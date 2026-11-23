package com.documentplatform.integration;

import com.documentplatform.integration.config.Endpoint;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.engine.CallEngine;
import com.documentplatform.outbound.Outbound;
import com.documentplatform.integration.result.AttemptResult;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.outbound.Installation;
import com.documentplatform.outbound.ProxySettings;
import com.documentplatform.integration.testkit.MockDestination;
import com.documentplatform.integration.testkit.MockProxy;
import com.documentplatform.integration.testkit.TestCertificates;
import com.documentplatform.notifications.inmemory.InMemorySecretResolver;
import com.documentplatform.notifications.secret.SecretRef;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * B2 for https: the proxy's Basic sign-in on CONNECT, right, wrong and missing, in a JVM with the JDK's defaults
 * (since P026 the HTTP client sends the sign-in on CONNECT itself; no start option is needed). The full https call
 * needs TLS to 127.0.0.1 to reach the mock unchanged; where something on the machine intercepts it (an antivirus
 * re-signing certificates), that one check is skipped as in {@link PrivateCaTest}; CI forbids skips.
 */
class ProxySignInTunnelTest {

    static final SecretRef PROXY_PASSWORD = SecretRef.of("int/proxy-password");

    @TempDir
    static Path dir;
    static TestCertificates.Ca ca;
    static MockDestination mock;
    static boolean intercepted;
    private MockProxy proxy;
    private final InMemorySecretResolver secrets = new InMemorySecretResolver();

    @BeforeAll
    static void startTls() throws Exception {
        ca = TestCertificates.ca(dir, "tunnel-ca");
        TestCertificates.Server server = TestCertificates.server(dir, ca, "tunnel-server", "dns:localhost");
        mock = MockDestination.startHttps(server.context());
        intercepted = !server.certificate().equals(peerCertificate(mock.port()));
    }

    @AfterAll
    static void stopTls() {
        mock.close();
    }

    @BeforeEach
    void start() {
        mock.reset();
        mock.expectBearer("tok-1");
        proxy = MockProxy.start().requireBasic("svc-proxy", "proxy-pass-1");
        secrets.put(SecretRef.of("int/t"), "tok-1").put(PROXY_PASSWORD, "proxy-pass-1");
    }

    @org.junit.jupiter.api.AfterEach
    void stop() {
        proxy.close();
    }

    private CallEngine engine(ProxySettings p) {
        Installation i = Installation.onPremises().withInternalAddresses(true).withPrivateCa(ca.pem()).withProxy(p);
        return new CallEngine(new Outbound(i, secrets), secrets, Clock.systemUTC());
    }

    private static IntegrationConfig config() {
        return Fx.twoStep(mock.addressByName(), new SignIn.BearerFixed(SecretRef.of("int/t")))
                .withEndpoint(new Endpoint(mock.addressByName(), true));
    }

    @Test
    void rightCredentialsOpenTheTunnel() {
        AttemptResult r;
        try (CallEngine engine = engine(ProxySettings.of(proxy.host(), proxy.port())
                .withSignIn("svc-proxy", PROXY_PASSWORD))) {
            r = engine.attempt(Fx.stored(config()), Fx.delivery(1));
        }
        assertTrue(proxy.signInsAccepted() >= 1, "the CONNECT carried the right sign-in");
        assertEquals(0, proxy.signInsRefused());
        assertTrue(proxy.targets().stream().allMatch(t -> t.equals("localhost:" + mock.port())));
        assumeFalse(intercepted, "TLS to 127.0.0.1 is intercepted on this machine");
        assertEquals(Outcome.SENT, r.outcome(), r.toString());
        assertTrue(mock.received().stream().allMatch(x -> x.header("proxy-authorization").isEmpty()),
                "the proxy sign-in never goes into the tunnel");
    }

    @Test
    void wrongCredentialsAreRefused() {
        secrets.put(PROXY_PASSWORD, "wrong");
        try (CallEngine engine = engine(ProxySettings.of(proxy.host(), proxy.port())
                .withSignIn("svc-proxy", PROXY_PASSWORD))) {
            AttemptResult r = engine.attempt(Fx.stored(config()), Fx.delivery(1));
            assertEquals(Outcome.CANT_CONNECT, r.outcome(), r.toString());
            assertEquals(IntegrationCode.INT_PROXY_SIGN_IN_REFUSED, r.code());
        }
        assertEquals(1, proxy.signInsRefused());
        assertTrue(mock.received().isEmpty());
    }

    @Test
    void missingCredentialsAreRefused() {
        try (CallEngine engine = engine(ProxySettings.of(proxy.host(), proxy.port()))) {
            AttemptResult r = engine.attempt(Fx.stored(config()), Fx.delivery(1));
            assertEquals(Outcome.CANT_CONNECT, r.outcome(), r.toString());
            assertEquals(IntegrationCode.INT_PROXY_SIGN_IN_REFUSED, r.code());
        }
        assertEquals(1, proxy.signInsRefused());
        assertTrue(mock.received().isEmpty());
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
}
