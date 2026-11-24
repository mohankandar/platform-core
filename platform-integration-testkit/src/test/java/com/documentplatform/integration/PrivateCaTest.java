package com.documentplatform.integration;

import com.documentplatform.integration.config.Endpoint;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.result.AttemptResult;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.outbound.Installation;
import com.documentplatform.outbound.ProxySettings;
import com.documentplatform.outbound.TrustBundle;
import com.documentplatform.integration.testkit.MockDestination;
import com.documentplatform.integration.testkit.MockProxy;
import com.documentplatform.integration.testkit.TestCertificates;
import com.documentplatform.notifications.secret.SecretRef;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The private certificate authority, added to the system's: the trust itself without the network, then end to end
 * against an https mock. The end-to-end tests that need a TLS connection to succeed are skipped when something on the
 * machine intercepts TLS to 127.0.0.1 (for example an antivirus that re-signs certificates); the trust checks still
 * run.
 */
class PrivateCaTest {

    @TempDir
    static Path dir;
    static TestCertificates.Ca ca;
    static TestCertificates.Ca otherCa;
    static TestCertificates.Server server;
    static MockDestination mock;
    static boolean intercepted;

    @BeforeAll
    static void start() throws Exception {
        ca = TestCertificates.ca(dir, "test-ca");
        otherCa = TestCertificates.ca(dir, "other-ca");
        // Issued to the name localhost only, so 127.0.0.1 does not match it.
        server = TestCertificates.server(dir, ca, "mock-server", "dns:localhost");
        mock = MockDestination.startHttps(server.context());
        intercepted = !server.certificate().equals(peerCertificate(mock.port()));
        if (intercepted) {
            System.err.println("PrivateCaTest: TLS to 127.0.0.1 is intercepted on this machine; the end-to-end TLS "
                    + "tests are skipped, the trust checks still run.");
        }
    }

    @AfterAll
    static void stop() {
        mock.close();
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
        }
        return seen.get();
    }

    // ---- The trust, without the network ----

    @Test
    void thePrivateCaIsAddedToTheSystemCas() throws Exception {
        X509Certificate[] chain = {server.certificate(), ca.certificate()};
        X509TrustManager system = TrustBundle.trustManager(List.of());
        X509TrustManager withCa = TrustBundle.trustManager(List.of(ca.certificate()));
        X509TrustManager withOther = TrustBundle.trustManager(List.of(otherCa.certificate()));
        assertThrows(CertificateException.class, () -> system.checkServerTrusted(chain, "RSA"));
        withCa.checkServerTrusted(chain, "RSA");
        assertThrows(CertificateException.class, () -> withOther.checkServerTrusted(chain, "RSA"));
        assertEquals(system.getAcceptedIssuers().length + 1, withCa.getAcceptedIssuers().length,
                "added, never replacing the system's");
    }

    @Test
    void aBundleThatIsNotACertificateIsRefused() {
        Installation onPrem = Installation.onPremises();
        assertThrows(IllegalArgumentException.class, () -> onPrem.withPrivateCa("not a certificate"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> onPrem.withPrivateCa("-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----"));
        assertFalse(e.getMessage().contains("AAAA"));
        assertEquals(2, onPrem.withPrivateCa(ca.pem() + otherCa.pem()).privateCaCertificates().size());
    }

    // ---- End to end ----

    private Fx fx(Installation inst) {
        Fx fx = new Fx(mock, inst);
        mock.reset();
        fx.secrets.put(SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/t"), "tok-1");
        mock.expectBearer("tok-1");
        return fx;
    }

    private static IntegrationConfig config(boolean byName) {
        return Fx.twoStep(byName ? mock.addressByName() : mock.address(),
                new SignIn.BearerFixed(SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/t")));
    }

    private static Installation onPrem() {
        return Installation.onPremises().withInternalAddresses(true);
    }

    @Test
    void untrustedWithoutThePrivateCa() {
        Fx fx = fx(onPrem());
        AttemptResult r = fx.engine.attempt(Fx.stored(config(true)), Fx.delivery(1));
        assertEquals(Outcome.CANT_CONNECT, r.outcome());
        assertEquals(IntegrationCode.INT_TLS_FAILED, r.code());
        assertTrue(mock.received().isEmpty());
        fx.engine.close();
    }

    @Test
    void trustedWithThePrivateCa() {
        assumeFalse(intercepted, "TLS to 127.0.0.1 is intercepted on this machine");
        Fx fx = fx(onPrem().withPrivateCa(ca.pem()));
        AttemptResult r = fx.engine.attempt(Fx.stored(config(true)), Fx.delivery(1));
        assertEquals(Outcome.SENT, r.outcome(), r.toString());
        fx.engine.close();
        Fx wrong = fx(onPrem().withPrivateCa(otherCa.pem()));
        assertEquals(IntegrationCode.INT_TLS_FAILED, wrong.engine.attempt(Fx.stored(config(true)), Fx.delivery(1))
                .code());
        wrong.engine.close();
    }

    @Test
    void theHostNameIsAlwaysChecked() {
        assumeFalse(intercepted, "TLS to 127.0.0.1 is intercepted on this machine");
        Fx fx = fx(onPrem().withPrivateCa(ca.pem()));
        AttemptResult r = fx.engine.attempt(Fx.stored(config(false)), Fx.delivery(1));
        assertEquals(Outcome.CANT_CONNECT, r.outcome());
        assertEquals(IntegrationCode.INT_TLS_FAILED, r.code());
        fx.engine.close();
    }

    @Test
    void throughTheProxyWithThePrivateCa() {
        assumeFalse(intercepted, "TLS to 127.0.0.1 is intercepted on this machine");
        try (MockProxy proxy = MockProxy.start()) {
            Fx fx = fx(onPrem().withPrivateCa(ca.pem()).withProxy(ProxySettings.of(proxy.host(), proxy.port())));
            IntegrationConfig c = config(true).withEndpoint(new Endpoint(mock.addressByName(), true));
            assertEquals(Outcome.SENT, fx.engine.attempt(Fx.stored(c), Fx.delivery(1)).outcome());
            assertTrue(proxy.targets().stream().allMatch(t -> t.equals("localhost:" + mock.port())));
            fx.engine.close();
        }
    }
}
