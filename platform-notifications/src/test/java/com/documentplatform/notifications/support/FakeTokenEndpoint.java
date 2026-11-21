package com.documentplatform.notifications.support;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A local stand-in for the Microsoft Entra token endpoint ({@code /<directory>/oauth2/v2.0/token}). Replies with the
 * scripted replies in order, then with {@link #always}. Records each request's path and form fields.
 */
public final class FakeTokenEndpoint implements AutoCloseable {

    public record Reply(int status, String body, Map<String, String> headers) {
    }

    public record Request(String path, Map<String, String> form) {
    }

    public static Reply token(String accessToken, long expiresIn) {
        return new Reply(200, "{\"token_type\":\"Bearer\",\"expires_in\":" + expiresIn + ",\"ext_expires_in\":"
                + expiresIn + ",\"access_token\":\"" + accessToken + "\"}", Map.of());
    }

    /** An Entra error reply, with the description text Microsoft sends (which the client must not keep). */
    public static Reply error(int status, String error, long aadsts) {
        return new Reply(status, "{\"error\":\"" + error + "\",\"error_description\":\"AADSTS" + aadsts
                + ": scripted description. Trace ID: 0000 Correlation ID: 1111\",\"error_codes\":[" + aadsts
                + "],\"timestamp\":\"2026-11-21 10:00:00Z\"}", Map.of());
    }

    public volatile Reply always = token("tok-default-SECRET", 3600);

    private final HttpServer server;
    private final Deque<Reply> script = new ArrayDeque<>();
    private final List<Request> requests = new ArrayList<>();

    public FakeTokenEndpoint() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> form = new LinkedHashMap<>();
            for (String pair : body.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
            Reply r;
            synchronized (this) {
                requests.add(new Request(exchange.getRequestURI().getPath(), form));
                r = script.isEmpty() ? always : script.poll();
            }
            byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            r.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            exchange.sendResponseHeaders(r.status(), out.length == 0 ? -1 : out.length);
            if (out.length > 0) exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    /** The sign-in address to configure (http on loopback). */
    public URI authority() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public synchronized FakeTokenEndpoint then(Reply reply) {
        script.add(reply);
        return this;
    }

    public synchronized List<Request> requests() {
        return List.copyOf(requests);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
