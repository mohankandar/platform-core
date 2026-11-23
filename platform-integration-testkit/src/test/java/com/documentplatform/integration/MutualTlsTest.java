package com.documentplatform.integration;

import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.envelope.ValueCipher;
import com.documentplatform.crypto.testkit.InMemoryKeyService;
import com.documentplatform.integration.config.ConfigCheck;
import com.documentplatform.integration.config.ConfigJson;
import com.documentplatform.integration.config.Endpoint;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.credential.CredentialChange;
import com.documentplatform.integration.credential.CredentialExpiry;
import com.documentplatform.integration.credential.CredentialSource;
import com.documentplatform.integration.credential.CredentialState;
import com.documentplatform.integration.credential.CredentialView;
import com.documentplatform.integration.credential.IntegrationCredentials;
import com.documentplatform.integration.credential.KeystoreRef;
import com.documentplatform.integration.credential.StoredCredential;
import com.documentplatform.integration.engine.CallEngine;
import com.documentplatform.integration.engine.TestAction;
import com.documentplatform.outbound.Outbound;
import com.documentplatform.integration.result.AttemptResult;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.outbound.Installation;
import com.documentplatform.integration.store.ConfigChange;
import com.documentplatform.integration.store.IntegrationAudit;
import com.documentplatform.integration.store.StoredIntegration;
import com.documentplatform.integration.testkit.InMemoryCredentialStore;
import com.documentplatform.integration.testkit.MockDestination;
import com.documentplatform.integration.testkit.TestCertificates;
import com.documentplatform.notifications.inmemory.InMemorySecretResolver;
import com.documentplatform.notifications.secret.SecretRef;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * B1, client certificates (mutual TLS) against an https mock that requires one: the right certificate, a wrong one,
 * an expired one, NEXT tested and switched (by the admin and at a set time), a keystore source, and every refusal.
 * Uploaded certificates are sealed with platform-crypto's testkit key service. The calls that need a TLS connection
 * to succeed are skipped where something on the machine intercepts TLS to 127.0.0.1 (as in {@link PrivateCaTest});
 * CI forbids skips. The Postgres store with the DML-only user is in platform-integration's
 * {@code PostgresCredentialStoreTest}.
 */
class MutualTlsTest {

    static final CustomerId CUSTOMER = CustomerId.fromTenantId("TEN-TEST-1");
    static final String P12_PASSWORD = "p12-pass-1";
    static final SecretRef KEYSTORE_PASSWORD = SecretRef.of("int/keystore-password");

    @TempDir
    static Path dir;
    static TestCertificates.Ca ca;
    static TestCertificates.Credential good;
    static TestCertificates.Credential next;
    static TestCertificates.Credential other;
    static TestCertificates.Credential expired;
    static TestCertificates.Credential later;
    static MockDestination mock;
    static boolean intercepted;

    @BeforeAll
    static void startTls() throws Exception {
        ca = TestCertificates.ca(dir, "mtls-ca");
        good = TestCertificates.credential(dir, "client-good", -1, 60);
        next = TestCertificates.credential(dir, "client-next", -1, 400);
        other = TestCertificates.credential(dir, "client-other", -1, 60);
        expired = TestCertificates.credential(dir, "client-expired", -20, 5);
        later = TestCertificates.credential(dir, "client-later", 10, 60);
        // The server trusts good, next and the expired one (so our own expiry check is what refuses it).
        TestCertificates.Server server = TestCertificates.serverTrusting(dir, ca, "mtls-server", "dns:localhost",
                good.certificate(), next.certificate(), expired.certificate());
        mock = MockDestination.startMutualTls(server.context());
        intercepted = !server.certificate().equals(peerCertificate(mock.port()));
        if (intercepted) {
            System.err.println("MutualTlsTest: TLS to 127.0.0.1 is intercepted on this machine; the end-to-end mutual "
                    + "TLS calls are skipped, the rest still runs.");
        }
    }

    @AfterAll
    static void stopTls() {
        mock.close();
    }

    /** A clock the test moves. */
    static final class MovingClock extends Clock {
        Instant now = Instant.now();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MovingClock clock = new MovingClock();
    private final InMemorySecretResolver secrets = new InMemorySecretResolver();
    private final InMemoryCredentialStore store = new InMemoryCredentialStore();
    private final InMemoryKeyService keys = new InMemoryKeyService().createKeysOnDemand();
    private final List<CredentialChange> audited = new ArrayList<>();
    private IntegrationCredentials credentials;
    private CallEngine engine;
    private StoredIntegration integration;

