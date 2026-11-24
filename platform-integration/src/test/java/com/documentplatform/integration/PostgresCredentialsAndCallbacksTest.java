package com.documentplatform.integration;

import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.envelope.ValueCipher;
import com.documentplatform.crypto.testkit.InMemoryKeyService;
import com.documentplatform.integration.callback.CallbackEntry;
import com.documentplatform.integration.callback.CallbackState;
import com.documentplatform.integration.config.ConfigJson;
import com.documentplatform.integration.config.Endpoint;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.credential.CredentialChange;
import com.documentplatform.integration.credential.CredentialExpiry;
import com.documentplatform.integration.credential.CredentialState;
import com.documentplatform.integration.credential.CredentialView;
import com.documentplatform.integration.credential.IntegrationCredentials;
import com.documentplatform.integration.credential.StoredCredential;
import com.documentplatform.integration.jdbc.IntegrationSchema;
import com.documentplatform.integration.jdbc.PostgresCallbackOutbox;
import com.documentplatform.integration.jdbc.PostgresCredentialStore;
import com.documentplatform.integration.jdbc.PostgresIntegrationStore;
import com.documentplatform.integration.store.ConfigChange;
import com.documentplatform.integration.store.IntegrationAudit;
import com.documentplatform.integration.store.IntegrationStore;
import com.documentplatform.integration.store.StoredIntegration;
import com.documentplatform.notifications.inmemory.InMemorySecretResolver;
import com.documentplatform.notifications.secret.SecretRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Schema version 2 in Postgres with the DML-only runtime user: client certificates sealed under the customer's keys
 * (platform-crypto's testkit key service), rotated, with history; the database's own rules; the callback outbox's
 * claims, leases and restart safety.
 */
class PostgresCredentialsAndCallbacksTest {

    static final CustomerId CUSTOMER = CustomerId.fromTenantId("TEN-TEST-2");

    @TempDir
    Path dir;

    private static StoredIntegration integration(DataSource app, IntegrationConfig c) {
        return new PostgresIntegrationStore(app).create(c, new ConfigChange(c.id(), c.entity(), c.product(), 1, "t",
                Instant.now().truncatedTo(ChronoUnit.MICROS), ConfigJson.fields(c), List.of()));
    }

    /** A self-signed client certificate and key as a PKCS#12 file (keytool; password "changeit"). */
    private byte[] p12(String name, int validDays) throws Exception {
        Path ks = dir.resolve(name + ".p12");
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", name, "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=" + name,
                "-validity", Integer.toString(validDays), "-storetype", "PKCS12", "-keystore", ks.toString(),
                "-storepass", "changeit", "-keypass", "changeit").redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) throw new IOException(new String(out));
        return Files.readAllBytes(ks);
    }

    private static String keyBase64(byte[] p12) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = new java.io.ByteArrayInputStream(p12)) {
            ks.load(in, "changeit".toCharArray());
        }
        String alias = Collections.list(ks.aliases()).get(0);
        return java.util.Base64.getEncoder().encodeToString(ks.getKey(alias, "changeit".toCharArray()).getEncoded());
    }

    @Test
    void certificatesSealedRotatedAndHistoryWithTheDmlOnlyUser() throws Exception {
        Pg db = Pg.migrated();
        DataSource app = db.appUser();
        IntegrationSchema.verify(app);
        StoredIntegration s = integration(app, JsonAndConfigTest.sample(SignIn.MutualTls.alone()));
        PostgresCredentialStore store = new PostgresCredentialStore(app);
        List<CredentialChange> audited = new ArrayList<>();
        IntegrationAudit audit = new IntegrationAudit() {
            @Override
            public void changed(ConfigChange change) {
            }

            @Override
            public void tested(com.documentplatform.integration.result.AttemptResult result, String by) {
            }

            @Override
            public void credentialChanged(CredentialChange change) {
                audited.add(change);
            }
        };
        IntegrationCredentials credentials = new IntegrationCredentials(store,
                new ValueCipher(new InMemoryKeyService().createKeysOnDemand()), e -> CUSTOMER,
                new InMemorySecretResolver(), audit, Clock.systemUTC());

        byte[] first = p12("pg-first", 60);
        byte[] second = p12("pg-second", 400);
        CredentialView current = credentials.upload(s, first, "changeit".toCharArray(), null, "alice");
        CredentialView next = credentials.upload(s, second, "changeit".toCharArray(),
                Instant.now().plusSeconds(3600), "alice");
        assertEquals(CredentialState.NEXT, next.state());
        assertNotNull(next.switchAt());

        // In the table: sealed text only, never the key.
        String key = keyBase64(first).substring(0, 40);
        try (var c = db.owner.getConnection(); var st = c.createStatement();
             var rs = st.executeQuery("select sealed, sha256, keystore_path from int_credential")) {
            while (rs.next()) {
                assertTrue(rs.getString(1).startsWith(ValueCipher.PREFIX));
                assertFalse(rs.getString(1).contains(key));
                assertNull(rs.getString(3));
            }
        }
        // Opened for a call.
        assertTrue(credentials.load(s.config(), CredentialState.CURRENT, Instant.now()).ok());
        assertTrue(credentials.load(s.config(), CredentialState.NEXT, Instant.now()).ok());

        credentials.switchToNext(s.id(), "bob");
        assertEquals(next.id(), credentials.current(s.id()).orElseThrow().id());
        StoredCredential retired = store.list(s.id()).stream().filter(r -> r.id().equals(current.id())).findFirst()
                .orElseThrow();
        assertEquals(CredentialState.RETIRED, retired.state());
        assertNull(retired.sealed(), "the retired key is dropped");
        List<CredentialChange> history = credentials.history(s.id());
        assertEquals(List.of(CredentialChange.Kind.UPLOADED, CredentialChange.Kind.UPLOADED,
                CredentialChange.Kind.SWITCHED), history.stream().map(CredentialChange::kind).toList());
        assertEquals(List.of("alice", "alice", "bob"), history.stream().map(CredentialChange::by).toList());
        assertEquals(history, audited);
        assertEquals(1, new CredentialExpiry(store).check(Instant.now().plus(java.time.Duration.ofDays(380))).size());

        // A change saved with a stale number is a conflict; a second CURRENT is refused by the database.
        StoredCredential now = store.list(s.id()).stream().filter(r -> r.state() == CredentialState.CURRENT)
                .findFirst().orElseThrow();
        IntegrationStore.Conflict stale = assertThrows(IntegrationStore.Conflict.class, () -> store.save(1,
                new CredentialChange(s.id(), s.config().entity(), s.config().product(), 2,
                        CredentialChange.Kind.SWITCH_SCHEDULED, now.id(), now.info().sha256(), "x", Instant.now()),
                List.of()));
        assertEquals(IntegrationCode.INT_CREDENTIAL_CONFLICT, stale.code());

        // The database's own rules, also against the app's user.
        assertThrows(IllegalStateException.class, () -> Pg.exec(app, "update int_credential set state = 'CURRENT', "
                + "retired_at = null where id = '" + current.id() + "'"));
        assertThrows(IllegalStateException.class, () -> Pg.exec(app, "delete from int_credential"));
        assertThrows(IllegalStateException.class, () -> Pg.exec(app, "update int_credential_change set changed_by = 'x'"));
        assertThrows(IllegalStateException.class, () -> Pg.exec(app, "delete from int_credential_change"));
        assertThrows(IllegalStateException.class, () -> Pg.exec(app, "drop table int_credential"));
    }

    @Test
    void theOutboxClaimsWithALeaseAndSurvivesARestart() throws Exception {
        Pg db = Pg.migrated();
        DataSource app = db.appUser();
        IntegrationConfig cb = IntegrationConfig.callback(JsonAndConfigTest.sample(new SignIn.None()).entity(),
                "DEMO", "Hooks", Endpoint.of("https://hooks.example/cases"), new SignIn.None(),
                SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/cb"));
        StoredIntegration s = integration(app, cb);
        Instant t0 = Instant.parse("2026-11-22T10:00:00Z");
        PostgresCallbackOutbox outbox = new PostgresCallbackOutbox(app);
        UUID id = UUID.randomUUID();
        assertTrue(outbox.enqueue(CallbackEntry.pending(id, s.id(), cb.entity(), cb.product(), "{\"case\":\"C-1\"}",
                t0)));
        assertFalse(outbox.enqueue(CallbackEntry.pending(id, s.id(), cb.entity(), cb.product(), "{}", t0)),
                "the ID is taken");

        // An instance claims it, then stops (a restart): another instance gets it only once the lease ran out.
        assertEquals(1, outbox.claimDue(t0, t0.plusSeconds(120), 10).size());
        PostgresCallbackOutbox afterRestart = new PostgresCallbackOutbox(app);
        assertTrue(afterRestart.claimDue(t0.plusSeconds(60), t0.plusSeconds(180), 10).isEmpty());
        List<CallbackEntry> again = afterRestart.claimDue(t0.plusSeconds(121), t0.plusSeconds(241), 10);
        assertEquals(1, again.size());
        assertEquals(CallbackState.SENDING, again.get(0).state());
        assertEquals("{\"case\":\"C-1\"}", again.get(0).body());

        afterRestart.markRetry(id, 1, "OUTAGE", "INT_OUTAGE", 503, t0.plusSeconds(300));
        CallbackEntry e = afterRestart.find(id).orElseThrow();
        assertEquals(CallbackState.PENDING, e.state());
        assertEquals(t0.plusSeconds(300), e.nextAttemptAt());
        assertNull(e.claimedUntil());
        afterRestart.markGaveUp(id, 2, "OUTAGE", "INT_OUTAGE", 503, t0.plusSeconds(400));
        assertEquals(List.of(id), outbox.gaveUp(cb.entity(), cb.product(), 10).stream().map(CallbackEntry::id)
                .toList());
        assertTrue(outbox.resend(id, t0.plusSeconds(500)));
        assertFalse(outbox.resend(id, t0.plusSeconds(500)), "only a GAVE_UP one");
        assertEquals(t0.plusSeconds(500), outbox.find(id).orElseThrow().windowFrom());
        outbox.claimDue(t0.plusSeconds(500), t0.plusSeconds(620), 10);
        outbox.markSent(id, 3, "SENT", "INT_SENT", 200, t0.plusSeconds(501));
        assertEquals(CallbackState.SENT, outbox.find(id).orElseThrow().state());
        assertEquals(1, outbox.purgeFinishedBefore(t0.plusSeconds(600)));
        assertTrue(outbox.find(id).isEmpty());
    }

    @Test
    void concurrentSendersNeverClaimTheSameCallback() throws Exception {
        Pg db = Pg.migrated();
        DataSource app = db.appUser();
        IntegrationConfig cb = IntegrationConfig.callback(UUID.fromString("5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f"), "DEMO", "Hooks",
                Endpoint.of("https://hooks.example/cases"), new SignIn.None(), SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/cb"));
        StoredIntegration s = integration(app, cb);
        Instant t0 = Instant.parse("2026-11-22T10:00:00Z");
        PostgresCallbackOutbox outbox = new PostgresCallbackOutbox(app);
        for (int i = 0; i < 40; i++) {
            outbox.enqueue(CallbackEntry.pending(UUID.randomUUID(), s.id(), cb.entity(), cb.product(), "{}", t0));
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<List<CallbackEntry>>> claims = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            claims.add(pool.submit(() -> {
                go.await();
                List<CallbackEntry> mine = new ArrayList<>();
                List<CallbackEntry> got;
                do {
                    got = new PostgresCallbackOutbox(app).claimDue(t0, t0.plusSeconds(120), 3);
                    mine.addAll(got);
                } while (!got.isEmpty());
                return mine;
            }));
        }
        go.countDown();
        Set<UUID> seen = new HashSet<>();
        int total = 0;
        for (Future<List<CallbackEntry>> f : claims) {
            for (CallbackEntry e : f.get(60, TimeUnit.SECONDS)) {
                assertTrue(seen.add(e.id()), "claimed twice: " + e.id());
                total++;
            }
        }
        pool.shutdown();
        assertEquals(40, total);
    }
}
