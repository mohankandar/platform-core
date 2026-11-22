package com.documentplatform.integration.testkit;

import com.documentplatform.integration.json.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * A mock destination server for tests (JDK {@code HttpServer}, http or https), generic: it answers any path, and is
 * switched per test (methods) or by a small admin call on its own port ({@link #adminAddress()}):
 * <ul>
 *   <li>{@link #working()}, {@link #down()} / {@link #up()}, {@link #slow(Duration)}</li>
 *   <li>{@link #busy(int, String, Duration)}: busy with or without a code and Retry-After</li>
 *   <li>{@link #outage()}: 503 without a code</li>
 *   <li>{@link #refuse(int, String)}: refuses with a status and a code</li>
 *   <li>{@link #alreadyReceived(String)}: 200 replay answer</li>
 *   <li>{@link #signInRefused(String)}: 401 with a code</li>
 *   <li>{@link #healthFailing(boolean)}: health failing while uploads work (or, with another switch, the reverse)</li>
 * </ul>
 * It checks the sign-in it is told to expect ({@link #expectBasic}, {@link #expectApiKey}, {@link #expectBearer},
 * {@link #expectOAuth2()}), has its own OAuth 2.0 token endpoint ({@link #tokenAddress()}; client secret in the form or
 * as Basic, or a certificate client assertion) and records what it received: method, path, headers (sign-in values
 * masked), the top-level fields of JSON bodies, and for other bodies only the SHA-256, size and media type —
 * <b>never a document's text</b>.
 *
 * <p>Errors are JSON {@code {"code": "...", "message": "..."}}; set {@code errorCodePointer} to {@code /code}.
 */
public final class MockDestination implements AutoCloseable {

    public enum Mode { WORKING, SLOW, BUSY, OUTAGE, REFUSE, ALREADY_RECEIVED, SIGN_IN_REFUSED }

    public enum TokenMode { WORKING, REFUSE_CLIENT, REFUSE_REQUEST, DOWN, BAD_REPLY }

    /** One request received. {@code fields}: top-level JSON fields; {@code sha256}/{@code size}: of the body. */
    public record Received(String method, String path, String query, Map<String, String> headers, String signIn,
                           Map<String, Object> fields, String sha256, long size, String contentType) {
        public Optional<String> header(String name) {
            return Optional.ofNullable(headers.get(name.toLowerCase(Locale.ROOT)));
        }
    }

    /** One token request: who asked and how; never the secret. */
    public record TokenRequest(String clientId, String grantType, String clientAuth, String scope, String audience,
                               boolean issued) {
    }

    private record Route(String method, Pattern path, int status, String body) {
    }

    public static final String HEALTH_PATH = "/health";
    public static final String TOKEN_PATH = "/oauth2/token";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_.:-]{1,40}");

    private final SSLContext ssl;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mock-destination");
        t.setDaemon(true);
        return t;
    });
    private final HttpServer admin;
    private volatile HttpServer server;
    private final int port;

    // switches
    private volatile Mode mode = Mode.WORKING;
    private volatile Duration delay = Duration.ZERO;
    private volatile int status = 0;
    private volatile String code;
    private volatile Duration retryAfter;
    private volatile String replayBody = "{\"alreadyReceived\":true}";
    private volatile boolean healthFailing;
    private final List<Route> routes = new CopyOnWriteArrayList<>();

    // sign-in
    private volatile String expectType = "NONE";
    private volatile String expectA;
    private volatile String expectB;
    private final Map<String, String> clientSecrets = new ConcurrentHashMap<>();
    private final Map<String, PublicKey> clientKeys = new ConcurrentHashMap<>();
    private final Map<String, Instant> tokens = new ConcurrentHashMap<>();
    private volatile TokenMode tokenMode = TokenMode.WORKING;
    private volatile long tokenLifetimeSeconds = 3600;
    private volatile String requiredScope;
    private volatile String requiredAudience;

    // records
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final List<TokenRequest> tokenRequests = new CopyOnWriteArrayList<>();
    private final AtomicInteger counter = new AtomicInteger();

    private MockDestination(SSLContext ssl) throws IOException {
        this.ssl = ssl;
        this.server = create(0);
        this.port = server.getAddress().getPort();
        this.admin = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        admin.createContext("/", this::admin);
        admin.setExecutor(pool);
        admin.start();
    }

    /** A mock on http://127.0.0.1:&lt;free port&gt;. */
    public static MockDestination start() {
        try {
            return new MockDestination(null);
        } catch (IOException e) {
            throw new IllegalStateException("mock destination could not start", e);
        }
    }

    /** A mock on https, with the given server certificate (see {@link TestCertificates}). */
    public static MockDestination startHttps(SSLContext serverContext) {
        try {
            return new MockDestination(serverContext);
        } catch (IOException e) {
            throw new IllegalStateException("mock destination could not start", e);
        }
    }

    private HttpServer create(int onPort) throws IOException {
        InetSocketAddress at = new InetSocketAddress(InetAddress.getLoopbackAddress(), onPort);
        HttpServer s;
        if (ssl != null) {
            HttpsServer h = HttpsServer.create(at, 0);
            h.setHttpsConfigurator(new HttpsConfigurator(ssl));
            s = h;
        } else {
            s = HttpServer.create(at, 0);
        }
        s.createContext("/", this::handle);
        s.setExecutor(pool);
        s.start();
        return s;
    }

    // ---- Addresses ----

    /** The base address, for example {@code http://127.0.0.1:50123}. */
    public URI address() {
        return URI.create((ssl == null ? "http" : "https") + "://127.0.0.1:" + port);
    }

    /** The base address with the host name {@code localhost} (for certificates issued to localhost). */
    public URI addressByName() {
        return URI.create((ssl == null ? "http" : "https") + "://localhost:" + port);
    }

    public URI tokenAddress() {
        return URI.create(address() + TOKEN_PATH);
    }

    public URI tokenAddressByName() {
        return URI.create(addressByName() + TOKEN_PATH);
    }

    /** The admin address (always http on 127.0.0.1): {@code POST /mode?name=BUSY&status=503&code=X&retryAfter=5}. */
    public URI adminAddress() {
        return URI.create("http://127.0.0.1:" + admin.getAddress().getPort());
    }

    public int port() {
        return port;
    }

    // ---- Switches ----

    public synchronized MockDestination working() {
        mode = Mode.WORKING;
        delay = Duration.ZERO;
        return this;
    }

    /** Stops listening: connections are refused until {@link #up()}. */
    public synchronized MockDestination down() {
        HttpServer s = server;
        if (s != null) {
            s.stop(0);
            server = null;
        }
        return this;
    }

    /** Listens again on the same port. */
    public synchronized MockDestination up() {
        if (server != null) return this;
        IOException last = null;
        for (int i = 0; i < 50; i++) {
            try {
                server = create(port);
                return this;
            } catch (BindException e) {
                last = e;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (IOException e) {
                last = e;
                break;
            }
        }
        throw new IllegalStateException("mock destination could not listen again on port " + port, last);
    }

    /** Answers normally, after {@code d}. */
    public MockDestination slow(Duration d) {
        mode = Mode.SLOW;
        delay = d;
        return this;
    }

    /**
     * Busy: {@code status} (429 or 503 usually), with the destination code {@code code} (null: none) and Retry-After
     * {@code after} (null: none).
     */
    public MockDestination busy(int status, String code, Duration after) {
        mode = Mode.BUSY;
        this.status = status;
        this.code = code;
        this.retryAfter = after;
        return this;
    }

    /** 503 without a destination code. */
    public MockDestination outage() {
        mode = Mode.OUTAGE;
        return this;
    }

    public MockDestination refuse(int status, String code) {
        mode = Mode.REFUSE;
        this.status = status;
        this.code = code;
        return this;
    }

    /** 200 with {@code body} (JSON), for "already received". Null keeps the default {@code {"alreadyReceived":true}}. */
    public MockDestination alreadyReceived(String body) {
        mode = Mode.ALREADY_RECEIVED;
        if (body != null) replayBody = body;
        return this;
    }

    /** 401 with {@code code}. */
    public MockDestination signInRefused(String code) {
        mode = Mode.SIGN_IN_REFUSED;
        this.code = code;
        return this;
    }

    /** The health path answers 503 while this is on (independent of the other switches). */
    public MockDestination healthFailing(boolean failing) {
        healthFailing = failing;
        return this;
    }

    /**
     * In WORKING mode, a request matching {@code method} and {@code pathRegex} gets {@code status} and {@code body}.
     * The body may hold {@code ${id}} (a new ID), {@code ${n}} (a counter), {@code ${sha256}} and {@code ${size}} (of
     * the request body). Routes are tried in the order added.
     */
    public MockDestination route(String method, String pathRegex, int status, String body) {
        routes.add(new Route(method, Pattern.compile(pathRegex), status, body));
        return this;
    }

    public MockDestination clearRoutes() {
        routes.clear();
        return this;
    }

    public Mode mode() {
        return mode;
    }

    // ---- Sign-in ----

    /** Accepts requests without checking sign-in (the default). */
    public MockDestination expectNone() {
        expectType = "NONE";
        return this;
    }

    public MockDestination expectBasic(String user, String password) {
        expectType = "BASIC";
        expectA = user;
        expectB = password;
        return this;
    }

    public MockDestination expectApiKey(String header, String key) {
        expectType = "API_KEY";
        expectA = header.toLowerCase(Locale.ROOT);
        expectB = key;
        return this;
    }

    public MockDestination expectBearer(String token) {
        expectType = "BEARER";
        expectA = token;
        return this;
    }

    /** Accepts bearer tokens issued by its own token endpoint (and not yet expired). */
    public MockDestination expectOAuth2() {
        expectType = "OAUTH2";
        return this;
    }

    public MockDestination registerClient(String clientId, String secret) {
        clientSecrets.put(clientId, secret);
        return this;
    }

    /** A client that proves itself with a client assertion signed by this certificate's key. */
    public MockDestination registerCertificateClient(String clientId, X509Certificate certificate) {
        clientKeys.put(clientId, certificate.getPublicKey());
        return this;
    }

    public MockDestination tokenMode(TokenMode m) {
        tokenMode = m;
        return this;
    }

    public MockDestination tokenLifetime(Duration d) {
        tokenLifetimeSeconds = d.toSeconds();
        return this;
    }

    public MockDestination requireScope(String scope) {
        requiredScope = scope;
        return this;
    }

    public MockDestination requireAudience(String audience) {
        requiredAudience = audience;
        return this;
    }

    /** Forgets the tokens issued: the next call with an old token is refused (as after a key change). */
    public MockDestination revokeTokens() {
        tokens.clear();
        return this;
    }

    // ---- Records ----

    public List<Received> received() {
        return List.copyOf(received);
    }

    /** Requests other than health checks. */
    public List<Received> calls() {
        return received.stream().filter(r -> !r.path().equals(HEALTH_PATH)).toList();
    }

    public List<TokenRequest> tokenRequests() {
        return List.copyOf(tokenRequests);
    }

    public long tokensIssued() {
        return tokenRequests.stream().filter(TokenRequest::issued).count();
    }

    public MockDestination clearRecords() {
        received.clear();
        tokenRequests.clear();
        return this;
    }

    /** All switches back to working, sign-in and records cleared. */
    public synchronized MockDestination reset() {
        up();
        working();
        routes.clear();
        healthFailing = false;
        expectNone();
        tokenMode = TokenMode.WORKING;
        requiredScope = null;
        requiredAudience = null;
        replayBody = "{\"alreadyReceived\":true}";
        return clearRecords();
    }

    // ---- Handling ----

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getRawPath();
            byte[] body;
            try (InputStream in = ex.getRequestBody()) {
                body = in.readAllBytes();
            }
            if (path.equals(TOKEN_PATH)) {
                token(ex, body);
                return;
            }
            String signIn = signIn(ex);
            received.add(record(ex, path, body, signIn));
            if (path.equals(HEALTH_PATH)) {
                if (healthFailing) {
                    send(ex, 503, "{\"status\":\"DOWN\"}", null);
                } else {
                    send(ex, 200, "{\"status\":\"UP\"}", null);
                }
                return;
            }
            Mode m = mode;
            if (m == Mode.SLOW) pause(delay);
            if (signIn == null && m != Mode.SIGN_IN_REFUSED) {
                send(ex, 401, error("MOCK-AUTH-REQUIRED"), null);
                return;
            }
            switch (m) {
                case BUSY -> send(ex, status, code == null ? "{}" : error(code), retryAfter);
                case OUTAGE -> send(ex, 503, "{\"message\":\"unavailable\"}", null);
                case REFUSE -> send(ex, status, error(code), null);
                case ALREADY_RECEIVED -> send(ex, 200, fill(replayBody, body), null);
                case SIGN_IN_REFUSED -> send(ex, 401, error(code), null);
                case WORKING, SLOW -> {
                    for (Route r : routes) {
                        if (r.method().equalsIgnoreCase(ex.getRequestMethod()) && r.path().matcher(path).matches()) {
                            if (r.status() / 100 == 3) {
                                ex.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
                            }
                            send(ex, r.status(), fill(r.body(), body), null);
                            return;
                        }
                    }
                    send(ex, 201, fill("{\"id\":\"${id}\",\"sha256\":\"${sha256}\",\"size\":${size}}", body), null);
                }
            }
        } catch (RuntimeException e) {
            // A test server: answer 500 rather than hang the client.
            try {
                send(ex, 500, error("MOCK-INTERNAL"), null);
            } catch (IOException ignored) {
                // the client went away
            }
        }
    }

    /** The accepted sign-in's type, or null when it was not accepted. */
    private String signIn(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        switch (expectType) {
            case "NONE":
                return "NONE";
            case "BASIC": {
                String want = "Basic " + Base64.getEncoder().encodeToString((expectA + ":" + expectB)
                        .getBytes(StandardCharsets.UTF_8));
                return want.equals(auth) ? "BASIC" : null;
            }
            case "API_KEY":
                return expectB.equals(ex.getRequestHeaders().getFirst(expectA)) ? "API_KEY" : null;
            case "BEARER":
                return ("Bearer " + expectA).equals(auth) ? "BEARER" : null;
            case "OAUTH2": {
                if (auth == null || !auth.startsWith("Bearer ")) return null;
                Instant exp = tokens.get(auth.substring(7));
                return exp != null && Instant.now().isBefore(exp) ? "OAUTH2" : null;
            }
            default:
                return null;
        }
    }

    private Received record(HttpExchange ex, String path, byte[] body, String signIn) {
        Map<String, String> headers = new LinkedHashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> {
            String name = k.toLowerCase(Locale.ROOT);
            boolean secret = name.equals("authorization") || name.equals("proxy-authorization")
                    || name.equals("cookie") || name.equals(expectA) && "API_KEY".equals(expectType);
            headers.put(name, secret ? "[present]" : String.join(",", v));
        });
        String type = ex.getRequestHeaders().getFirst("Content-Type");
        Map<String, Object> fields = Map.of();
        if (type != null && type.toLowerCase(Locale.ROOT).contains("json") && body.length > 0) {
            try {
                fields = Json.object(new String(body, StandardCharsets.UTF_8));
            } catch (Json.Invalid e) {
                fields = Map.of();
            }
        }
        return new Received(ex.getRequestMethod(), path, ex.getRequestURI().getRawQuery(), Map.copyOf(headers),
                signIn, fields, sha256(body), body.length, type);
    }

    private void token(HttpExchange ex, byte[] body) throws IOException {
        Map<String, String> form = form(new String(body, StandardCharsets.UTF_8));
        String clientAuth = "none";
        String clientId = form.get("client_id");
        boolean ok = false;
        String basic = ex.getRequestHeaders().getFirst("Authorization");
        if (basic != null && basic.startsWith("Basic ")) {
            String pair = new String(Base64.getDecoder().decode(basic.substring(6)), StandardCharsets.UTF_8);
            int colon = pair.indexOf(':');
            clientId = URLDecoder.decode(pair.substring(0, colon), StandardCharsets.UTF_8);
            String secret = URLDecoder.decode(pair.substring(colon + 1), StandardCharsets.UTF_8);
            clientAuth = "client_secret_basic";
            ok = secret.equals(clientSecrets.get(clientId));
        } else if (form.containsKey("client_secret")) {
            clientAuth = "client_secret_post";
            ok = clientId != null && form.get("client_secret").equals(clientSecrets.get(clientId));
        } else if (form.containsKey("client_assertion")) {
            clientAuth = "private_key_jwt";
            ok = clientId != null && assertionValid(form.get("client_assertion"), clientId);
        }
        TokenMode tm = tokenMode;
        boolean grant = "client_credentials".equals(form.get("grant_type"));
        boolean scopeOk = requiredScope == null || requiredScope.equals(form.get("scope"));
        boolean audienceOk = requiredAudience == null || requiredAudience.equals(form.get("audience"));
        boolean issue = tm == TokenMode.WORKING && ok && grant && scopeOk && audienceOk;
        tokenRequests.add(new TokenRequest(clientId, form.get("grant_type"), clientAuth, form.get("scope"),
                form.get("audience"), issue));
        if (tm == TokenMode.DOWN) {
            send(ex, 503, "{\"error\":\"temporarily_unavailable\"}", null);
        } else if (tm == TokenMode.BAD_REPLY) {
            send(ex, 200, "{\"token_type\":\"Bearer\"}", null);
        } else if (tm == TokenMode.REFUSE_CLIENT || !ok) {
            send(ex, 401, "{\"error\":\"invalid_client\",\"error_description\":\"no\"}", null);
        } else if (tm == TokenMode.REFUSE_REQUEST || !grant || !scopeOk || !audienceOk) {
            send(ex, 400, "{\"error\":\"invalid_scope\"}", null);
        } else {
            String token = "mock-" + UUID.randomUUID();
            tokens.put(token, Instant.now().plusSeconds(tokenLifetimeSeconds));
            send(ex, 200, "{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\",\"expires_in\":"
                    + tokenLifetimeSeconds + "}", null);
        }
    }

    private boolean assertionValid(String jwt, String clientId) {
        PublicKey key = clientKeys.get(clientId);
        String[] p = jwt.split("\\.");
        if (key == null || p.length != 3) return false;
        try {
            Signature v = Signature.getInstance("SHA256withRSA");
            v.initVerify(key);
            v.update((p[0] + "." + p[1]).getBytes(StandardCharsets.US_ASCII));
            if (!v.verify(Base64.getUrlDecoder().decode(p[2]))) return false;
            Map<String, Object> claims = Json.object(new String(Base64.getUrlDecoder().decode(p[1]),
                    StandardCharsets.UTF_8));
            String aud = String.valueOf(claims.get("aud"));
            boolean audOk = aud.equals(tokenAddress().toString()) || aud.equals(tokenAddressByName().toString());
            return audOk && clientId.equals(claims.get("iss")) && clientId.equals(claims.get("sub"))
                    && claims.get("exp") instanceof Long exp && exp > Instant.now().getEpochSecond();
        } catch (Exception e) {
            return false;
        }
    }

    private void admin(HttpExchange ex) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            Map<String, String> q = form(ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery());
            switch (path) {
                case "/mode" -> {
                    String name = q.getOrDefault("name", "WORKING");
                    switch (name) {
                        case "WORKING" -> working();
                        case "DOWN" -> down();
                        case "UP" -> up();
                        case "SLOW" -> slow(Duration.ofMillis(Long.parseLong(q.getOrDefault("delayMs", "1000"))));
                        case "BUSY" -> busy(Integer.parseInt(q.getOrDefault("status", "429")), q.get("code"),
                                q.containsKey("retryAfter") ? Duration.ofSeconds(Long.parseLong(q.get("retryAfter")))
                                        : null);
                        case "OUTAGE" -> outage();
                        case "REFUSE" -> refuse(Integer.parseInt(q.getOrDefault("status", "400")),
                                q.getOrDefault("code", "MOCK-REFUSED"));
                        case "ALREADY_RECEIVED" -> alreadyReceived(null);
                        case "SIGN_IN_REFUSED" -> signInRefused(q.getOrDefault("code", "MOCK-AUTH-REFUSED"));
                        default -> {
                            send(ex, 400, error("MOCK-ADMIN-UNKNOWN-MODE"), null);
                            return;
                        }
                    }
                    send(ex, 200, "{\"mode\":\"" + name + "\"}", null);
                }
                case "/health" -> {
                    healthFailing(Boolean.parseBoolean(q.getOrDefault("failing", "false")));
                    send(ex, 200, "{\"healthFailing\":" + healthFailing + "}", null);
                }
                case "/reset" -> {
                    reset();
                    send(ex, 200, "{}", null);
                }
                case "/received" -> {
                    List<Object> out = new ArrayList<>();
                    for (Received r : received) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("method", r.method());
                        m.put("path", r.path());
                        m.put("signIn", r.signIn());
                        m.put("sha256", r.sha256());
                        m.put("size", r.size());
                        out.add(m);
                    }
                    send(ex, 200, Json.write(out), null);
                }
                default -> send(ex, 404, error("MOCK-ADMIN-NOT-FOUND"), null);
            }
        } catch (RuntimeException e) {
            send(ex, 400, error("MOCK-ADMIN-BAD-REQUEST"), null);
        }
    }

    private String fill(String template, byte[] body) {
        return template.replace("${id}", UUID.randomUUID().toString())
                .replace("${n}", Integer.toString(counter.incrementAndGet()))
                .replace("${sha256}", sha256(body))
                .replace("${size}", Long.toString(body.length));
    }

    /** An error body; without a code (null) only a message. */
    private static String error(String code) {
        if (code == null) return "{\"message\":\"mock answer\"}";
        String c = SAFE.matcher(code).matches() ? code : "MOCK-ERROR";
        return "{\"code\":\"" + c + "\",\"message\":\"mock answer\"}";
    }

    private static void send(HttpExchange ex, int status, String body, Duration retryAfter) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        if (retryAfter != null) ex.getResponseHeaders().set("Retry-After", Long.toString(retryAfter.toSeconds()));
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(b);
            }
        }
    }

    private static void pause(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Map<String, String> form(String s) {
        Map<String, String> m = new LinkedHashMap<>();
        if (s == null || s.isEmpty()) return m;
        for (String pair : s.split("&")) {
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            m.put(k, v);
        }
        return m;
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        HttpServer s = server;
        if (s != null) s.stop(0);
        admin.stop(0);
        pool.shutdownNow();
    }
}