    @BeforeEach
    void start() {
        mock.reset();
        mock.expectNone();
        IntegrationAudit audit = new IntegrationAudit() {
            @Override
            public void changed(ConfigChange change) {
            }

            @Override
            public void tested(AttemptResult result, String by) {
            }

            @Override
            public void credentialChanged(CredentialChange change) {
                audited.add(change);
            }
        };
        credentials = new IntegrationCredentials(store, new ValueCipher(keys), e -> CUSTOMER, secrets, audit, clock);
        engine = new CallEngine(new Outbound(installation()), secrets, credentials, clock);
        integration = Fx.stored(config(SignIn.MutualTls.alone()));
    }

    @AfterEach
    void stop() {
        engine.close();
    }

    private static Installation installation() {
        return Installation.onPremises().withInternalAddresses(true).withPrivateCa(ca.pem());
    }

    private static IntegrationConfig config(SignIn signIn) {
        return Fx.twoStep(mock.addressByName(), signIn);
    }

    private static String fingerprint(TestCertificates.Credential c) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(c.certificate().getEncoded()));
    }

    private CredentialView upload(TestCertificates.Credential c) throws Exception {
        return credentials.upload(integration, TestCertificates.pkcs12(c, P12_PASSWORD), P12_PASSWORD.toCharArray(),
                null, "it-admin");
    }

    // ---- Calls ----

    @Test
    void theRightCertificateIsPresented() throws Exception {
        CredentialView v = upload(good);
        assertEquals(CredentialState.CURRENT, v.state());
        assertEquals(CredentialSource.UPLOADED, v.source());
        assertEquals(fingerprint(good), v.info().sha256());
        assertEquals("CN=client-good", v.info().subject());
        assumeFalse(intercepted, "TLS to 127.0.0.1 is intercepted on this machine");
        AttemptResult r = engine.attempt(integration, Fx.delivery(2));
        assertEquals(Outcome.SENT, r.outcome(), r.toString());
        assertFalse(mock.clientCertificates().isEmpty());
        assertTrue(mock.clientCertificates().stream().allMatch(c -> c.sha256().equals(v.info().sha256())));
        // The health check presents it too.
        assertTrue(engine.health(integration).healthy());
    }

    @Test
    void aCertificateTheDestinationDoesNotTrustIsRefused() throws Exception {
        upload(other);
        assumeFalse(intercepted, "TLS to 127.0.0.1 is intercepted on this machine");
        AttemptResult r = engine.attempt(integration, Fx.delivery(1));
        assertEquals(Outcome.SIGN_IN_FAILED, r.outcome(), r.toString());
        assertEquals(IntegrationCode.INT_CREDENTIAL_REFUSED, r.code());
        assertTrue(mock.calls().isEmpty(), "nothing reached the destination");
        assertTrue(IntegrationCode.INT_CREDENTIAL_REFUSED.message().contains("fingerprint"));
    }

    @Test
    void withoutACertificateNothingIsSent() {
        AttemptResult r = engine.attempt(integration, Fx.delivery(1));
        assertEquals(Outcome.NOT_CONFIGURED, r.outcome());
        assertEquals(IntegrationCode.INT_CREDENTIAL_MISSING, r.code());
        assertTrue(mock.received().isEmpty());
    }

    @Test
    void anExpiredCurrentCertificateRefusesTheCall() throws Exception {
        // Refused at upload when it has expired already.
        IntegrationCredentials.Refused atUpload = assertThrows(IntegrationCredentials.Refused.class,
                () -> upload(expired));
        assertEquals(IntegrationCode.INT_CREDENTIAL_EXPIRED, atUpload.code());
        // A good one that later runs out refuses every call (SIGN_IN_FAILED), before anything is sent.
        upload(good);
        clock.now = good.certificate().getNotAfter().toInstant().plusSeconds(1);
        AttemptResult r = engine.attempt(integration, Fx.delivery(1));
        assertEquals(Outcome.SIGN_IN_FAILED, r.outcome());
        assertEquals(IntegrationCode.INT_CREDENTIAL_EXPIRED, r.code());
        assertEquals(IntegrationCode.INT_CREDENTIAL_EXPIRED, engine.health(integration).code());
        assertTrue(mock.received().isEmpty());
    }

    @Test
    void nextIsTestedThenSwitchedWithoutDowntime() throws Exception {
        CredentialView current = upload(good);
        CredentialView nx = upload(next);
        assertEquals(CredentialState.NEXT, nx.state());
        // Only two in use.
        IntegrationCredentials.Refused third = assertThrows(IntegrationCredentials.Refused.class, () -> upload(other));
        assertEquals(IntegrationCode.INT_CREDENTIAL_NEXT_EXISTS, third.code());

        TestAction test = new TestAction(engine, IntegrationAudit.NONE);
        if (!intercepted) {
            AttemptResult withNext = test.runWithNext(integration, "it-admin");
            assertEquals(Outcome.SENT, withNext.outcome(), withNext.toString());
            assertTrue(mock.clientCertificates().stream().allMatch(c -> c.sha256().equals(nx.info().sha256())),
                    "the Test of NEXT presented NEXT");
            mock.clearRecords();
            assertEquals(Outcome.SENT, engine.attempt(integration, Fx.delivery(1)).outcome());
            assertTrue(mock.clientCertificates().stream().allMatch(c -> c.sha256().equals(current.info().sha256())),
                    "calls still present CURRENT");
            mock.clearRecords();
        }

        CredentialView switched = credentials.switchToNext(integration.id(), "it-admin");
        assertEquals(nx.id(), switched.id());
        assertEquals(CredentialState.CURRENT, switched.state());
        List<CredentialView> all = credentials.list(integration.id());
        assertEquals(CredentialState.RETIRED, all.stream().filter(c -> c.id().equals(current.id())).findFirst()
                .orElseThrow().state());
        assertTrue(credentials.next(integration.id()).isEmpty());
        // The retired one's key is no longer stored.
        StoredCredential retired = store.rows().stream().filter(r -> r.id().equals(current.id())).findFirst()
                .orElseThrow();
        assertNull(retired.sealed());
        if (!intercepted) {
            assertEquals(Outcome.SENT, engine.attempt(integration, Fx.delivery(1)).outcome());
            assertTrue(mock.clientCertificates().stream().allMatch(c -> c.sha256().equals(nx.info().sha256())));
        }
        // Versioned and audited like the configuration.
        List<CredentialChange> history = credentials.history(integration.id());
        assertEquals(List.of(CredentialChange.Kind.UPLOADED, CredentialChange.Kind.UPLOADED,
                CredentialChange.Kind.SWITCHED), history.stream().map(CredentialChange::kind).toList());
        assertEquals(List.of(1, 2, 3), history.stream().map(CredentialChange::version).toList());
        assertEquals(history, audited);
        assertEquals("it-admin", history.get(2).by());
        assertEquals(IntegrationCode.INT_CREDENTIAL_NO_NEXT, assertThrows(IntegrationCredentials.Refused.class,
                () -> credentials.switchToNext(integration.id(), "it-admin")).code());
    }

    @Test
    void nextBecomesCurrentAtItsSetTime() throws Exception {
        upload(good);
        CredentialView nx = upload(next);
        Instant at = clock.now.plus(Duration.ofDays(2));
        credentials.scheduleSwitch(integration.id(), at, "it-admin");
        assertTrue(credentials.switchDue(at.minusSeconds(1)).isEmpty());
        List<CredentialView> done = credentials.switchDue(at);
        assertEquals(1, done.size());
        assertEquals(nx.id(), credentials.current(integration.id()).orElseThrow().id());
        assertEquals(IntegrationCredentials.SCHEDULED, credentials.history(integration.id()).get(3).by());
        assertEquals(CredentialChange.Kind.SWITCHED_AT_TIME, credentials.history(integration.id()).get(3).kind());
        // A NEXT that is not valid yet waits, and cannot be switched to by hand either.
        IntegrationConfig c2 = config(SignIn.MutualTls.alone());
        StoredIntegration second = Fx.stored(c2);
        credentials.upload(second, TestCertificates.pkcs12(good, P12_PASSWORD), P12_PASSWORD.toCharArray(), null, "a");
        credentials.upload(second, TestCertificates.pkcs12(later, P12_PASSWORD), P12_PASSWORD.toCharArray(),
                clock.now.plusSeconds(60), "a");
        assertTrue(credentials.switchDue(clock.now.plusSeconds(61)).isEmpty());
        assertEquals(IntegrationCode.INT_CREDENTIAL_NOT_YET_VALID, assertThrows(IntegrationCredentials.Refused.class,
                () -> credentials.switchToNext(second.id(), "a")).code());
        // Discarded without being used.
        assertEquals(CredentialState.RETIRED, credentials.discardNext(second.id(), "a").state());
    }

    @Test
    void aKeystoreTheCustomerManages() throws Exception {
        Path file = TestCertificates.keystore(good, dir.resolve("customer-keystore.p12"), "integration-client",
                "ks-pass-1");
        secrets.put(KEYSTORE_PASSWORD, "ks-pass-1");
        CredentialView v = credentials.useKeystore(integration, new KeystoreRef(file.toString(), KEYSTORE_PASSWORD,
                "integration-client"), null, "it-admin");
        assertEquals(CredentialSource.KEYSTORE, v.source());
        assertEquals(file.toString(), v.keystorePath());
        assertEquals(fingerprint(good), v.info().sha256());
        StoredCredential row = store.rows().get(0);
        assertNull(row.sealed(), "nothing copied");
        if (!intercepted) {
            assertEquals(Outcome.SENT, engine.attempt(integration, Fx.delivery(1)).outcome());
        }
        // The customer renews the certificate in the file itself: the next use takes it and records it.
        TestCertificates.keystore(next, file, "integration-client", "ks-pass-1");
        if (!intercepted) {
            assertEquals(Outcome.SENT, engine.attempt(integration, Fx.delivery(1)).outcome());
        } else {
            engine.attempt(integration, Fx.delivery(1));
        }
        assertEquals(fingerprint(next), credentials.current(integration.id()).orElseThrow().info().sha256());
        assertEquals(CredentialChange.Kind.KEYSTORE_RENEWED, credentials.history(integration.id()).get(1).kind());

        // Refusals: wrong password, unknown alias, no file, no password in the secret store.
        StoredIntegration s2 = Fx.stored(config(SignIn.MutualTls.alone()));
        secrets.put(SecretRef.of("int/wrong"), "wrong");
        assertEquals(IntegrationCode.INT_CREDENTIAL_PASSWORD_WRONG, assertThrows(IntegrationCredentials.Refused.class,
                () -> credentials.useKeystore(s2, new KeystoreRef(file.toString(), SecretRef.of("int/wrong"),
                        "integration-client"), null, "a")).code());
        assertEquals(IntegrationCode.INT_CREDENTIAL_ALIAS_NOT_FOUND, assertThrows(IntegrationCredentials.Refused.class,
                () -> credentials.useKeystore(s2, new KeystoreRef(file.toString(), KEYSTORE_PASSWORD, "nope"), null,
                        "a")).code());
        assertEquals(IntegrationCode.INT_CREDENTIAL_KEYSTORE_UNREADABLE, assertThrows(
                IntegrationCredentials.Refused.class, () -> credentials.useKeystore(s2, new KeystoreRef(
                        dir.resolve("missing.p12").toString(), KEYSTORE_PASSWORD, "x"), null, "a")).code());
        assertEquals(IntegrationCode.INT_SECRET_NOT_FOUND, assertThrows(IntegrationCredentials.Refused.class,
                () -> credentials.useKeystore(s2, new KeystoreRef(file.toString(), SecretRef.of("int/none"),
                        "integration-client"), null, "a")).code());
    }

    @Test
    void uploadsAreCheckedAndSealed() throws Exception {
        // Not a certificate file; the wrong password; a key that is not the certificate's; an encrypted PEM key.
        assertEquals(IntegrationCode.INT_CREDENTIAL_FILE_INVALID, refusal("not a certificate".getBytes(), null));
        assertEquals(IntegrationCode.INT_CREDENTIAL_PASSWORD_WRONG,
                refusal(TestCertificates.pkcs12(good, P12_PASSWORD), "wrong".toCharArray()));
        String mismatch = TestCertificates.pem("CERTIFICATE", good.certificate().getEncoded())
                + TestCertificates.pem("PRIVATE KEY", other.key().getEncoded());
        assertEquals(IntegrationCode.INT_CREDENTIAL_KEY_MISMATCH, refusal(mismatch.getBytes(StandardCharsets.US_ASCII),
                null));
        assertEquals(IntegrationCode.INT_CREDENTIAL_PASSWORD_WRONG, refusal(("-----BEGIN ENCRYPTED PRIVATE KEY-----\n"
                + "AAAA\n-----END ENCRYPTED PRIVATE KEY-----\n").getBytes(StandardCharsets.US_ASCII), null));
        assertEquals(IntegrationCode.INT_CREDENTIAL_FILE_INVALID, refusal(new byte[40_000], null));
        // A PEM file with the certificate and its key works.
        CredentialView v = credentials.upload(integration, good.pem().getBytes(StandardCharsets.US_ASCII), null, null,
                "it-admin");
        assertEquals(fingerprint(good), v.info().sha256());
        // Stored sealed under the customer's keys; the key appears nowhere.
        StoredCredential row = store.rows().get(0);
        assertTrue(row.sealed().startsWith(ValueCipher.PREFIX));
        assertEquals(CUSTOMER, ValueCipher.customerOf(row.sealed()));
        String key = good.keyBase64().substring(0, 40);
        for (String text : List.of(row.sealed(), row.toString(), v.toString(), credentials.list(integration.id())
                .toString(), credentials.history(integration.id()).toString())) {
            assertFalse(text.contains(key), text);
        }
        // Without a key service: refused at upload; the key service down at a call: can't connect, nothing sent.
        IntegrationCredentials noKeys = new IntegrationCredentials(new InMemoryCredentialStore(), null, e -> CUSTOMER,
                secrets, IntegrationAudit.NONE, clock);
        assertEquals(IntegrationCode.INT_CREDENTIAL_KEY_SERVICE_FAILED, assertThrows(
                IntegrationCredentials.Refused.class, () -> noKeys.upload(integration, good.pem().getBytes(), null,
                        null, "a")).code());
        keys.down(true);
        AttemptResult r = engine.attempt(integration, Fx.delivery(1));
        assertEquals(Outcome.CANT_CONNECT, r.outcome());
        assertEquals(IntegrationCode.INT_CREDENTIAL_KEY_SERVICE_FAILED, r.code());
        keys.down(false);
        assertTrue(mock.received().isEmpty());
    }

    private IntegrationCode refusal(byte[] file, char[] password) {
        return assertThrows(IntegrationCredentials.Refused.class,
                () -> credentials.upload(integration, file, password, null, "it-admin")).code();
    }

    // ---- Together with OAuth 2.0 ----

    @Test
    void withOAuthTheTokenRequestCanPresentTheCertificate() throws Exception {
        upload(good);
        mock.expectOAuth2().registerClient("app", "client-secret");
        secrets.put(SecretRef.of("int/client"), "client-secret");
        SignIn.OAuth2ClientCredentials oauth = SignIn.OAuth2ClientCredentials.secret(mock.tokenAddressByName(), "app",
                SecretRef.of("int/client"), null);
        // The mock requires the certificate on every connection, also the token request's.
        StoredIntegration presenting = Fx.stored(config(new SignIn.MutualTls(oauth, true)));
        StoredIntegration notPresenting = Fx.stored(config(new SignIn.MutualTls(oauth, false)));
        for (StoredIntegration s : List.of(presenting, notPresenting)) { // each integration has its own certificate
            credentials.upload(s, TestCertificates.pkcs12(good, P12_PASSWORD), P12_PASSWORD.toCharArray(), null, "a");
        }
        assumeFalse(intercepted, "TLS to 127.0.0.1 is intercepted on this machine");
        // Without the certificate first: once a token is held, no token request is made at all.
        AttemptResult without = engine.attempt(notPresenting, Fx.delivery(1));
        assertEquals(Outcome.CANT_CONNECT, without.outcome(), without.toString());
        assertEquals(0, mock.tokensIssued(), "the token address took no request without the certificate");
        assertTrue(mock.calls().isEmpty());
        AttemptResult with = engine.attempt(presenting, Fx.delivery(1));
        assertEquals(Outcome.SENT, with.outcome(), with.toString());
    }

    @Test
    void switchingTheCertificateDropsTheHeldTokenAtOnce() throws Exception {
        mock.expectOAuth2().registerClient("app", "client-secret");
        secrets.put(SecretRef.of("int/client"), "client-secret");
        SignIn.OAuth2ClientCredentials oauth = SignIn.OAuth2ClientCredentials.secret(mock.tokenAddressByName(), "app",
                SecretRef.of("int/client"), null);
        StoredIntegration s = Fx.stored(config(new SignIn.MutualTls(oauth, true)));
        credentials.upload(s, TestCertificates.pkcs12(good, P12_PASSWORD), P12_PASSWORD.toCharArray(), null, "a");
        credentials.upload(s, TestCertificates.pkcs12(next, P12_PASSWORD), P12_PASSWORD.toCharArray(), null, "a");
        assumeFalse(intercepted, "TLS to 127.0.0.1 is intercepted on this machine");
        assertEquals(Outcome.SENT, engine.attempt(s, Fx.delivery(1)).outcome());
        assertEquals(Outcome.SENT, engine.attempt(s, Fx.delivery(1)).outcome());
        assertEquals(1, mock.tokensIssued(), "the token is held");
        credentials.switchToNext(s.id(), "it-admin");
        assertEquals(Outcome.SENT, engine.attempt(s, Fx.delivery(1)).outcome());
        assertEquals(2, mock.tokensIssued(), "the switch dropped it; the new token came with the new certificate");
    }

    // ---- Expiry warnings ----

    @Test
    void expiryWarningsAt30And7And1DaysAndAfter() throws Exception {
        upload(good);
        Instant end = good.certificate().getNotAfter().toInstant();
        CredentialExpiry expiry = new CredentialExpiry(store);
        assertTrue(expiry.check(end.minus(Duration.ofDays(31))).isEmpty());
        assertEquals(30, expiry.check(end.minus(Duration.ofDays(30))).get(0).threshold());
        assertEquals(30, expiry.check(end.minus(Duration.ofDays(8))).get(0).threshold());
        assertEquals(7, expiry.check(end.minus(Duration.ofDays(7))).get(0).threshold());
        CredentialExpiry.Warning last = expiry.check(end.minus(Duration.ofHours(5))).get(0);
        assertEquals(1, last.threshold());
        assertEquals(0, last.daysLeft());
        assertFalse(last.expired());
        CredentialExpiry.Warning gone = expiry.check(end.plusSeconds(5)).get(0);
        assertTrue(gone.expired());
        assertEquals(CredentialState.CURRENT, gone.state());
        assertEquals(integration.id(), gone.integrationId());
        // The installation's own thresholds.
        assertEquals(14, new CredentialExpiry(store, List.of(14, 3)).check(end.minus(Duration.ofDays(10))).get(0)
                .threshold());
        assertThrows(IllegalArgumentException.class, () -> new CredentialExpiry(store, List.of(0)));
    }

    // ---- Configuration ----

    @Test
    void theConfiguration() {
        Installation inst = installation();
        SignIn.OAuth2ClientCredentials oauth = SignIn.OAuth2ClientCredentials.secret(
                java.net.URI.create("https://login.example/token"), "app", SecretRef.of("int/client"), "s");
        IntegrationConfig c = config(new SignIn.MutualTls(oauth, true));
        assertTrue(ConfigCheck.check(c, inst).ok(), ConfigCheck.check(c, inst).errors().toString());
        assertEquals(c, ConfigJson.read(ConfigJson.write(c)));
        assertEquals(SignIn.MutualTls.alone(), ConfigJson.read(ConfigJson.write(config(SignIn.MutualTls.alone())))
                .signIn());
        // Over plain http a certificate means nothing.
        IntegrationConfig plain = c.withEndpoint(new Endpoint(java.net.URI.create("http://127.0.0.1:1"), true));
        assertTrue(ConfigCheck.check(plain, inst).errors().stream()
                .anyMatch(p -> p.code() == IntegrationCode.INT_CONFIG_MUTUAL_TLS_INVALID));
        assertThrows(IllegalArgumentException.class, () -> new SignIn.MutualTls(new SignIn.None(), false));
        assertThrows(IllegalArgumentException.class, () -> new SignIn.MutualTls(SignIn.MutualTls.alone(), false));
        assertThrows(IllegalArgumentException.class, () -> new SignIn.MutualTls(null, true));
    }

    /** The leaf certificate a TLS client actually sees on 127.0.0.1:port (test only: accepts anything). */
    private static X509Certificate peerCertificate(int port) throws Exception {
        AtomicReference<X509Certificate> seen = new AtomicReference<>();
        SSLContext any = SSLContext.getInstance("TLS");
        any.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {
            }

            public void checkServerTrusted(X509Certificate[] c, String a) {
                seen.set(c[0]);
            }

            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, null);
        try (SSLSocket s = (SSLSocket) any.getSocketFactory().createSocket("127.0.0.1", port)) {
            s.startHandshake();
        } catch (java.io.IOException noClientCertificate) {
            // The server requires a client certificate; the server's own certificate was seen before that.
        }
        return seen.get();
    }
}
