package com.documentplatform.integration.testkit;

import com.documentplatform.integration.json.internal.Json;
import com.documentplatform.integration.testkit.openapi.OpenApiSpec;
import com.documentplatform.integration.json.JsonSchema;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * A mock destination server for tests (JDK {@code HttpServer}, http or https), switched per test (methods) or by a
 * small admin call on its own port ({@link #adminAddress()}):
 * <ul>
 *   <li>{@link #working()}, {@link #down()} / {@link #up()}, {@link #slow(Duration)}</li>
 *   <li>{@link #busy(int, String, Duration)}: busy with or without a code and Retry-After</li>
 *   <li>{@link #outage()}: 503 without a code</li>
 *   <li>{@link #refuse(int, String)}: refuses with a status and a code</li>
 *   <li>{@link #alreadyReceived(String)}: 200 replay answer</li>
 *   <li>{@link #signInRefused(String)}: 401 with a code</li>
 *   <li>health ({@link #healthPath(String)}): {@link #healthFailing(boolean)}, {@link #healthBusy(String, Duration)},
 *       {@link #healthSlow(Duration)}, independent of the other switches</li>
 * </ul>
 * Generic by default: it answers any path. {@link #specMode} makes it follow an OpenAPI document instead: only the
 * document's operations exist, requests are checked against it (required headers and parameters, media type, JSON
 * body schema) and refused with the code the {@link SpecBehaviour} names, and answers are built from the response
 * schemas (and checked against them).
 *
 * <p>It checks the sign-in it is told to expect ({@link #expectBasic}, {@link #expectApiKey}, {@link #expectBearer},
 * {@link #expectOAuth2()}), has its own OAuth 2.0 token endpoint ({@link #tokenAddress()}; client secret in the form or
 * as Basic, or a certificate client assertion; tokens can carry claims and the mock can require them), and records
 * what it received: method, path, headers (sign-in values masked), the names of JSON body fields (their values only
 * after {@link #recordValues(boolean)}), and for other bodies only the SHA-256, size and media type — <b>never a
 * document's text</b>.
 */
public final class MockDestination implements AutoCloseable {

    public enum Mode { WORKING, SLOW, BUSY, OUTAGE, REFUSE, ALREADY_RECEIVED, SIGN_IN_REFUSED }

    public enum TokenMode { WORKING, REFUSE_CLIENT, REFUSE_REQUEST, DOWN, BAD_REPLY }

    /**
     * One request received. {@code fields}: top-level JSON field names, with their values only when
     * {@link #recordValues(boolean)} is on ({@value #NOT_RECORDED} otherwise); {@code sha256}/{@code size}: of the
     * body; {@code operation}: the OpenAPI operation in spec mode, else null.
     */
    public record Received(String method, String path, String query, Map<String, String> headers, String signIn,
                           Map<String, Object> fields, String sha256, long size, String contentType,
                           String operation, List<Part> parts) {
        public Optional<String> header(String name) {
            return Optional.ofNullable(headers.get(name.toLowerCase(Locale.ROOT)));
        }
    }

    /** One part of a multipart/form-data body: its name, file name and media type, its SHA-256 and size. */
    public record Part(String name, String fileName, String contentType, String sha256, long size) {
    }

    /** One token request: who asked and how; never the secret. */
    public record TokenRequest(String clientId, String grantType, String clientAuth, String scope, String audience,
                               boolean issued) {
    }

    /** A request in spec mode, for {@link SpecBehaviour}: the parsed parts, never the document's bytes. */
    public record SpecRequest(String operationId, String method, String path, Map<String, String> pathParams,
                              Map<String, String> headers, Object json, String sha256, long size) {
        public Optional<String> header(String name) {
            return Optional.ofNullable(headers.get(name.toLowerCase(Locale.ROOT)));
        }
    }

    /** What an API's mock does beyond its OpenAPI document: its refusal codes, error body and answer values. */
    public interface SpecBehaviour {
        /** The code a request the document refuses gets (missing header or parameter, body not matching). */
        String badRequestCode();

        /** The code for a body of another media type (415). */
        default String unsupportedMediaCode() {
            return badRequestCode();
        }

        /** The error body for a code; {@code reference} is the request's correlation ID or a new one. */
        default Map<String, Object> error(String code, String reference) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (code != null) m.put("code", code);
            m.put("message", "mock answer");
            return m;
        }

        /** Values put into the success answer built from the schema (top-level fields). */
        default Map<String, Object> answer(SpecRequest request) {
            return Map.of();
        }

        /** Values put into the 200 "already received" answer built from the schema. */
        default Map<String, Object> replay(SpecRequest request) {
            return Map.of();
        }

        /** An error answer for this one request instead of the normal one (for example to fail one upload). */
        default Optional<SpecError> refuse(SpecRequest request) {
            return Optional.empty();
        }
    }

    /** An error answer a {@link SpecBehaviour} chose: status, code (or null) and Retry-After (or null). */
    public record SpecError(int status, String code, Duration retryAfter) {
    }

    private record Route(String method, Pattern path, int status, String body) {
    }

    public static final String HEALTH_PATH = "/health";
    public static final String TOKEN_PATH = "/oauth2/token";
    public static final String NOT_RECORDED = "[not recorded]";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_.:-]{1,40}");
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private final SSLContext ssl;
    private final boolean clientAuth;
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
    private volatile String healthPath = HEALTH_PATH;
    private volatile boolean healthFailing;
    private volatile String healthBusyCode;
    private volatile Duration healthBusyRetryAfter;
    private volatile boolean healthBusy;
    private volatile Duration healthDelay = Duration.ZERO;
    private volatile boolean recordValues;
    private volatile String errorCodePointer = "/code";
    private final List<Route> routes = new CopyOnWriteArrayList<>();

    // spec mode
    private volatile OpenApiSpec spec;
    private volatile JsonSchema schemas;
    private volatile SpecBehaviour behaviour;
    private final List<String> specProblems = new CopyOnWriteArrayList<>();

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
    private volatile Map<String, String> tokenClaims = Map.of();
    private volatile List<String> requiredClaims = List.of();
    private volatile String missingClaimsCode;

    // accepted, check later
    private volatile Pattern acceptPath;
    private volatile String acceptMethod;
    private volatile int acceptWorkingPolls;
    private volatile String acceptFinalState;
    private volatile Duration acceptRetryAfter;
    private volatile URI acceptLocationBase;
    private final Map<String, AtomicInteger> statusPolls = new ConcurrentHashMap<>();

    // records
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final List<PeerCertificate> clientCertificates = new CopyOnWriteArrayList<>();
    private final List<TokenRequest> tokenRequests = new CopyOnWriteArrayList<>();
    private final AtomicInteger counter = new AtomicInteger();

    private MockDestination(SSLContext ssl) throws IOException {
        this(ssl, false);
    }

    private MockDestination(SSLContext ssl, boolean clientAuth) throws IOException {
        this.ssl = ssl;
        this.clientAuth = clientAuth;
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

    /**
     * A mock on https that <b>requires a client certificate</b> (mutual TLS) and accepts only those its server context
     * trusts ({@link TestCertificates#serverTrusting}). A missing or untrusted certificate fails the TLS handshake
     * (nothing reaches the handlers); each accepted request's certificate is in {@link #clientCertificates()}.
     */
    public static MockDestination startMutualTls(SSLContext serverContext) {
        try {
            return new MockDestination(serverContext, true);
        } catch (IOException e) {
            throw new IllegalStateException("mock destination could not start", e);
        }
    }

    private HttpServer create(int onPort) throws IOException {
        InetSocketAddress at = new InetSocketAddress(InetAddress.getLoopbackAddress(), onPort);
        HttpServer s;
        if (ssl != null) {
            HttpsServer h = HttpsServer.create(at, 0);
            h.setHttpsConfigurator(clientAuth ? new HttpsConfigurator(ssl) {
                @Override
                public void configure(com.sun.net.httpserver.HttpsParameters params) {
                    javax.net.ssl.SSLParameters p = getSSLContext().getDefaultSSLParameters();
                    p.setNeedClientAuth(true);
                    params.setSSLParameters(p);
                }
            } : new HttpsConfigurator(ssl));
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

    /**
     * 200 "already received". Generic mode: {@code body} (JSON; null keeps {@code {"alreadyReceived":true}}). Spec
     * mode: the answer built from the 200 schema with the behaviour's replay values ({@code body} is ignored).
     */
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

    /** The health check's path (default {@value #HEALTH_PATH}). */
    public MockDestination healthPath(String path) {
        healthPath = path;
        return this;
    }

    /** The health path answers 503 while this is on (independent of the other switches). */
    public MockDestination healthFailing(boolean failing) {
        healthFailing = failing;
        if (failing) healthBusy = false;
        return this;
    }

    /** The health path answers 503 busy: with {@code code} (null: none) and Retry-After {@code after} (null: none). */
    public MockDestination healthBusy(String code, Duration after) {
        healthBusy = true;
        healthFailing = false;
        healthBusyCode = code;
        healthBusyRetryAfter = after;
        return this;
    }

    /** The health path answers after {@code d}. */
    public MockDestination healthSlow(Duration d) {
        healthDelay = d;
        return this;
    }

    /** Health answers 200 again, without delay. */
    public MockDestination healthOk() {
        healthFailing = false;
        healthBusy = false;
        healthDelay = Duration.ZERO;
        return this;
    }

    /**
     * In WORKING mode, a request matching {@code method} and {@code pathRegex} gets {@code status} and {@code body}.
     * The body may hold {@code ${id}} (a new ID), {@code ${n}} (a counter), {@code ${sha256}} and {@code ${size}} (of
     * the request body). Routes are tried in the order added, before spec mode.
     */
    public MockDestination route(String method, String pathRegex, int status, String body) {
        routes.add(new Route(method, Pattern.compile(pathRegex), status, body));
        return this;
    }

    public MockDestination clearRoutes() {
        routes.clear();
        return this;
    }

    /** Records JSON body values too (for tests of non-sensitive values); off by default: field names only. */
    public MockDestination recordValues(boolean on) {
        recordValues = on;
        return this;
    }

    /** Follows the OpenAPI document: its operations only, requests checked, answers from its schemas. */
    public MockDestination specMode(OpenApiSpec document, SpecBehaviour b) {
        spec = document;
        schemas = new JsonSchema(document.root());
        behaviour = b;
        return this;
    }

    /** What spec mode refused and why (places and codes, never values), in order. */
    public List<String> specProblems() {
        return List.copyOf(specProblems);
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

    /**
     * Issued tokens become JWTs (unsigned, for tests) carrying {@code sub} (the client ID), {@code exp} and these
     * claims (a login server set up to add them). Empty: no claims added.
     */
    public MockDestination tokenClaims(Map<String, String> claims) {
        tokenClaims = Map.copyOf(claims);
        return this;
    }

    /** Refuses (401 with {@code code}) a token without every one of these claims. Empty: no check. */
    public MockDestination requireTokenClaims(String code, List<String> names) {
        requiredClaims = List.copyOf(names);
        missingClaimsCode = code;
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
        String h = healthPath;
        return received.stream().filter(r -> !r.path().equals(h)).toList();
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
        specProblems.clear();
        clientCertificates.clear();
        statusPolls.clear();
        return this;
    }

    /** A client certificate the mock saw (mutual TLS): its subject and SHA-256 fingerprint; never a key. */
    public record PeerCertificate(String subject, String sha256) {
    }

    /** The client certificate of each request that reached the handlers (mutual TLS), in order. */
    public List<PeerCertificate> clientCertificates() {
        return List.copyOf(clientCertificates);
    }

    // ---- Accepted, check later ----

    /** Where the mock's status answers are: {@code GET /api/status/<id>}. */
    public static final String STATUS_PATH = "/api/status/";

    /**
     * {@code method pathRegex} answers <b>202 Accepted</b> with {@code {"statusId":"st-<n>"}}, a {@code Location}
     * header {@code /api/status/st-<n>} and, when given, Retry-After. {@code GET /api/status/<id>} then answers
     * {@code {"state":"WORKING"}} (200) {@code workingPolls} times, then {@code {"state":"<finalState>",
     * "resultId":"res-<n>"}}, or {@code {"state":"FAILED","code":"MOCK-STATUS-FAILED"}} when finalState is FAILED.
     * Other switches (refuse, outage, busy, sign-in) apply to the status calls as to any call.
     */
    public MockDestination acceptLater(String method, String pathRegex, int workingPolls, String finalState,
                                       Duration retryAfter) {
        acceptMethod = method;
        acceptPath = Pattern.compile(pathRegex);
        acceptWorkingPolls = workingPolls;
        acceptFinalState = finalState;
        acceptRetryAfter = retryAfter;
        return this;
    }

    /**
     * The 202 answers' {@code Location} points at {@code base} (absolute: {@code <base>/api/status/st-<n>}), for tests
     * of the same-origin rule with another server; null: the mock's own path again.
     */
    public MockDestination acceptLaterLocation(URI base) {
        acceptLocationBase = base;
        return this;
    }

    /** Stops answering 202. */
    public MockDestination acceptNow() {
        acceptPath = null;
        acceptLocationBase = null;
        return this;
    }

    /** How many status calls the mock answered for {@code statusId}. */
    public int statusPolls(String statusId) {
        AtomicInteger n = statusPolls.get(statusId);
        return n == null ? 0 : n.get();
    }

    private boolean acceptLater(HttpExchange ex, String path, byte[] body) throws IOException {
        Pattern p = acceptPath;
        if (p == null) return false;
        if (ex.getRequestMethod().equalsIgnoreCase("GET") && path.startsWith(STATUS_PATH)) {
            String id = path.substring(STATUS_PATH.length());
            AtomicInteger n = statusPolls.get(id);
            if (n == null) {
                send(ex, 404, error("MOCK-STATUS-UNKNOWN", null), null);
                return true;
            }
            int poll = n.incrementAndGet();
            if (poll <= acceptWorkingPolls) {
                send(ex, 200, "{\"state\":\"WORKING\"}", acceptRetryAfter);
            } else if ("FAILED".equals(acceptFinalState)) {
                send(ex, 200, "{\"state\":\"FAILED\",\"code\":\"MOCK-STATUS-FAILED\"}", null);
            } else {
                send(ex, 200, "{\"state\":\"" + acceptFinalState + "\",\"resultId\":\"res-" + id + "\"}", null);
            }
            return true;
        }
        if (ex.getRequestMethod().equalsIgnoreCase(acceptMethod) && p.matcher(path).matches()) {
            String id = "st-" + counter.incrementAndGet();
            statusPolls.put(id, new AtomicInteger());
            URI base = acceptLocationBase;
            ex.getResponseHeaders().add("Location", (base == null ? "" : base.toString()) + STATUS_PATH + id);
            send(ex, 202, "{\"statusId\":\"" + id + "\"}", acceptRetryAfter);
            return true;
        }
        return false;
    }

    /** All switches back to working, sign-in and records cleared (spec mode and health path kept). */
    public synchronized MockDestination reset() {
        up();
        working();
        routes.clear();
        healthOk();
        expectNone();
        tokenMode = TokenMode.WORKING;
        requiredScope = null;
        requiredAudience = null;
        tokenClaims = Map.of();
        requiredClaims = List.of();
        recordValues = false;
        replayBody = "{\"alreadyReceived\":true}";
        acceptPath = null;
        acceptLocationBase = null;
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
            String reference = Optional.ofNullable(ex.getRequestHeaders().getFirst("X-Correlation-Id"))
                    .orElse(UUID.randomUUID().toString());
            OpenApiSpec.Operation op = spec == null ? null : spec.find(ex.getRequestMethod(), path).orElse(null);
            String signIn = signIn(ex);
            received.add(record(ex, path, body, signIn, op));
            if (ex instanceof com.sun.net.httpserver.HttpsExchange hx) {
                try {
                    if (hx.getSSLSession().getPeerCertificates()[0] instanceof X509Certificate peer) {
                        clientCertificates.add(new PeerCertificate(peer.getSubjectX500Principal().getName(),
                                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                                        .digest(peer.getEncoded()))));
                    }
                } catch (javax.net.ssl.SSLPeerUnverifiedException | java.security.GeneralSecurityException none) {
                    // no client certificate on this connection
                }
            }
            if (path.equals(healthPath)) {
                health(ex);
                return;
            }
            Mode m = mode;
            if (m == Mode.SLOW) pause(delay);
            if (signIn == null && m != Mode.SIGN_IN_REFUSED) {
                send(ex, 401, error("MOCK-AUTH-REQUIRED", reference), null);
                return;
            }
            if (signIn != null && signIn.startsWith("MISSING-CLAIMS") && m != Mode.SIGN_IN_REFUSED) {
                send(ex, 401, error(missingClaimsCode, reference), null);
                return;
            }
            switch (m) {
                case BUSY -> send(ex, status, code == null ? "{}" : error(code, reference), retryAfter);
                case OUTAGE -> send(ex, 503, "{\"message\":\"unavailable\"}", null);
                case REFUSE -> send(ex, status, error(code, reference), null);
                case ALREADY_RECEIVED -> {
                    if (spec != null) answerFromSpec(ex, path, body, op, true, reference);
                    else send(ex, 200, fill(replayBody, body), null);
                }
                case SIGN_IN_REFUSED -> send(ex, 401, error(code, reference), null);
                case WORKING, SLOW -> {
                    if (acceptLater(ex, path, body)) return;
                    for (Route r : routes) {
                        if (r.method().equalsIgnoreCase(ex.getRequestMethod()) && r.path().matcher(path).matches()) {
                            if (r.status() / 100 == 3) {
                                ex.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
                            }
                            send(ex, r.status(), fill(r.body(), body), null);
                            return;
                        }
                    }
                    if (spec != null) {
                        answerFromSpec(ex, path, body, op, false, reference);
                        return;
                    }
                    send(ex, 201, fill("{\"id\":\"${id}\",\"sha256\":\"${sha256}\",\"size\":${size}}", body), null);
                }
            }
        } catch (RuntimeException e) {
            // A test server: answer 500 rather than hang the client.
            try {
                send(ex, 500, error("MOCK-INTERNAL", null), null);
            } catch (IOException ignored) {
                // the client went away
            }
        }
    }

    private void health(HttpExchange ex) throws IOException {
        pause(healthDelay);
        if (healthBusy) {
            Map<String, Object> body = healthBusyCode == null ? new LinkedHashMap<>()
                    : errorBody(errorCodePointer, healthBusyCode);
            body.remove("message");
            body.put("status", "NOT_READY");
            String b = Json.write(body);
            send(ex, 503, b, healthBusyRetryAfter);
        } else if (healthFailing) {
            send(ex, 503, "{\"status\":\"" + (spec != null ? "NOT_READY" : "DOWN") + "\"}", null);
        } else {
            send(ex, 200, "{\"status\":\"UP\"}", null);
        }
    }

    /** Spec mode: check the request against the document, then answer from the response schema. */
    private void answerFromSpec(HttpExchange ex, String path, byte[] body, OpenApiSpec.Operation op, boolean replay,
                                String reference) throws IOException {
        if (op == null) {
            specProblems.add(ex.getRequestMethod() + " " + path + ": no such operation");
            send(ex, 404, "{\"message\":\"not found\"}", null);
            return;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), String.join(",", v)));
        Map<String, String> pathParams = OpenApiSpec.pathParams(op.pathTemplate(), path).orElse(Map.of());
        Map<String, String> query = form(ex.getRequestURI().getRawQuery());
        List<String> problems = new ArrayList<>();
        for (OpenApiSpec.Param p : op.params()) {
            String v = switch (p.in()) {
                case "header" -> headers.get(p.name().toLowerCase(Locale.ROOT));
                case "path" -> pathParams.get(p.name());
                case "query" -> query.get(p.name());
                default -> null;
            };
            if (v == null) {
                if (p.required()) problems.add(p.in() + " " + p.name() + ": missing");
                continue;
            }
            for (JsonSchema.Problem s : schemas.check(v, p.schema())) problems.add(p.in() + " " + p.name() + " " + s.what());
        }
        Object json = null;
        if (!op.requestBodies().isEmpty()) {
            String type = Optional.ofNullable(ex.getRequestHeaders().getFirst("Content-Type")).orElse("")
                    .split(";")[0].trim().toLowerCase(Locale.ROOT);
            if (!op.requestBodies().containsKey(type)) {
                specProblems.add(op.id() + ": media type " + (type.isEmpty() ? "missing" : type) + " not accepted");
                send(ex, 415, error(behaviour.unsupportedMediaCode(), reference), null);
                return;
            }
            if (op.bodyRequired() && body.length == 0) problems.add("body: missing");
            if (type.equals("multipart/form-data")) {
                Map<String, Object> schema = schemas.resolve(op.requestBodies().get(type));
                String fullType = ex.getRequestHeaders().getFirst("Content-Type");
                List<Part> parts = parts(fullType, body);
                if (schema.get("required") instanceof List<?> req) {
                    for (Object name : req) {
                        if (parts.stream().noneMatch(p -> name.equals(p.name()))) problems.add("part " + name + ": missing");
                    }
                }
                if (schema.get("properties") instanceof Map<?, ?> props) {
                    for (Part p : parts) {
                        if (p.contentType() != null && p.contentType().toLowerCase(Locale.ROOT).contains("json")
                                && props.get(p.name()) instanceof Map<?, ?> ps) {
                            try {
                                @SuppressWarnings("unchecked") Map<String, Object> partSchema = (Map<String, Object>) ps;
                                Object partJson = Json.parse(new String(partContent(fullType, body, p.name()),
                                        StandardCharsets.UTF_8));
                                for (JsonSchema.Problem s : schemas.check(partJson, partSchema)) {
                                    problems.add("part " + p.name() + s);
                                }
                            } catch (Json.Invalid e) {
                                problems.add("part " + p.name() + ": not JSON");
                            }
                        }
                    }
                }
            }
            if (type.contains("json")) {
                try {
                    json = Json.parse(new String(body, StandardCharsets.UTF_8));
                    for (JsonSchema.Problem s : schemas.check(json, op.requestBodies().get(type))) problems.add("body" + s);
                } catch (Json.Invalid e) {
                    problems.add("body: not JSON");
                }
            }
        }
        if (!problems.isEmpty()) {
            for (String p : problems) specProblems.add(op.id() + ": " + p);
            send(ex, 400, error(behaviour.badRequestCode(), reference), null);
            return;
        }
        SpecRequest request = new SpecRequest(op.id(), ex.getRequestMethod(), path, pathParams, headers, json,
                sha256(body), body.length);
        Optional<SpecError> refused = behaviour.refuse(request);
        if (refused.isPresent()) {
            SpecError e = refused.get();
            send(ex, e.status(), e.code() == null ? "{\"message\":\"unavailable\"}" : error(e.code(), reference),
                    e.retryAfter());
            return;
        }
        String statusKey = replay ? "200" : op.answers().containsKey("201") ? "201" : op.answers().containsKey("200")
                ? "200" : op.answers().keySet().stream().filter(k -> k.startsWith("2")).findFirst().orElse(null);
        OpenApiSpec.Answer answer = statusKey == null ? null : op.answers().get(statusKey);
        if (answer == null || answer.schema() == null) {
            send(ex, statusKey == null ? 204 : Integer.parseInt(statusKey), "", null);
            return;
        }
        Object sample = schemas.sample(answer.schema());
        if (sample instanceof Map<?, ?> sm) {
            @SuppressWarnings("unchecked") Map<String, Object> out = new LinkedHashMap<>((Map<String, Object>) sm);
            out.putAll(replay ? behaviour.replay(request) : behaviour.answer(request));
            sample = out;
        }
        List<JsonSchema.Problem> own = schemas.check(sample, answer.schema());
        if (!own.isEmpty()) {
            for (JsonSchema.Problem p : own) specProblems.add(op.id() + " answer " + statusKey + ": " + p);
            send(ex, 500, error("MOCK-SPEC-ANSWER-INVALID", reference), null);
            return;
        }
        send(ex, Integer.parseInt(statusKey), Json.write(sample), null);
    }

    /**
     * The accepted sign-in's type, or null when it was not accepted. An OAuth 2.0 token without a required claim
     * gives {@code MISSING-CLAIMS}.
     */
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
                String token = auth.substring(7);
                Instant exp = tokens.get(token);
                if (exp == null || !Instant.now().isBefore(exp)) return null;
                if (!requiredClaims.isEmpty() && !hasClaims(token, requiredClaims)) return "MISSING-CLAIMS";
                return "OAUTH2";
            }
            default:
                return null;
        }
    }

    private static boolean hasClaims(String token, List<String> names) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) return false;
        try {
            Map<String, Object> claims = Json.object(new String(Base64.getUrlDecoder().decode(parts[1]),
                    StandardCharsets.UTF_8));
            return names.stream().allMatch(n -> claims.get(n) instanceof String s && !s.isBlank());
        } catch (Json.Invalid | IllegalArgumentException e) {
            return false;
        }
    }

    private Received record(HttpExchange ex, String path, byte[] body, String signIn, OpenApiSpec.Operation op) {
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
                Map<String, Object> parsed = Json.object(new String(body, StandardCharsets.UTF_8));
                if (recordValues) {
                    fields = parsed;
                } else {
                    Map<String, Object> names = new LinkedHashMap<>();
                    for (String k : parsed.keySet()) names.put(k, NOT_RECORDED);
                    fields = names;
                }
            } catch (Json.Invalid e) {
                fields = Map.of();
            }
        }
        return new Received(ex.getRequestMethod(), path, ex.getRequestURI().getRawQuery(), Map.copyOf(headers),
                signIn, fields, sha256(body), body.length, type, op == null ? null : op.id(), parts(type, body));
    }

    /** The parts of a multipart/form-data body (names, file names, types, checksums; never the content). */
    static List<Part> parts(String contentType, byte[] body) {
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) return List.of();
        java.util.regex.Matcher bm = Pattern.compile("boundary=([^;]+)").matcher(contentType);
        if (!bm.find()) return List.of();
        byte[] delimiter = ("--" + bm.group(1)).getBytes(StandardCharsets.ISO_8859_1);
        List<Part> out = new ArrayList<>();
        int at = indexOf(body, delimiter, 0);
        while (at >= 0) {
            int start = at + delimiter.length;
            if (start + 2 <= body.length && body[start] == '-' && body[start + 1] == '-') break; // closing delimiter
            start += 2; // CRLF
            int headEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), start);
            int next = indexOf(body, delimiter, headEnd < 0 ? start : headEnd);
            if (headEnd < 0 || next < 0) break;
            String head = new String(body, start, headEnd - start, StandardCharsets.UTF_8);
            byte[] content = java.util.Arrays.copyOfRange(body, headEnd + 4, next - 2); // without the CRLF before
            java.util.regex.Matcher n = Pattern.compile("name=\"([^\"]*)\"").matcher(head);
            java.util.regex.Matcher f = Pattern.compile("filename=\"([^\"]*)\"").matcher(head);
            java.util.regex.Matcher t = Pattern.compile("(?i)Content-Type: *([^\\r\\n]+)").matcher(head);
            out.add(new Part(n.find() ? n.group(1) : null, f.find() ? f.group(1) : null, t.find() ? t.group(1) : null,
                    sha256(content), content.length));
            at = next;
        }
        return List.copyOf(out);
    }

    /** The content of the first part with this name (for checking a JSON part in spec mode; never recorded). */
    static byte[] partContent(String contentType, byte[] body, String name) {
        java.util.regex.Matcher bm = Pattern.compile("boundary=([^;]+)").matcher(contentType);
        if (!bm.find()) return new byte[0];
        byte[] delimiter = ("--" + bm.group(1)).getBytes(StandardCharsets.ISO_8859_1);
        int at = indexOf(body, delimiter, 0);
        while (at >= 0) {
            int start = at + delimiter.length + 2;
            int headEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), start);
            int next = indexOf(body, delimiter, headEnd < 0 ? start : headEnd);
            if (headEnd < 0 || next < 0) break;
            String head = new String(body, start, headEnd - start, StandardCharsets.UTF_8);
            if (head.contains("name=\"" + name + "\"")) return java.util.Arrays.copyOfRange(body, headEnd + 4, next - 2);
            at = next;
        }
        return new byte[0];
    }

    private static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
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
            String token = newToken(clientId);
            tokens.put(token, Instant.now().plusSeconds(tokenLifetimeSeconds));
            send(ex, 200, "{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\",\"expires_in\":"
                    + tokenLifetimeSeconds + "}", null);
        }
    }

    /** {@code mock-<uuid>}, or with token claims an unsigned test JWT whose signature part is {@code mock-<uuid>}. */
    private String newToken(String clientId) {
        String id = "mock-" + UUID.randomUUID();
        Map<String, String> claims = tokenClaims;
        if (claims.isEmpty()) return id;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", clientId);
        payload.put("exp", Instant.now().plusSeconds(tokenLifetimeSeconds).getEpochSecond());
        payload.putAll(claims);
        return B64URL.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + B64URL.encodeToString(Json.write(payload).getBytes(StandardCharsets.UTF_8)) + "." + id;
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
                            send(ex, 400, error("MOCK-ADMIN-UNKNOWN-MODE", null), null);
                            return;
                        }
                    }
                    send(ex, 200, "{\"mode\":\"" + name + "\"}", null);
                }
                case "/health" -> {
                    if (q.containsKey("busy")) {
                        healthBusy(q.get("code"), q.containsKey("retryAfter")
                                ? Duration.ofSeconds(Long.parseLong(q.get("retryAfter"))) : null);
                    } else {
                        healthFailing(Boolean.parseBoolean(q.getOrDefault("failing", "false")));
                    }
                    send(ex, 200, "{\"healthFailing\":" + healthFailing + ",\"healthBusy\":" + healthBusy + "}", null);
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
                default -> send(ex, 404, error("MOCK-ADMIN-NOT-FOUND", null), null);
            }
        } catch (RuntimeException e) {
            send(ex, 400, error("MOCK-ADMIN-BAD-REQUEST", null), null);
        }
    }

    private String fill(String template, byte[] body) {
        return template.replace("${id}", UUID.randomUUID().toString())
                .replace("${n}", Integer.toString(counter.incrementAndGet()))
                .replace("${sha256}", sha256(body))
                .replace("${size}", Long.toString(body.length));
    }

    /** An error body; without a code (null) only a message. In spec mode the behaviour's shape. */
    private String error(String code, String reference) {
        String c = code == null ? null : SAFE.matcher(code).matches() ? code : "MOCK-ERROR";
        SpecBehaviour b = behaviour;
        if (b != null && spec != null) {
            return Json.write(b.error(c, reference == null ? UUID.randomUUID().toString() : reference));
        }
        if (c == null) return "{\"message\":\"mock answer\"}";
        return Json.write(errorBody(errorCodePointer, c));
    }

    /** {@code {"message": ..., <pointer>: code}}, for example {@code /error/code} → {@code {"error":{"code":...}}}. */
    public static Map<String, Object> errorBody(String pointer, String code) {
        Map<String, Object> top = new LinkedHashMap<>();
        String[] parts = pointer.substring(1).split("/");
        Map<String, Object> cur = top;
        for (int i = 0; i < parts.length - 1; i++) {
            Map<String, Object> next = new LinkedHashMap<>();
            cur.put(parts[i].replace("~1", "/").replace("~0", "~"), next);
            cur = next;
        }
        if (code != null) cur.put(parts[parts.length - 1].replace("~1", "/").replace("~0", "~"), code);
        top.putIfAbsent("message", "mock answer");
        return top;
    }

    /** Where error answers put their code (default {@code /code}). */
    public MockDestination errorCodePointer(String pointer) {
        if (!com.documentplatform.integration.json.internal.Json.isPointer(pointer)) throw new IllegalArgumentException("not a pointer");
        errorCodePointer = pointer;
        return this;
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
        if (d.isZero()) return;
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

    /** For tests that need a set of the spec's operation IDs. */
    public Set<String> specOperations() {
        OpenApiSpec s = spec;
        return s == null ? Set.of() : Set.copyOf(s.operations().stream().map(OpenApiSpec.Operation::id).toList());
    }
}
