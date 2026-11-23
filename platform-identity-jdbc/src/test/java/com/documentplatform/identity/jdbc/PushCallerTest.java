package com.documentplatform.identity.jdbc;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.identity.IdentityConnectionStatus;
import com.documentplatform.identity.admission.ConnectionKey;
import com.documentplatform.identity.admission.EntityId;
import com.documentplatform.identity.admission.MachineSourceBindings;
import com.documentplatform.identity.inmemory.InMemoryIdentityStore;
import com.documentplatform.identity.push.DirectoryMachineSourceBindings;
import com.documentplatform.identity.push.PushCaller;
import com.documentplatform.identity.push.PushCallerDirectory;
import com.documentplatform.identity.push.PushCallerIdentity;
import com.documentplatform.identity.push.PushCallerInput;
import com.documentplatform.identity.push.PushCallerResult;
import com.documentplatform.identity.push.PushCallerSettings;
import com.documentplatform.identity.push.PushRefusal;
import com.documentplatform.identity.store.ChangeSource;
import com.documentplatform.identity.store.EntityStore;
import com.documentplatform.identity.store.LoginConnection;
import com.documentplatform.identity.store.LoginConnectionStore;
import com.documentplatform.identity.store.MachineBinding;
import com.documentplatform.identity.store.MachineBindingStore;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static com.documentplatform.identity.jdbc.PostgresIdentityStoresTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * P028 B1: the push-API caller check, with the same rules over the in-memory directory and over Postgres (the
 * DML-only app user). Made-up products, claims and clients only.
 */
class PushCallerTest {

    static final String AUD = "api://product-a";
    static final String CLIENT = "0oaSender01";

    /** Two throwaway self-signed certificates (public parts only; their keys were discarded). */
    static final X509Certificate CERT_ONE = cert("""
            -----BEGIN CERTIFICATE-----
            MIIBXDCCAQOgAwIBAgIITuLajl3FqYYwCgYIKoZIzj0EAwMwIjEgMB4GA1UEAxMX
            cHVzaC1jYWxsZXItb25lLmV4YW1wbGUwIBcNMjYxMTIzMDMzMTMwWhgPMjEyNjEw
            MzAwMzMxMzBaMCIxIDAeBgNVBAMTF3B1c2gtY2FsbGVyLW9uZS5leGFtcGxlMFkw
            EwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEJYaEyQLU7IW6ruTT6v6JPlciHc/1JQqD
            HlgOXLUhGayvbthK9MGt6kWRNIDJ1eEAd/DqyMEnNZ2Ij01arwg296MhMB8wHQYD
            VR0OBBYEFBFqXq7X/UUppYLy+Xx2WYCwaeePMAoGCCqGSM49BAMDA0cAMEQCIESR
            GicwCm+X79wdIz+mc9YaWuXFh7xZfnn7FrAqYIAMAiAWtSFXSr4V9JJVJ6cFJQMw
            RmeC7Vr7EUnNa2G5tKkz5A==
            -----END CERTIFICATE-----
            """);
    static final X509Certificate CERT_TWO = cert("""
            -----BEGIN CERTIFICATE-----
            MIIBXzCCAQSgAwIBAgIJAL/j6C3qZvD/MAoGCCqGSM49BAMDMCIxIDAeBgNVBAMT
            F3B1c2gtY2FsbGVyLXR3by5leGFtcGxlMCAXDTI2MTEyMzAzMzEzMVoYDzIxMjYx
            MDMwMDMzMTMxWjAiMSAwHgYDVQQDExdwdXNoLWNhbGxlci10d28uZXhhbXBsZTBZ
            MBMGByqGSM49AgEGCCqGSM49AwEHA0IABKMFmMetX43oHb9CLbeVwpXTjUI9F7Em
            ZeUWeLLy+5DITlQkNmDOx+xsdH2RRG7fH1JX27sQfmrEQdj2nS2eL0ujITAfMB0G
            A1UdDgQWBBTNglTo73lmZGbKRqMvYuZKkPzHzjAKBggqhkjOPQQDAwNJADBGAiEA
            g1coe2/eChaaD8e44LPjkG6G3JOKk6hk4gXv0Ez/zakCIQDW3Z7+px4TqNniiFbb
            FXnvIVtFIJy/dapLvk0WfueKFA==
            -----END CERTIFICATE-----
            """);

