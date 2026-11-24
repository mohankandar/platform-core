package com.documentplatform.integration;

import com.documentplatform.integration.config.Endpoint;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.config.SignIn.OAuth2ClientCredentials;
import com.documentplatform.integration.result.AttemptResult;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.outbound.Installation;
import com.documentplatform.outbound.ProxySettings;
import com.documentplatform.integration.testkit.MockDestination;
import com.documentplatform.integration.testkit.MockProxy;
import com.documentplatform.notifications.secret.SecretRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The outbound proxy: calls and token requests go through it; bypass; a refusing proxy has its own code. */
class ProxyTest {

    private MockDestination mock;
    private MockProxy proxy;

    @BeforeEach
    void start() {
        mock = MockDestination.start();
        proxy = MockProxy.start();
    }

    @AfterEach
    void stop() {
        proxy.close();
        mock.close();
    }

    private Fx fx(ProxySettings p) {
        Fx fx = new Fx(mock, Installation.onPremises().withInternalAddresses(true).withProxy(p));
        fx.secrets.put(SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/t"), "tok-1").put(SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/client"), "client-secret");
        return fx;
    }

    private IntegrationConfig config(URI base, boolean useProxy) {
        return Fx.twoStep(base, new SignIn.BearerFixed(SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/t")))
                .withEndpoint(new Endpoint(base, useProxy));
    }

    @Test
    void callsAndTokenRequestsGoThroughTheProxy() {
        Fx fx = fx(ProxySettings.of(proxy.host(), proxy.port()));
        mock.expectOAuth2().registerClient("app", "client-secret");
        IntegrationConfig c = config(mock.addressByName(), true).withSignIn(OAuth2ClientCredentials.secret(
                mock.tokenAddressByName(), "app", SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/client"), null));
        AttemptResult r = fx.engine.attempt(Fx.stored(c), Fx.delivery(2));
        assertEquals(Outcome.SENT, r.outcome(), r.toString());
        // The token request and the three calls.
        assertEquals(4, proxy.targets().size());
        assertTrue(proxy.targets().stream().allMatch(t -> t.equals("localhost:" + mock.port())));
        assertEquals(1, mock.tokensIssued());
        assertEquals(3, mock.calls().size());
        fx.engine.close();
    }

    @Test
    void aRefusingProxyHasItsOwnCode() {
        proxy.refuse(true);
        Fx fx = fx(ProxySettings.of(proxy.host(), proxy.port()));
        // https: the proxy refuses the tunnel before any TLS.
        IntegrationConfig c = config(URI.create("https://localhost:" + mock.port()), true);
        AttemptResult r = fx.engine.attempt(Fx.stored(c), Fx.delivery(1));
        assertEquals(Outcome.CANT_CONNECT, r.outcome());
        assertEquals(IntegrationCode.INT_PROXY_REFUSED, r.code());
        assertTrue(mock.received().isEmpty());
        fx.engine.close();
    }

    @Test
    void anUnreachableProxyIsCantConnect() {
        int port = proxy.port();
        proxy.close();
        Fx fx = fx(ProxySettings.of("127.0.0.1", port));
        AttemptResult r = fx.engine.attempt(Fx.stored(config(mock.addressByName(), true)), Fx.delivery(1));
        assertEquals(Outcome.CANT_CONNECT, r.outcome());
        assertTrue(mock.received().isEmpty());
        fx.engine.close();
    }

    @Test
    void bypassedHostsAndIntegrationsThatDoNotUseTheProxyGoDirect() {
        proxy.refuse(true);
        Fx bypass = fx(new ProxySettings(proxy.host(), proxy.port(), List.of("localhost")));
        assertEquals(Outcome.SENT, bypass.engine.attempt(Fx.stored(config(mock.addressByName(), true)),
                Fx.delivery(1)).outcome());
        bypass.engine.close();
        Fx suffix = fx(new ProxySettings(proxy.host(), proxy.port(), List.of(".localhost")));
        assertEquals(Outcome.SENT, suffix.engine.attempt(Fx.stored(config(mock.addressByName(), true)),
                Fx.delivery(1)).outcome());
        suffix.engine.close();
        Fx direct = fx(ProxySettings.of(proxy.host(), proxy.port()));
        assertEquals(Outcome.SENT, direct.engine.attempt(Fx.stored(config(mock.addressByName(), false)),
                Fx.delivery(1)).outcome());
        direct.engine.close();
        assertTrue(proxy.targets().isEmpty());
    }
}
