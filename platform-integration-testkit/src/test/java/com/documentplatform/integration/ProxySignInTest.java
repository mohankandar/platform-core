package com.documentplatform.integration;

import com.documentplatform.integration.config.Endpoint;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.config.SignIn.OAuth2ClientCredentials;
import com.documentplatform.integration.engine.CallEngine;
import com.documentplatform.outbound.Outbound;
import com.documentplatform.integration.result.AttemptResult;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.outbound.AddressGuard;
import com.documentplatform.outbound.Installation;
import com.documentplatform.outbound.ProxySettings;
import com.documentplatform.integration.testkit.MockDestination;
import com.documentplatform.integration.testkit.MockProxy;
import com.documentplatform.notifications.inmemory.InMemorySecretResolver;
import com.documentplatform.notifications.secret.SecretRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B2, in a JVM started without any option (the JDK's default: Basic disabled for CONNECT tunnels): plain-http calls
 * and token requests through a proxy that needs Basic work with the right credentials and get
 * {@code INT_PROXY_SIGN_IN_REFUSED} with wrong or missing ones; since P026 an https call through it needs no JVM
 * option (the HTTP client sends the sign-in on CONNECT itself). The https (CONNECT) cases are in
 * {@link ProxySignInTunnelTest}.
 */
class ProxySignInTest {

    static final SecretRef PROXY_PASSWORD = SecretRef.of("int/proxy-password");

    private MockDestination mock;
    private MockProxy proxy;
    private final InMemorySecretResolver secrets = new InMemorySecretResolver();

    @BeforeEach
    void start() {
        mock = MockDestination.start();
        proxy = MockProxy.start().requireBasic("svc-proxy", "proxy-pass-1");
        secrets.put(PROXY_PASSWORD, "proxy-pass-1").put(SecretRef.of("int/t"), "tok-1")
                .put(SecretRef.of("int/client"), "client-secret");
    }

    @AfterEach
    void stop() {
        proxy.close();
        mock.close();
    }

    private CallEngine engine(ProxySettings p) {
        return engine(p, secrets);
    }

    private static CallEngine engine(ProxySettings p, InMemorySecretResolver s) {
        Installation i = Installation.onPremises().withInternalAddresses(true).withProxy(p);
        return new CallEngine(new Outbound(i, s), s, Clock.systemUTC());
    }

    private ProxySettings withSignIn() {
        return ProxySettings.of(proxy.host(), proxy.port()).withSignIn("svc-proxy", PROXY_PASSWORD);
    }

    private IntegrationConfig config(URI base) {
        return Fx.twoStep(base, new SignIn.BearerFixed(SecretRef.of("int/t"))).withEndpoint(new Endpoint(base, true));
    }

    @Test
    void rightCredentialsGoToTheProxyOnlyAndTheCallsArrive() {
        try (CallEngine engine = engine(withSignIn())) {
            AttemptResult r = engine.attempt(Fx.stored(config(mock.addressByName())), Fx.delivery(2));
            assertEquals(Outcome.SENT, r.outcome(), r.toString());
        }
        assertEquals(3, mock.calls().size());
        assertEquals(3, proxy.signInsAccepted());
        assertEquals(0, proxy.signInsRefused());
        assertTrue(mock.received().stream().allMatch(x -> x.header("proxy-authorization").isEmpty()),
                "the proxy sign-in never reaches the destination");
    }

    @Test
    void tokenRequestsThroughTheProxySignInToo() {
        mock.expectOAuth2().registerClient("app", "client-secret");
        IntegrationConfig c = config(mock.addressByName()).withSignIn(OAuth2ClientCredentials.secret(
                mock.tokenAddressByName(), "app", SecretRef.of("int/client"), null));
        try (CallEngine engine = engine(withSignIn())) {
            assertEquals(Outcome.SENT, engine.attempt(Fx.stored(c), Fx.delivery(1)).outcome());
        }
        assertEquals(1, mock.tokensIssued());
        assertEquals(3, proxy.signInsAccepted());
    }

    @Test
    void wrongOrMissingCredentialsAreRefusedByTheProxy() {
        secrets.put(PROXY_PASSWORD, "wrong");
        try (CallEngine engine = engine(withSignIn())) {
            AttemptResult r = engine.attempt(Fx.stored(config(mock.addressByName())), Fx.delivery(1));
            assertEquals(Outcome.CANT_CONNECT, r.outcome());
            assertEquals(IntegrationCode.INT_PROXY_SIGN_IN_REFUSED, r.code());
            assertEquals(IntegrationCode.INT_PROXY_SIGN_IN_REFUSED, engine.health(Fx.stored(config(
                    mock.addressByName()))).code());
        }
        // No sign-in configured, the proxy asks for it.
        try (CallEngine engine = engine(ProxySettings.of(proxy.host(), proxy.port()))) {
            AttemptResult r = engine.attempt(Fx.stored(config(mock.addressByName())), Fx.delivery(1));
            assertEquals(IntegrationCode.INT_PROXY_SIGN_IN_REFUSED, r.code());
        }
        // The token request gets the same code (not "token unreachable").
        mock.expectOAuth2().registerClient("app", "client-secret");
        IntegrationConfig oauth = config(mock.addressByName()).withSignIn(OAuth2ClientCredentials.secret(
                mock.tokenAddressByName(), "app", SecretRef.of("int/client"), null));
        try (CallEngine engine = engine(withSignIn())) {
            AttemptResult r = engine.attempt(Fx.stored(oauth), Fx.delivery(1));
            assertEquals(Outcome.CANT_CONNECT, r.outcome());
            assertEquals(IntegrationCode.INT_PROXY_SIGN_IN_REFUSED, r.code());
        }
        assertEquals(0, proxy.signInsAccepted());
        assertTrue(mock.received().isEmpty());
    }

    @Test
    void httpsThroughAProxyWithSignInNeedsNoJvmOption() {
        // The HTTP client sends the proxy sign-in on CONNECT itself (this JVM keeps the JDK's default, which
        // disables Basic for tunnels in the JDK's own client); no start option is needed.
        try (CallEngine engine = engine(withSignIn())) {
            AttemptResult r = engine.attempt(Fx.stored(config(URI.create("https://localhost:" + mock.port()))),
                    Fx.delivery(1));
            assertNotEquals(IntegrationCode.INT_PROXY_SIGN_IN_REFUSED, r.code());
        }
        assertEquals(1, proxy.targets().size(), "the CONNECT went to the proxy");
        assertEquals(1, proxy.signInsAccepted(), "with the sign-in, at once");
        assertEquals(0, proxy.signInsRefused());
    }

    @Test
    void thePasswordComesFromTheSecretStoreAtEveryCall() {
        InMemorySecretResolver empty = new InMemorySecretResolver().put(SecretRef.of("int/t"), "tok-1");
        try (CallEngine engine = engine(withSignIn(), empty)) {
            AttemptResult r = engine.attempt(Fx.stored(config(mock.addressByName())), Fx.delivery(1));
            assertEquals(Outcome.NOT_CONFIGURED, r.outcome());
            assertEquals(IntegrationCode.INT_SECRET_NOT_FOUND, r.code());
            empty.put(PROXY_PASSWORD, "proxy-pass-1"); // put in: picked up without a restart
            assertEquals(Outcome.SENT, engine.attempt(Fx.stored(config(mock.addressByName())), Fx.delivery(1))
                    .outcome());
        }
        Installation i = Installation.onPremises().withInternalAddresses(true).withProxy(withSignIn());
        assertThrows(IllegalArgumentException.class, () -> new Outbound(i));
        assertThrows(IllegalArgumentException.class, () -> ProxySettings.of("p", 1).withSignIn("a:b", PROXY_PASSWORD));
        assertThrows(IllegalArgumentException.class, () -> new ProxySettings("p", 1, null, "user", null));
        assertFalse(withSignIn().toString().contains("int/proxy-password"));
    }
}
