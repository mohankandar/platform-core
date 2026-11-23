package com.documentplatform.identity.spring;

import com.documentplatform.identity.signin.OidcTestSignIn;
import com.documentplatform.identity.signin.TestReport;
import com.documentplatform.identity.signin.TestSignIn;
import com.documentplatform.identity.testkit.MockIssuer;
import com.documentplatform.outbound.Outbound;
import com.documentplatform.outbound.Installation;
import com.documentplatform.integration.testkit.TestCertificates;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * P031's missing piece: the login-connection test sign-in against an https issuer (the identity testkit's
 * {@code MockIssuer.startHttps} with this testkit's certificates), over the outbound rules with the private CA. The
 * successful handshake needs TLS to 127.0.0.1 unchanged; where it is intercepted that check is skipped, as in the integration testkit's
 * PrivateCaTest (CI forbids skips).
 */
class HttpsIssuerTest {

    static final String AUD = "api://product-a";

    @TempDir
    static Path dir;
    static TestCertificates.Ca ca;
    static MockIssuer issuer;
    static boolean intercepted;

    @BeforeAll
    static void start() throws Exception {
        ca = TestCertificates.ca(dir, "issuer-ca");
        TestCertificates.Server server = TestCertificates.server(dir, ca, "issuer", "dns:localhost");
        issuer = MockIssuer.startHttps(server.context());
        intercepted = !server.certificate().equals(peerCertificate(issuer.issuer().getPort()));
    }

    @AfterAll
    static void stop() {
        issuer.close();
    }

    private static TestReport signIn(Installation installation) {
        try (Outbound outbound = new Outbound(installation)) {
            return new OidcTestSignIn(new OutboundIssuerFetch(outbound), Clock.systemUTC()).run(issuer.issuer(), AUD,
                    TestSignIn.withToken(issuer.token(AUD, List.of("readers"), Duration.ofMinutes(5))), false);
        }
    }

    @Test
    void anHttpsIssuerIsAnOrdinaryIssuer() {
        assertEquals("https", issuer.issuer().getScheme());
        assertEquals("localhost", issuer.issuer().getHost());
    }

    @Test
    void untrustedWithoutThePrivateCa() {
        TestReport r = signIn(Installation.onPremises().withInternalAddresses(true));
        assertFalse(r.passed());
        assertEquals(TestReport.Code.IDN_TEST_ISSUER_UNREACHABLE, r.failure());
        assertEquals("OUT_TLS_FAILED", r.reason());
    }

    @Test
    void theTestSignInPassesWithThePrivateCa() {
        assumeFalse(intercepted, "TLS to 127.0.0.1 is intercepted on this machine");
        TestReport r = signIn(Installation.onPremises().withInternalAddresses(true).withPrivateCa(ca.pem()));
        assertTrue(r.passed(), r.toString());
        assertTrue(r.tokenChecked());
    }

    /**
     * P028 B4: an identity-only Spring context (no platform-integration) validates a draft against the https issuer,
     * through the Outbound bean from platform.outbound.* with the private CA file; no development licence needed.
     */
    @Test
    void anIdentityOnlyContextTestSignsInOverHttps() throws Exception {
        assumeFalse(intercepted, "TLS to 127.0.0.1 is intercepted on this machine");
        Path caFile = dir.resolve("private-ca.pem");
        java.nio.file.Files.writeString(caFile, ca.pem());
        com.documentplatform.identity.inmemory.InMemoryIdentityStore store =
                new com.documentplatform.identity.inmemory.InMemoryIdentityStore();
        store.saveCustomer("TEN-TEST-0001", "Test Customer");
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        com.documentplatform.outbound.spring.OutboundAutoConfiguration.class,
                        IdentityIssuerFetchAutoConfiguration.class, IdentityLoginConnectionStoreAutoConfiguration.class,
                        IdentityLoginConnectionsAutoConfiguration.class))
                .withClassLoader(new org.springframework.boot.test.context.FilteredClassLoader(
                        "com.documentplatform.integration"))
                .withPropertyValues("platform.outbound.installation=ON_PREMISES",
                        "platform.outbound.allow-internal-addresses=true",
                        "platform.outbound.private-ca-file=" + caFile.toAbsolutePath())
                .withBean(com.documentplatform.identity.store.LoginConnectionStore.class, () -> store)
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure(), () -> String.valueOf(ctx.getStartupFailure()));
                    var lc = ctx.getBean(com.documentplatform.identity.store.LoginConnections.class);
                    var d = lc.createDraft("TEN-TEST-0001", "PRODUCT_A", 0,
                            new com.documentplatform.identity.store.ConnectionValues("test-idp",
                                    com.documentplatform.identity.IdentityProviderType.OIDC, issuer.issuer(),
                                    "web-client", AUD, null), "it-admin");
                    var v = lc.validate(d.connection().connectionId(), 1,
                            TestSignIn.withToken(issuer.token(AUD, List.of("readers"), Duration.ofMinutes(5))),
                            "it-admin");
                    assertEquals(com.documentplatform.identity.store.ConnectionOutcome.Result.VALIDATED, v.result(),
                            v.message());
                });
    }

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
        }
        return seen.get();
    }
}
