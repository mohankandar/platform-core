package com.documentplatform.outbound;

import com.documentplatform.notifications.inmemory.InMemorySecretResolver;
import com.documentplatform.notifications.secret.SecretRef;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P028 B4: the outbound rules in their own module (the integrations' tests in platform-integration-testkit run the
 * same rules through the call engine, with the mock destination, proxy and certificates).
 */
class OutboundRulesTest {

    static final Duration T = Duration.ofSeconds(5);
    static final String ANSWER_BODY = "ANSWER-BODY-planted-3c3c";
    static final String ANSWER_HEADER = "ANSWER-HEADER-planted-4d4d";
    HttpServer server;
    URI base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/ok", x -> {
            byte[] b = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, b.length);
            x.getResponseBody().write(b);
            x.close();
        });
        server.createContext("/redirect", x -> {
            x.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data");
            x.sendResponseHeaders(302, -1);
            x.close();
        });
        server.createContext("/large", x -> {
            byte[] b = new byte[4096];
            x.sendResponseHeaders(200, b.length);
            x.getResponseBody().write(b);
            x.close();
        });
        server.createContext("/echo", x -> {
            byte[] b = ("{\"text\":\"" + ANSWER_BODY + "\"}").getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("X-Session", ANSWER_HEADER);
            x.sendResponseHeaders(200, b.length);
            x.getResponseBody().write(b);
            x.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static Outbound.Call get(URI uri) {
        return new Outbound.Call("GET", uri, List.of(), null, T);
    }

    private static OutboundCode refused(Outbound o, Outbound.Call call) {
        return assertThrows(Outbound.Failure.class, () -> o.send(call, T, 1024, false)).code();
    }

    @Test
    void aCallAndAnAnswerNeverShowHeadersBodiesOrTheAddress() throws Exception {
        // xc-never-leak-64: toString holds the method and the status only.
        String bearer = "BEARER-planted-1a1a";
        String body = "BODY-planted-2b2b";
        String query = "QUERY-planted-5e5e";
        Outbound.Call call = new Outbound.Call("POST", base.resolve("/echo?k=" + query),
                List.of(Map.entry("Authorization", "Bearer " + bearer), Map.entry("X-Note", bearer)),
                HttpRequest.BodyPublishers.ofString(body), T);
        try (Outbound o = new Outbound(Installation.onPremises().withInternalAddresses(true))) {
            Outbound.Answer a = o.send(call, T, 1024, false);
            assertEquals(ANSWER_HEADER, a.header("X-Session").orElseThrow(), "the answer has them");
            assertTrue(new String(a.body(), StandardCharsets.UTF_8).contains(ANSWER_BODY));
            for (String text : List.of(call.toString(), a.toString())) {
                for (String planted : List.of(bearer, body, query, ANSWER_BODY, ANSWER_HEADER, "/echo", "127.0.0.1")) {
                    assertFalse(text.contains(planted), "found " + planted + " in: " + text);
                }
            }
        }
    }

    @Test
    void internalAddressesAndPlainHttpOnlyWhereAllowed() throws Exception {
        try (Outbound strict = new Outbound(Installation.onPremises());
             Outbound internal = new Outbound(Installation.onPremises().withInternalAddresses(true))) {
            assertEquals(OutboundCode.OUT_ADDRESS_NOT_SECURE, refused(strict, get(base.resolve("/ok"))));
            assertEquals(OutboundCode.OUT_ADDRESS_BLOCKED,
                    refused(strict, get(URI.create("https://127.0.0.1:" + server.getAddress().getPort()))));
            Outbound.Answer a = internal.send(get(base.resolve("/ok")), T, 1024, false);
            assertEquals(200, a.status());
            assertEquals("{\"ok\":true}", new String(a.body(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void cloudMetadataIsAlwaysRefused() {
        try (Outbound internal = new Outbound(Installation.onPremises().withInternalAddresses(true))) {
            assertEquals(OutboundCode.OUT_ADDRESS_BLOCKED,
                    refused(internal, get(URI.create("http://169.254.169.254/latest/meta-data"))));
            assertEquals(OutboundCode.OUT_ADDRESS_BLOCKED,
                    refused(internal, get(URI.create("https://metadata.google.internal/"))));
        }
        AddressGuard guard = new AddressGuard(true);
        assertEquals(OutboundCode.OUT_ADDRESS_BLOCKED, guard.check("169.254.169.254", false).orElseThrow());
        assertEquals(OutboundCode.OUT_ADDRESS_BLOCKED, guard.check("[fd00:ec2::254]", false).orElseThrow());
        assertTrue(guard.check("127.0.0.1", false).isEmpty());
        assertEquals(OutboundCode.OUT_ADDRESS_BLOCKED, new AddressGuard(false).check("10.1.2.3", false).orElseThrow());
    }

    @Test
    void aNameThatAlsoPointsInsideIsRefusedAndTheConnectionGoesToTheCheckedAddress() {
        AddressGuard twoFaced = new AddressGuard(false, host -> new InetAddress[]{
                InetAddress.getByAddress(host, new byte[]{93, (byte) 184, (byte) 216, 34}),
                InetAddress.getByAddress(host, new byte[]{10, 0, 0, 1})});
        assertEquals(OutboundCode.OUT_ADDRESS_BLOCKED, twoFaced.check("api.example", false).orElseThrow());
        AddressGuard unknown = new AddressGuard(false, host -> {
            throw new UnknownHostException();
        });
        assertEquals(OutboundCode.OUT_HOST_UNKNOWN, unknown.check("nowhere.example", false).orElseThrow());
        AddressGuard.Checked ok = new AddressGuard(true).checked("127.0.0.1", false);
        assertTrue(ok.refused().isEmpty());
        assertEquals(List.of(InetAddress.getLoopbackAddress()), ok.addresses());
    }

    @Test
    void redirectsAreNeverFollowedAndLargeAnswersAreNotRead() throws Exception {
        try (Outbound o = new Outbound(Installation.onPremises().withInternalAddresses(true))) {
            Outbound.Answer r = o.send(get(base.resolve("/redirect")), T, 1024, false);
            assertEquals(302, r.status());
            Outbound.Answer large = o.send(get(base.resolve("/large")), T, 1024, false);
            assertTrue(large.tooLarge());
            assertEquals(0, large.body().length);
        }
    }

    @Test
    void headersTheHttpLayerSetsAndControlCharactersAreRefused() {
        try (Outbound o = new Outbound(Installation.onPremises().withInternalAddresses(true))) {
            assertEquals(OutboundCode.OUT_VALUE_NOT_ALLOWED, refused(o, new Outbound.Call("GET", base.resolve("/ok"),
                    List.of(Map.entry("Host", "elsewhere")), null, T)));
            assertEquals(OutboundCode.OUT_VALUE_NOT_ALLOWED, refused(o, new Outbound.Call("POST", base.resolve("/ok"),
                    List.of(Map.entry("X-Note", "a\r\nInjected: yes")), HttpRequest.BodyPublishers.ofString("x"), T)));
            assertEquals(OutboundCode.OUT_CANT_CONNECT, refused(o, get(URI.create("ftp://127.0.0.1/x"))));
            assertEquals(OutboundCode.OUT_CANT_CONNECT,
                    refused(o, get(URI.create("http://user:pw@127.0.0.1:" + server.getAddress().getPort() + "/ok"))));
        }
    }

    @Test
    void aFailureNeverHoldsTheAddressAndCodesExplainThemselves() {
        try (Outbound o = new Outbound(Installation.onPremises())) {
            Outbound.Failure f = assertThrows(Outbound.Failure.class,
                    () -> o.send(get(URI.create("http://secret-host.example/path?token=abc")), T, 1024, false));
            assertFalse(f.getMessage().contains("secret-host"), f.getMessage());
            assertFalse(f.getMessage().contains("abc"), f.getMessage());
        }
        assertTrue(OutboundCode.OUT_PROXY_SIGN_IN_REFUSED.hasMessage());
        assertTrue(OutboundCode.OUT_PROXY_SIGN_IN_REFUSED.message().contains("platform.outbound.proxy.user"));
        assertEquals("OUT_TIMEOUT", OutboundCode.OUT_TIMEOUT.message());
        for (OutboundCode c : OutboundCode.values()) assertTrue(c.name().startsWith("OUT_"), c.name());
    }

    @Test
    void installationRules() {
        assertThrows(IllegalArgumentException.class, () -> new Installation(true, true, false, null, null));
        assertThrows(IllegalArgumentException.class, () -> new Installation(true, false, true, null, null));
        assertThrows(IllegalArgumentException.class, () -> Installation.onPremises().withPrivateCa("not a pem"));
        Installation withProxy = Installation.onPremises().withProxy(ProxySettings.of("proxy.corp.example", 3128)
                .withSignIn("svc", SecretRef.of("outbound/proxy")));
        assertThrows(IllegalArgumentException.class, () -> new Outbound(withProxy),
                "a proxy with sign-in needs the secret resolver");
        new Outbound(withProxy, new InMemorySecretResolver()).close();
        assertFalse(withProxy.toString().contains("outbound/proxy"), withProxy.toString());
        ProxySettings bypass = new ProxySettings("proxy.corp.example", 3128, List.of(".corp.example", "files01"));
        assertTrue(bypass.bypasses("api.corp.example"));
        assertTrue(bypass.bypasses("files01"));
        assertFalse(bypass.bypasses("api.example"));
    }

    @Test
    void retryAfterInSecondsOrAsADate() {
        Instant now = Instant.parse("2026-11-22T10:00:00Z");
        assertEquals(Duration.ofSeconds(120), RetryAfter.parse("120", now));
        assertEquals(Duration.ofSeconds(30), RetryAfter.parse("Sun, 22 Nov 2026 10:00:30 GMT", now));
        assertNull(RetryAfter.parse("soon", now));
        assertNull(RetryAfter.parse(null, now));
    }
}
