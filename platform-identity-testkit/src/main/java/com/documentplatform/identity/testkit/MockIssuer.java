package com.documentplatform.identity.testkit;

import com.documentplatform.identity.signin.IssuerFetch;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A mock OIDC login server for tests (generic: no product, no vendor): a JDK HTTP server on {@code 127.0.0.1} that
 * serves a discovery document and a key set (one RSA key, RS256), and mints signed test tokens. Its issuer is a local
 * test issuer ({@code http://127.0.0.1:<port>/oauth2/test}), so the test sign-in accepts it only under a development
 * licence.
 *
 * <p>Switches for the failure paths: {@link #down()}, {@link #discoveryIssuer} (another issuer in the document),
 * {@link #discoveryStatus}, {@link #keysStatus}, {@link #noKeys()} and {@link #withoutJwksUri()}. {@link #fetch()} is a
 * plain {@link IssuerFetch} for tests (no outbound rules).
 *
 * <pre>
 * try (MockIssuer issuer = MockIssuer.start()) {
 *     String token = issuer.token("api://product-a", List.of("readers"), Duration.ofMinutes(5));
 *     ...
 * }
 * </pre>
 */
public final class MockIssuer implements AutoCloseable {

    public static final String PATH = "/oauth2/test";
    public static final String KEY_ID = "mock-key-1";

    private final HttpServer server;
    private final KeyPair key;
    private final KeyPair otherKey;
    private final URI issuer;
    private final Clock clock;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile String discoveryIssuer;
    private volatile int discoveryStatus = 200;
    private volatile int keysStatus = 200;
    private volatile boolean noKeys;
    private volatile boolean withoutJwksUri;
    private volatile boolean down;

    private MockIssuer(Clock clock) throws IOException {
        this.clock = clock;
        this.key = rsa();
        this.otherKey = rsa();
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.issuer = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + PATH);
        this.discoveryIssuer = issuer.toString();
        server.createContext("/", this::handle);
        server.start();
    }

    /** Starts on a free port of {@code 127.0.0.1}. */
    public static MockIssuer start() {
        return start(Clock.systemUTC());
    }

    /** @param clock the time of the tokens' {@code iat} and {@code exp} */
    public static MockIssuer start(Clock clock) {
        try {
            return new MockIssuer(Objects.requireNonNull(clock, "clock"));
        } catch (IOException e) {
            throw new IllegalStateException("mock issuer could not start", e);
        }
    }

    /** The issuer address ({@code http://127.0.0.1:<port>/oauth2/test}). */
    public URI issuer() {
        return issuer;
    }

    public URI jwksUri() {
        return URI.create(issuer + "/keys");
    }

    /** How many requests the server answered. */
    public int calls() {
        return calls.get();
    }

    // ---- switches

    /** Every request gets no answer (the connection is closed). */
    public MockIssuer down() {
        down = true;
        return this;
    }

    public MockIssuer up() {
        down = false;
        return this;
    }

    /** The discovery document names this issuer instead of the real one. */
    public MockIssuer discoveryIssuer(String value) {
        discoveryIssuer = value;
        return this;
    }

    public MockIssuer discoveryStatus(int status) {
        discoveryStatus = status;
        return this;
    }

    public MockIssuer keysStatus(int status) {
        keysStatus = status;
        return this;
    }

    /** The key set is empty. */
    public MockIssuer noKeys() {
        noKeys = true;
        return this;
    }

    /** The discovery document has no {@code jwks_uri}. */
    public MockIssuer withoutJwksUri() {
        withoutJwksUri = true;
        return this;
    }

    // ---- tokens

    /** A token signed by this issuer's key with {@code iss}, {@code aud}, {@code exp} and the {@code groups} claim. */
    public String token(String audience, List<String> groups, Duration validFor) {
        Map<String, Object> claims = claims(audience, validFor);
        claims.put("groups", groups);
        return sign(claims, key, KEY_ID);
    }

    /** The standard claims ({@code iss}, {@code sub}, {@code aud}, {@code iat}, {@code exp}), to change and sign. */
    public Map<String, Object> claims(String audience, Duration validFor) {
        Instant now = clock.instant();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer.toString());
        claims.put("sub", "test-user");
        claims.put("aud", audience);
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plus(validFor).getEpochSecond());
        return claims;
    }

    /** Signs any claims with this issuer's key. */
    public String sign(Map<String, Object> claims) {
        return sign(claims, key, KEY_ID);
    }

    /** Signs the claims with a key the issuer does not publish, under the published key ID (a forged token). */
    public String signWithOtherKey(Map<String, Object> claims) {
        return sign(claims, otherKey, KEY_ID);
    }

    private static String sign(Map<String, Object> claims, KeyPair with, String kid) {
        String header = b64(json(Map.of("alg", "RS256", "typ", "JWT", "kid", kid)));
        String body = b64(json(claims));
        try {
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(with.getPrivate());
            s.update((header + "." + body).getBytes(StandardCharsets.US_ASCII));
            return header + "." + body + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(s.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- fetch for tests

    /** A plain fetch for tests: JDK HTTP client, no redirects, no outbound rules. */
    public static IssuerFetch fetch() {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5)).proxy(HttpClient.Builder.NO_PROXY).build();
        return (uri, maxBytes, local) -> {
            try {
                HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                        .GET().build(), HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream in = r.body()) {
                    byte[] body = in.readNBytes(maxBytes + 1);
                    if (body.length > maxBytes) throw new IssuerFetch.Refused("TOO_LARGE");
                    return new IssuerFetch.Response(r.statusCode(), body);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IssuerFetch.Refused("INTERRUPTED");
            } catch (IssuerFetch.Refused e) {
                throw e;
            } catch (IOException e) {
                throw new IssuerFetch.Refused("CANT_CONNECT");
            }
        };
    }

    // ---- server

    private void handle(HttpExchange x) throws IOException {
        calls.incrementAndGet();
        try (x) {
            if (down) {
                x.getResponseBody().close();
                return;
            }
            String path = x.getRequestURI().getPath();
            if (path.equals(PATH + "/.well-known/openid-configuration")) {
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("issuer", discoveryIssuer);
                doc.put("authorization_endpoint", issuer + "/authorize");
                doc.put("token_endpoint", issuer + "/token");
                if (!withoutJwksUri) doc.put("jwks_uri", jwksUri().toString());
                doc.put("id_token_signing_alg_values_supported", List.of("RS256"));
                send(x, discoveryStatus, json(doc));
            } else if (path.equals(PATH + "/keys")) {
                List<Object> keys = new ArrayList<>();
                if (!noKeys) {
                    RSAPublicKey pub = (RSAPublicKey) key.getPublic();
                    Map<String, Object> jwk = new LinkedHashMap<>();
                    jwk.put("kty", "RSA");
                    jwk.put("use", "sig");
                    jwk.put("alg", "RS256");
                    jwk.put("kid", KEY_ID);
                    jwk.put("n", b64(unsigned(pub.getModulus())));
                    jwk.put("e", b64(unsigned(pub.getPublicExponent())));
                    keys.add(jwk);
                }
                send(x, keysStatus, json(Map.of("keys", keys)));
            } else {
                send(x, 404, "{}");
            }
        }
    }

    private static void send(HttpExchange x, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = x.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ---- helpers

    private static KeyPair rsa() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] unsigned(BigInteger v) {
        byte[] b = v.toByteArray();
        if (b.length > 1 && b[0] == 0) {
            byte[] out = new byte[b.length - 1];
            System.arraycopy(b, 1, out, 0, out.length);
            return out;
        }
        return b;
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String b64(String text) {
        return b64(text.getBytes(StandardCharsets.UTF_8));
    }

    /** A small JSON writer for the documents above (strings, numbers, booleans, lists, maps). */
    static String json(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v);
        return b.toString();
    }

    private static void write(StringBuilder b, Object v) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String s) {
            b.append('"');
            for (char c : s.toCharArray()) {
                if (c == '"' || c == '\\') b.append('\\').append(c);
                else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                else b.append(c);
            }
            b.append('"');
        } else if (v instanceof Number || v instanceof Boolean) {
            b.append(v);
        } else if (v instanceof Map<?, ?> m) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) b.append(',');
                first = false;
                write(b, String.valueOf(e.getKey()));
                b.append(':');
                write(b, e.getValue());
            }
            b.append('}');
        } else if (v instanceof List<?> l) {
            b.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) b.append(',');
                write(b, l.get(i));
            }
            b.append(']');
        } else {
            throw new IllegalArgumentException("not JSON: " + v.getClass().getSimpleName());
        }
    }
}
