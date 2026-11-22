package com.documentplatform.integration;

import com.documentplatform.identity.signin.OidcTestSignIn;
import com.documentplatform.identity.signin.TestReport;
import com.documentplatform.identity.signin.TestSignIn;
import com.documentplatform.identity.testkit.MockIssuer;
import com.documentplatform.integration.http.Outbound;
import com.documentplatform.integration.http.OutboundIssuerFetch;
import com.documentplatform.integration.safety.Installation;
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
 * successful handshake needs TLS to 127.0.0.1 unchanged; where it is intercepted that check is skipped, as in
 * {@link PrivateCaTest} (CI forbids skips).
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
        assertEquals(IntegrationCode.INT_TLS_FAILED.name(), r.reason());
    }

    @Test
    void theTestSignInPassesWithThePrivateCa() {
        assumeFalse(intercepted, "TLS to 127.0.0.1 is intercepted on this machine");
        TestReport r = signIn(Installation.onPremises().withInternalAddresses(true).withPrivateCa(ca.pem()));
        assertTrue(r.passed(), r.toString());
        assertTrue(r.tokenChecked());
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