    private static X509Certificate cert(String pem) {
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A movable clock for the cache. */
    static final class TestClock extends Clock {
        Instant now = T;

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** The stores and the directory under test: in memory, or Postgres with the DML-only user. */
    record World(EntityStore entities, LoginConnectionStore connections, MachineBindingStore bindings,
                 PushCallerDirectory directory, Pg db) {
    }

    static World world(String kind) {
        World w;
        if (kind.equals("memory")) {
            InMemoryIdentityStore s = new InMemoryIdentityStore();
            w = new World(s, s, s, s.pushCallerDirectory(), null);
        } else {
            Pg db = Pg.migrated();
            DataSource app = db.appUser(DML);
            IdentitySchema.verify(app);
            w = new World(new PostgresEntityStore(app), new PostgresLoginConnectionStore(app),
                    new PostgresMachineBindingStore(app), new PostgresPushCallerDirectory(app), db);
        }
        // A customer with S1, S2 activated (S3 not), an ACTIVE PRODUCT_A connection, one token client on S1.
        w.entities().saveCustomer(TENANT, "Test Customer");
        w.entities().syncSlots(TENANT, List.of(S1, S2, S3));
        w.entities().activate(S1, "Alpha", "admin-1", T);
        w.entities().activate(S2, "Beta", "admin-1", T);
        w.connections().insert(connection(TENANT, "PRODUCT_A", AUD), ChangeSource.SETUP, "setup");
        w.bindings().insertBinding(MachineBinding.create(TENANT, "PRODUCT_A", CLIENT, S1, "sender-a", null),
                ChangeSource.SETUP, "setup");
        return w;
    }

    static PushCaller caller(World w, PushCallerSettings s, Clock clock) {
        return new PushCaller(w.directory(), s, clock);
    }

    static PushCaller caller(World w) {
        return caller(w, PushCallerSettings.of("PRODUCT_A").withCacheFor(Duration.ZERO), Clock.systemUTC());
    }

    static PushCallerInput token(Map<String, Object> claims) {
        return PushCallerInput.token(ISSUER, List.of(AUD), claims);
    }

    static PushRefusal refusal(PushCallerResult r) {
        assertFalse(r.allowed(), r.toString());
        return r.refusal();
    }

    static void bind(World w, String clientId, String slot, String source) {
        w.bindings().insertBinding(MachineBinding.create(TENANT, "PRODUCT_A", clientId, slot, source, null),
                ChangeSource.SETUP, "setup");
    }

    static void switchOff(World w, String clientId) {
        MachineBinding b = w.bindings().bindings(TENANT, "PRODUCT_A").stream()
                .filter(x -> x.clientId().equals(clientId)).findFirst().orElseThrow();
        w.bindings().updateBinding(b.switchedOff(), b.version(), ChangeSource.APP, "it-admin").orElseThrow();
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "postgres"})
    void aTokenFromEachClaimOrderFindsTheEntityAndSource(String kind) {
        World w = world(kind);
        PushCaller pc = caller(w);
        for (String claim : List.of("client_id", "azp", "cid", "sub")) {
            PushCallerResult r = pc.check(token(Map.of(claim, CLIENT)));
            assertTrue(r.allowed(), claim + ": " + r);
            PushCallerIdentity id = r.identity();
            assertEquals(TENANT, id.tenantId());
            assertEquals(S1, id.slotId());
            assertEquals(EntityUuid.fromSlotId(S1), id.entityUuid());
            assertEquals("sender-a", id.sourceKey());
            assertEquals(CLIENT, id.clientId());
            assertEquals(PushCallerIdentity.Via.TOKEN, id.via());
        }
        // The first configured claim present wins: client_id before sub.
        assertTrue(pc.check(token(Map.of("client_id", CLIENT, "sub", "someone-else"))).allowed());
        assertEquals(PushRefusal.IDN_PUSH_NOT_BOUND,
                refusal(pc.check(token(Map.of("client_id", "someone-else", "sub", CLIENT)))));
        // A product's own claim order.
        PushCaller own = caller(w, PushCallerSettings.of("PRODUCT_A").withClientIdClaims(List.of("app_client"))
                .withCacheFor(Duration.ZERO), Clock.systemUTC());
        assertTrue(own.check(token(Map.of("app_client", CLIENT))).allowed());
        assertEquals(PushRefusal.IDN_PUSH_NO_CLIENT_ID, refusal(own.check(token(Map.of("client_id", CLIENT)))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "postgres"})
    void everyTokenRefusal(String kind) {
        World w = world(kind);
        PushCaller pc = caller(w);
        assertEquals(PushRefusal.IDN_PUSH_NO_CONNECTION, refusal(pc.check(PushCallerInput.token(
                URI.create("https://other-login.example.test/"), List.of(AUD), Map.of("client_id", CLIENT)))));
        assertEquals(PushRefusal.IDN_PUSH_NO_CONNECTION, refusal(pc.check(PushCallerInput.token(
                URI.create(ISSUER + "/"), List.of(AUD), Map.of("client_id", CLIENT)))), "the issuer exactly");
        assertEquals(PushRefusal.IDN_PUSH_AUDIENCE, refusal(pc.check(PushCallerInput.token(ISSUER,
                List.of("api://product-b"), Map.of("client_id", CLIENT)))));
        assertEquals(PushRefusal.IDN_PUSH_NO_CLIENT_ID, refusal(pc.check(token(Map.of("scope", "x")))));
        assertEquals(PushRefusal.IDN_PUSH_NO_CLIENT_ID, refusal(pc.check(token(Map.of("client_id", " ")))));
        assertEquals(PushRefusal.IDN_PUSH_NOT_BOUND, refusal(pc.check(token(Map.of("client_id", "0OASENDER01")))),
                "client IDs are case-sensitive");
        assertEquals(PushRefusal.IDN_PUSH_NO_CONNECTION, refusal(new PushCaller(w.directory(),
                PushCallerSettings.of("PRODUCT_B").withCacheFor(Duration.ZERO))
                .check(token(Map.of("client_id", CLIENT)))), "another product: no connection of its own");

        // Inactive entity: a client bound to S3 (never activated), and S1 suspended.
        bind(w, "0oaSender03", S3, "sender-a");
        assertEquals(PushRefusal.IDN_PUSH_ENTITY_NOT_ACTIVE, refusal(pc.check(token(Map.of("client_id",
                "0oaSender03")))));
        w.entities().suspend(S1);
        assertEquals(PushRefusal.IDN_PUSH_ENTITY_NOT_ACTIVE, refusal(pc.check(token(Map.of("client_id", CLIENT)))));
        w.entities().resume(S1);
        assertTrue(pc.check(token(Map.of("client_id", CLIENT))).allowed());

        switchOff(w, CLIENT);
        assertEquals(PushRefusal.IDN_PUSH_BINDING_OFF, refusal(pc.check(token(Map.of("client_id", CLIENT)))));

        // The connection no longer ACTIVE.
        bind(w, "0oaSender02", S2, "sender-b");
        assertTrue(pc.check(token(Map.of("client_id", "0oaSender02"))).allowed());
        LoginConnection c = w.connections().active(TENANT, "PRODUCT_A").orElseThrow();
        w.connections().update(new LoginConnection(c.connectionId(), c.tenantId(), c.productCode(), c.providerId(),
                c.providerType(), c.issuer(), c.browserClientId(), c.apiAudience(), c.secretReference(),
                IdentityConnectionStatus.DISABLED, c.configurationVersion()), c.configurationVersion(),
                ChangeSource.APP, "it-admin").orElseThrow();
        assertEquals(PushRefusal.IDN_PUSH_NO_CONNECTION, refusal(pc.check(token(Map.of("client_id",
                "0oaSender02")))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "postgres"})
    void configuredEntityAndSourceClaimsMustMatchWhenPresent(String kind) {
        World w = world(kind);
        PushCaller pc = caller(w, PushCallerSettings.of("PRODUCT_A").withEntityClaim("delivery_entity")
                .withSourceClaim("sending_system").withCacheFor(Duration.ZERO), Clock.systemUTC());
        assertTrue(pc.check(token(Map.of("client_id", CLIENT))).allowed(), "not in the token: the binding decides");
        assertTrue(pc.check(token(Map.of("client_id", CLIENT, "delivery_entity", S1, "sending_system", "sender-a")))
                .allowed());
        assertTrue(pc.check(token(Map.of("client_id", CLIENT, "delivery_entity",
                EntityUuid.fromSlotId(S1).toString()))).allowed(), "the entity UUID matches too");
        assertEquals(PushRefusal.IDN_PUSH_CLAIM_MISMATCH, refusal(pc.check(token(Map.of("client_id", CLIENT,
                "delivery_entity", S2)))));
        assertEquals(PushRefusal.IDN_PUSH_CLAIM_MISMATCH, refusal(pc.check(token(Map.of("client_id", CLIENT,
                "sending_system", "sender-b")))));
        assertEquals(PushRefusal.IDN_PUSH_CLAIM_MISMATCH, refusal(pc.check(token(Map.of("client_id", CLIENT,
                "sending_system", List.of("sender-a"))))), "a claim that is not text never matches");
        // Not configured: such claims are not read.
        assertTrue(caller(w).check(token(Map.of("client_id", CLIENT, "delivery_entity", S2))).allowed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "postgres"})
    void certificatesBoundNotBoundAndInARotationOverlap(String kind) {
        World w = world(kind);
        PushCaller pc = caller(w);
        String one = PushCaller.fingerprint(CERT_ONE);
        String two = PushCaller.fingerprint(CERT_TWO);
        assertTrue(PushCaller.isFingerprint(one), one);
        assertNotEquals(one, two);

        PushCallerResult r = pc.check(PushCallerInput.certificate(CERT_ONE));
        assertEquals(PushRefusal.IDN_PUSH_CERT_NOT_BOUND, refusal(r));
        assertEquals(one, r.fingerprint(), "the IT Admin sees the fingerprint");

        bind(w, one, S2, "sender-b");
        PushCallerResult ok = pc.check(PushCallerInput.certificate(CERT_ONE));
        assertTrue(ok.allowed(), ok.toString());
        assertEquals(S2, ok.identity().slotId());
        assertEquals("sender-b", ok.identity().sourceKey());
        assertEquals(one, ok.identity().clientId());
        assertEquals(PushCallerIdentity.Via.CERTIFICATE, ok.identity().via());

        // Rotation overlap: the next certificate for the same entity and source; both work, then the old one is off.
        bind(w, two, S2, "sender-b");
        assertTrue(pc.check(PushCallerInput.certificate(CERT_ONE)).allowed());
        assertTrue(pc.check(PushCallerInput.certificate(CERT_TWO)).allowed());
        switchOff(w, one);
        assertEquals(PushRefusal.IDN_PUSH_BINDING_OFF, refusal(pc.check(PushCallerInput.certificate(CERT_ONE))));
        assertTrue(pc.check(PushCallerInput.certificate(CERT_TWO)).allowed());

        w.entities().suspend(S2);
        assertEquals(PushRefusal.IDN_PUSH_ENTITY_NOT_ACTIVE,
                refusal(pc.check(PushCallerInput.certificate(CERT_TWO))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "postgres"})
    void aCertificateCallerStillNeedsTheCustomersActiveConnection(String kind) {
        World w = world(kind);
        PushCaller pc = caller(w);
        String one = PushCaller.fingerprint(CERT_ONE);
        bind(w, one, S2, "sender-b");
        assertTrue(pc.check(PushCallerInput.certificate(CERT_ONE)).allowed(), "bound, connection ACTIVE");

        // The app disables the customer's connection: no ACTIVE connection is left for the customer and product.
        LoginConnection c = w.connections().active(TENANT, "PRODUCT_A").orElseThrow();
        w.connections().update(new LoginConnection(c.connectionId(), c.tenantId(), c.productCode(), c.providerId(),
                c.providerType(), c.issuer(), c.browserClientId(), c.apiAudience(), c.secretReference(),
                IdentityConnectionStatus.DISABLED, c.configurationVersion()), c.configurationVersion(),
                ChangeSource.APP, "it-admin").orElseThrow();
        assertTrue(w.connections().active(TENANT, "PRODUCT_A").isEmpty());

        PushCallerResult r = pc.check(PushCallerInput.certificate(CERT_ONE));
        assertEquals(PushRefusal.IDN_PUSH_NO_CONNECTION, refusal(r), "the binding and entity are fine, still refused");
        assertEquals(one, r.fingerprint());
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "postgres"})
    void tokenAndCertificateBothRequiredTheSameOrDifferent(String kind) {
        World w = world(kind);
        String one = PushCaller.fingerprint(CERT_ONE);
        String two = PushCaller.fingerprint(CERT_TWO);
        bind(w, one, S1, "sender-a");  // the same entity and source as the token client
        bind(w, two, S2, "sender-b");  // another
        PushCaller both = caller(w, PushCallerSettings.of("PRODUCT_A").withTokenAndCertificateRequired(true)
                .withCacheFor(Duration.ZERO), Clock.systemUTC());
        PushCallerResult same = both.check(PushCallerInput.tokenAndCertificate(ISSUER, List.of(AUD),
                Map.of("client_id", CLIENT), CERT_ONE));
        assertTrue(same.allowed(), same.toString());
        assertEquals(PushCallerIdentity.Via.TOKEN_AND_CERTIFICATE, same.identity().via());
        assertEquals(CLIENT, same.identity().clientId());
        assertEquals(PushRefusal.IDN_PUSH_TOKEN_AND_CERT_DIFFER, refusal(both.check(
                PushCallerInput.tokenAndCertificate(ISSUER, List.of(AUD), Map.of("client_id", CLIENT), CERT_TWO))));
        assertEquals(PushRefusal.IDN_PUSH_CERT_NOT_BOUND, refusal(both.check(token(Map.of("client_id", CLIENT)))),
                "no certificate where both are required");
        assertEquals(PushRefusal.IDN_PUSH_NO_CLIENT_ID, refusal(both.check(PushCallerInput.certificate(CERT_ONE))),
                "no token where both are required");
        // Not required, but both given: both are checked and must agree.
        assertEquals(PushRefusal.IDN_PUSH_TOKEN_AND_CERT_DIFFER, refusal(caller(w).check(
                PushCallerInput.tokenAndCertificate(ISSUER, List.of(AUD), Map.of("client_id", CLIENT), CERT_TWO))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "postgres"})
    void aBindingSwitchedOffTakesEffectWithinTheCacheTime(String kind) {
        World w = world(kind);
        TestClock clock = new TestClock();
        PushCaller pc = caller(w, PushCallerSettings.of("PRODUCT_A"), clock);
        assertEquals(Duration.ofSeconds(30), pc.settings().cacheFor());
        assertTrue(pc.check(token(Map.of("client_id", CLIENT))).allowed());
        switchOff(w, CLIENT);
        clock.now = T.plusSeconds(29);
        assertTrue(pc.check(token(Map.of("client_id", CLIENT))).allowed(), "kept for the cache time");
        clock.now = T.plusSeconds(30);
        assertEquals(PushRefusal.IDN_PUSH_BINDING_OFF, refusal(pc.check(token(Map.of("client_id", CLIENT)))));
        // Refusals are kept too: a binding switched back on counts after the cache time.
        MachineBinding off = w.bindings().bindings(TENANT, "PRODUCT_A").get(0);
        w.bindings().updateBinding(off.activeFor(S1, "sender-a", null), off.version(), ChangeSource.APP,
                "it-admin").orElseThrow();
        clock.now = T.plusSeconds(45);
        assertEquals(PushRefusal.IDN_PUSH_BINDING_OFF, refusal(pc.check(token(Map.of("client_id", CLIENT)))));
        clock.now = T.plusSeconds(61);
        assertTrue(pc.check(token(Map.of("client_id", CLIENT))).allowed());
        // forget() drops what is kept at once.
        switchOff(w, CLIENT);
        pc.forget();
        assertEquals(PushRefusal.IDN_PUSH_BINDING_OFF, refusal(pc.check(token(Map.of("client_id", CLIENT)))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "postgres"})
    void theSourceLookupBesideMachineEntityBindings(String kind) {
        World w = world(kind);
        MachineSourceBindings sources = new DirectoryMachineSourceBindings(w.directory(), "PRODUCT_A");
        ConnectionKey key = new ConnectionKey("okta", ISSUER, AUD);
        MachineSourceBindings.MachineSource s = sources.find(key, CLIENT).orElseThrow();
        assertEquals(EntityId.of(EntityUuid.fromSlotId(S1)), s.entity());
        assertEquals(S1, s.slotId());
        assertEquals("sender-a", s.sourceKey());
        assertTrue(sources.find(new ConnectionKey("other-idp", ISSUER, AUD), CLIENT).isEmpty());
        assertTrue(sources.find(new ConnectionKey("okta", ISSUER, "api://product-b"), CLIENT).isEmpty());
        assertTrue(sources.find(key, " ").isEmpty());
        switchOff(w, CLIENT);
        assertTrue(sources.find(key, CLIENT).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgres"})
    void aDatabaseErrorThrowsAndNeverFallsBack(String kind) {
        World w = world(kind);
        PushCaller pc = caller(w, PushCallerSettings.of("PRODUCT_A"), Clock.systemUTC());
        w.db().exec("alter table idn_machine_binding rename to idn_machine_binding_gone");
        assertThrows(IdentityStoreException.class, () -> pc.check(token(Map.of("client_id", CLIENT))));
        w.db().exec("alter table idn_machine_binding_gone rename to idn_machine_binding");
        assertTrue(pc.check(token(Map.of("client_id", CLIENT))).allowed(), "an error is never kept");
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory"})
    void noTokenOrCertificateContentInTheLog(String kind) {
        World w = world(kind);
        bind(w, PushCaller.fingerprint(CERT_ONE), S2, "sender-b");
        List<String> lines = new ArrayList<>();
        Logger logger = Logger.getLogger(PushCaller.class.getName());
        java.util.logging.Level before = logger.getLevel();
        Handler h = new Handler() {
            @Override
            public void publish(LogRecord r) {
                String m = r.getMessage();
                Object[] p = r.getParameters();
                lines.add(p == null ? m : java.text.MessageFormat.format(m, p));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(h);
        logger.setLevel(java.util.logging.Level.ALL);
        try {
            PushCaller pc = caller(w);
            String secretish = "eyJhbGciOiJSUzI1NiJ9.payload.signature";
            pc.check(token(Map.of("client_id", "0oaUnknown", "raw_token", secretish, "email", "a@b.example")));
            pc.check(token(Map.of("client_id", CLIENT, "email", "a@b.example")));
            pc.check(PushCallerInput.certificate(CERT_ONE));
            pc.check(PushCallerInput.certificate(CERT_TWO));
            String all = String.join("\n", lines);
            assertTrue(all.contains("IDN_PUSH_NOT_BOUND"), all);
            assertTrue(all.contains("IDN_PUSH_CERT_NOT_BOUND"), all);
            assertTrue(all.contains(PushCaller.fingerprint(CERT_TWO)), "the fingerprint is allowed: " + all);
            for (String never : List.of(secretish, "a@b.example", "0oaUnknown", CLIENT, "MII", "push-caller-")) {
                assertFalse(all.contains(never), never + " in: " + all);
            }
            assertFalse(PushCallerInput.certificate(CERT_ONE).toString().contains("CN="));
            assertFalse(token(Map.of("client_id", CLIENT)).toString().contains(CLIENT));
        } finally {
            logger.removeHandler(h);
            logger.setLevel(before);
        }
    }
}
