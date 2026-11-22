package com.documentplatform.integration;

import com.documentplatform.integration.testkit.MockProxy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The test proxy's plain-http forwarding (the cause of the intermittent {@code ProxyTest} failure in P031): the client
 * must never reuse a connection the proxy closes after one request, and every request body must arrive whole.
 */
class MockProxyTest {

    private HttpServer upstream;
    private MockProxy proxy;

    @BeforeEach
    void start() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        // Answers with the number of body bytes it read; the JDK server sends no "Connection: close" of its own.
        upstream.createContext("/", ex -> {
            byte[] body;
            try (InputStream in = ex.getRequestBody()) {
                body = in.readAllBytes();
            }
            byte[] answer = Integer.toString(body.length).getBytes(StandardCharsets.US_ASCII);
            ex.sendResponseHeaders(200, answer.length);
            ex.getResponseBody().write(answer);
            ex.close();
        });
        upstream.start();
        proxy = MockProxy.start();
    }

    @AfterEach
    void stop() {
        proxy.close();
        upstream.stop(0);
    }

    private String target() {
        return "http://localhost:" + upstream.getAddress().getPort() + "/upload";
    }

    @Test
    void theAnswerTellsTheClientTheConnectionCloses() throws Exception {
        try (Socket s = new Socket(proxy.host(), proxy.port())) {
            s.getOutputStream().write(("POST " + target() + " HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\n"
                    + "Proxy-Connection: keep-alive\r\n\r\nhello").getBytes(StandardCharsets.US_ASCII));
            String answer = new String(s.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertTrue(answer.startsWith("HTTP/1.1 200"), answer);
            assertTrue(answer.toLowerCase().contains("\r\nconnection: close\r\n"), answer);
            assertTrue(answer.endsWith("\r\n\r\n5"), answer);
        }
    }

    @Test
    void manyRequestsInARowThroughOneClientAllArriveWhole() throws Exception {
        byte[] body = new byte[64 * 1024];
        Arrays.fill(body, (byte) 'x');
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .proxy(ProxySelector.of(new InetSocketAddress(proxy.host(), proxy.port()))).build()) {
            for (int i = 0; i < 300; i++) {
                HttpRequest r = HttpRequest.newBuilder(URI.create(target()))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
                HttpResponse<String> a = client.send(r, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, a.statusCode(), "request " + i);
                assertEquals(Integer.toString(body.length), a.body(), "request " + i);
            }
        }
        assertEquals(300, proxy.targets().size());
    }

    @Test
    void aChunkedBodyIsRelayedWhole() throws Exception {
        try (Socket s = new Socket(proxy.host(), proxy.port())) {
            s.getOutputStream().write(("POST " + target() + " HTTP/1.1\r\nHost: localhost\r\n"
                    + "Transfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n4;x=y\r\ndefg\r\n0\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            String answer = new String(s.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertTrue(answer.endsWith("\r\n\r\n7"), answer);
        }
    }

    @Test
    void basicSignInIsCheckedAndNeverForwarded() throws Exception {
        proxy.requireBasic("proxy-user", "right");
        String good = java.util.Base64.getEncoder().encodeToString("proxy-user:right".getBytes(StandardCharsets.UTF_8));
        String bad = java.util.Base64.getEncoder().encodeToString("proxy-user:wrong".getBytes(StandardCharsets.UTF_8));
        assertTrue(send("Proxy-Authorization: Basic " + good + "\r\n").startsWith("HTTP/1.1 200"));
        String refused = send("Proxy-Authorization: Basic " + bad + "\r\n");
        assertTrue(refused.startsWith("HTTP/1.1 407"), refused);
        assertTrue(refused.contains("Proxy-Authenticate: Basic"), refused);
        assertTrue(send("").startsWith("HTTP/1.1 407"));
        assertEquals(1, proxy.signInsAccepted());
        assertEquals(2, proxy.signInsRefused());
        proxy.requireBasic(null, null);
        assertTrue(send("").startsWith("HTTP/1.1 200"));
    }

    private String send(String extraHeader) throws Exception {
        try (Socket s = new Socket(proxy.host(), proxy.port())) {
            s.getOutputStream().write(("GET " + target() + " HTTP/1.1\r\nHost: localhost\r\n" + extraHeader + "\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            return new String(s.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
