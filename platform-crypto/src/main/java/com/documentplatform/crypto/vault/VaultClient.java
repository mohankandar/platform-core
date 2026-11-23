package com.documentplatform.crypto.vault;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.KeyService;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A small client for HashiCorp Vault: login (token, token file or AppRole), Transit calls for
 * {@link VaultKeyService}, reading KV version 2 secrets ({@link #readSecret}) and the health check.
 *
 * <p>Uses the JDK's {@link HttpClient}, the JVM's trust store and proxy settings. Redirects are not followed (a token
 * is never sent to another address). Every failure is a {@link CryptoException} with a specific code; no message,
 * log line or {@code toString()} holds the token, a secret ID, a secret or anything Vault sent back other than its
 * short error text. Thread-safe.
 */
public final class VaultClient implements AutoCloseable {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private static final System.Logger LOG = System.getLogger(VaultClient.class.getName());
    private static final Pattern MOUNT = VaultAuth.MOUNT;
    private static final Pattern SECRET_PATH = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._-]{0,127}(?:/[A-Za-z0-9][A-Za-z0-9._-]{0,127}){0,15}");

    /** One reply: the HTTP status and the JSON body (empty when there was none). */
    record Reply(int status, Map<String, Object> body) {
        boolean ok() {
            return status == 200 || status == 204;
        }

        /** Vault's first error text, cut short and kept to safe characters ("" if none). */
        String error() {
            Object errors = body.get("errors");
            if (errors instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof String first) {
                return safe(first.replaceAll("\\s+", " ").strip());
            }
            return "";
        }
    }

    private final URI address;
    private final String namespace;
    private final VaultAuth auth;
    private final Duration timeout;
    private final HttpClient http;
    private volatile String token;
    private volatile boolean loggedIn;

    /**
     * @param address   Vault's address, {@code https://vault.internal:8200}
     * @param namespace the Vault Enterprise / HCP namespace, or null
     * @param auth      how to get a token
     * @param timeout   for connecting and for each call
     */
    public VaultClient(URI address, String namespace, VaultAuth auth, Duration timeout) {
        this.address = checkAddress(address);
        this.namespace = namespace == null || namespace.isBlank() ? null : namespace.strip();
        this.auth = Objects.requireNonNull(auth, "auth");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(ProxySelector.getDefault())
                .build();
    }

    public VaultClient(URI address, VaultAuth auth) {
        this(address, null, auth, DEFAULT_TIMEOUT);
    }

    /**
     * A KV version 2 secret: its keys and values. {@link CryptoError#CRYPTO_SECRET_MISSING} if there is none.
     *
     * @param mount where the KV engine is mounted, for example {@code secret}
     * @param path  the secret's path under the mount, for example {@code platform/smtp}
     */
    public Secret readSecret(String mount, String path) {
        String where = kvPath(mount, "data", path);
        Reply r = call("GET", where, null);
        if (r.status() == 404) {
            throw new CryptoException(CryptoError.CRYPTO_SECRET_MISSING, "Vault has no secret " + path + " under "
                    + mount);
        }
        expectOk(r, "reading secret " + path);
        Map<String, Object> data = VaultJson.object(r.body(), "data", "data").orElseThrow(() ->
                new CryptoException(CryptoError.CRYPTO_SECRET_MISSING, "secret " + path + " has no current version"));
        Map<String, String> values = new LinkedHashMap<>();
        data.forEach((k, v) -> {
            if (v instanceof String s) {
                values.put(k, s);
            }
        });
        return new Secret(values);
    }

    /**
     * Writes a new version of a KV version 2 secret. Package-private: only this package's tests write secrets; the
     * running app only reads them (its Vault policy has no KV write).
     */
    void writeSecret(String mount, String path, Map<String, String> values) {
        Objects.requireNonNull(values, "values");
        String where = kvPath(mount, "data", path);
        Reply r = call("POST", where, VaultJson.write(Map.of("data", values)));
        expectOk(r, "writing secret " + path);
    }

    /**
     * Whether Vault answers and is usable: initialised, unsealed, active or a standby that forwards. Needs no token.
     * Never throws.
     */
    public KeyService.Health health(String keyService) {
        Reply r;
        try {
            r = send("GET", "/v1/sys/health?standbyok=true&perfstandbyok=true", null, null);
        } catch (CryptoException e) {
            return new KeyService.Health(KeyService.Health.Status.DOWN, keyService, e.code(), e.getMessage());
        }
        return switch (r.status()) {
            case 200, 429, 472, 473 -> new KeyService.Health(KeyService.Health.Status.UP, keyService, null,
                    where() + " answers");
            case 503 -> new KeyService.Health(KeyService.Health.Status.DOWN, keyService,
                    CryptoError.CRYPTO_KEY_SERVICE_SEALED, where() + " is sealed");
            default -> new KeyService.Health(KeyService.Health.Status.DOWN, keyService,
                    CryptoError.CRYPTO_KEY_SERVICE_ERROR, where() + " health check answered HTTP " + r.status());
        };
    }

    /** Revokes a token this client logged in for (AppRole). Never throws; a given token is left alone. */
    @Override
    public void close() {
        if (loggedIn && token != null) {
            try {
                Reply r = send("POST", "/v1/auth/token/revoke-self", "{}", token);
                if (!r.ok()) {
                    LOG.log(System.Logger.Level.WARNING, "The Vault login token was not revoked (HTTP {0}); it expires"
                            + " with its TTL", r.status());
                }
            } catch (CryptoException e) {
                LOG.log(System.Logger.Level.WARNING, "The Vault login token was not revoked ({0}); it expires with its"
                        + " TTL", e.code());
            }
        }
        token = null;
        loggedIn = false;
    }

    @Override
    public String toString() {
        return "VaultClient[" + where() + ", " + auth + "]";
    }

    // ---- for VaultKeyService ----

    /**
     * An authenticated call. When Vault refuses the token (403) of a login that can be repeated (AppRole, token
     * file), logs in again once and repeats the call: the token may have expired.
     */
    Reply call(String method, String path, String body) {
        String t = token();
        Reply r = send(method, path, body, t);
        if (r.status() == 403 && !(auth instanceof VaultAuth.Token)) {
            synchronized (this) {
                if (Objects.equals(token, t)) {
                    token = null;
                    loggedIn = false;
                }
            }
            r = send(method, path, body, token());
        }
        return r;
    }

    /** Throws the specific code for a reply that is not 200/204. */
    void expectOk(Reply r, String what) {
        if (r.ok()) {
            return;
        }
        throw failure(r, what);
    }

    CryptoException failure(Reply r, String what) {
        int s = r.status();
        String vault = r.error().isEmpty() ? "" : " (" + r.error() + ")";
        CryptoError code = switch (s) {
            case 401, 403 -> CryptoError.CRYPTO_PERMISSION_DENIED;
            case 503 -> CryptoError.CRYPTO_KEY_SERVICE_SEALED;
            default -> CryptoError.CRYPTO_KEY_SERVICE_ERROR;
        };
        return new CryptoException(code, where() + " answered HTTP " + s + " to " + what + vault
                + (s >= 300 && s < 400 ? "; redirects are not followed: use the active node's address" : ""));
    }

    String where() {
        return "Vault at " + address.getScheme() + "://" + address.getHost()
                + (address.getPort() > 0 ? ":" + address.getPort() : "");
    }

    static String checkMount(String mount, String setting) {
        if (mount == null || !MOUNT.matcher(mount).matches()) {
            throw new CryptoException(CryptoError.CRYPTO_SETTINGS_INVALID, setting + " is not a valid Vault mount");
        }
        return mount;
    }

    // ---- inside ----

    private String kvPath(String mount, String kind, String path) {
        checkMount(mount, "the KV mount");
        if (path == null || !SECRET_PATH.matcher(path).matches() || path.contains("..")) {
            throw new CryptoException(CryptoError.CRYPTO_SETTINGS_INVALID, "the secret path is not valid");
        }
        return "/v1/" + mount + "/" + kind + "/" + path;
    }

    private String token() {
        String t = token;
        if (t != null) {
            return t;
        }
        synchronized (this) {
            if (token == null) {
                token = switch (auth) {
                    case VaultAuth.Token given -> given.value();
                    case VaultAuth.TokenFile file -> readTokenFile(file);
                    case VaultAuth.AppRole role -> login(role);
                };
            }
            return token;
        }
    }

    private static String readTokenFile(VaultAuth.TokenFile file) {
        String value;
        try {
            value = Files.readString(file.file(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new CryptoException(CryptoError.CRYPTO_LOGIN_REFUSED, "the Vault token file " + file.file()
                    + " cannot be read (" + e.getClass().getSimpleName() + ")");
        }
        if (value.isEmpty()) {
            throw new CryptoException(CryptoError.CRYPTO_LOGIN_REFUSED, "the Vault token file " + file.file()
                    + " is empty");
        }
        return value;
    }

    private String login(VaultAuth.AppRole role) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("role_id", role.roleId());
        body.put("secret_id", role.secretId());
        Reply r = send("POST", "/v1/auth/" + role.mount() + "/login", VaultJson.write(body), null);
        if (r.status() == 400 || r.status() == 401 || r.status() == 403) {
            throw new CryptoException(CryptoError.CRYPTO_LOGIN_REFUSED, where() + " refused the AppRole login at "
                    + role.mount() + " (HTTP " + r.status() + ")");
        }
        expectOk(r, "the AppRole login");
        String t = VaultJson.object(r.body(), "auth").flatMap(a -> VaultJson.string(a, "client_token"))
                .filter(v -> !v.isBlank()).orElseThrow(() -> new CryptoException(
                        CryptoError.CRYPTO_KEY_SERVICE_ERROR, "the AppRole login reply has no token"));
        loggedIn = true;
        LOG.log(System.Logger.Level.DEBUG, "Logged in to {0} with AppRole at {1}", where(), role.mount());
        return t;
    }

    private Reply send(String method, String path, String body, String withToken) {
        HttpRequest.Builder b = HttpRequest.newBuilder(address.resolve(path.substring(1)))
                .timeout(timeout)
                .header("X-Vault-Request", "true")
                .header("Accept", "application/json");
        if (withToken != null) {
            b.header("X-Vault-Token", withToken);
        }
        if (namespace != null) {
            b.header("X-Vault-Namespace", namespace);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        HttpResponse<java.io.InputStream> response;
        String text;
        try {
            response = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            text = limited(response.body());
        } catch (HttpConnectTimeoutException | ConnectException e) {
            throw unreachable(e);
        } catch (HttpTimeoutException e) {
            throw new CryptoException(CryptoError.CRYPTO_KEY_SERVICE_UNREACHABLE, "no answer from " + where()
                    + " within " + timeout.toMillis() + " ms");
        } catch (SSLException e) {
            throw tls(e);
        } catch (IOException e) {
            throw unreachable(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CryptoException(CryptoError.CRYPTO_KEY_SERVICE_UNREACHABLE, "interrupted while calling "
                    + where());
        }
        Map<String, Object> json;
        if (text == null || text.isBlank()) {
            json = Map.of();
        } else {
            try {
                json = VaultJson.parseObject(text);
            } catch (VaultJson.Malformed e) {
                if (response.statusCode() == 200) {
                    throw new CryptoException(CryptoError.CRYPTO_KEY_SERVICE_ERROR, "the reply from " + where()
                            + " is not JSON: is the address Vault's?");
                }
                json = Map.of();
            }
        }
        return new Reply(response.statusCode(), json);
    }

    private CryptoException unreachable(IOException e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause != cause.getCause()) {
            cause = cause.getCause();
        }
        if (cause instanceof SSLException ssl) {
            return tls(ssl);
        }
        return new CryptoException(CryptoError.CRYPTO_KEY_SERVICE_UNREACHABLE, where() + " does not answer ("
                + cause.getClass().getSimpleName() + ")");
    }

    private CryptoException tls(Throwable e) {
        return new CryptoException(CryptoError.CRYPTO_KEY_SERVICE_TLS, where() + ": " + e.getClass().getSimpleName()
                + "; add Vault's CA certificate to the Java trust store");
    }

    /** The most of a reply read (SEC-48): a Vault answer is a few KiB; anything larger is not Vault's. */
    static final int MAX_REPLY = 1024 * 1024;

    private String limited(java.io.InputStream in) throws IOException {
        try (in) {
            byte[] b = in.readNBytes(MAX_REPLY + 1);
            if (b.length > MAX_REPLY) {
                throw new CryptoException(CryptoError.CRYPTO_KEY_SERVICE_ERROR, "the reply from " + where()
                        + " is larger than " + MAX_REPLY + " bytes: is the address Vault's?");
            }
            return new String(b, StandardCharsets.UTF_8);
        }
    }

    /**
     * Whether {@code address} is plain http to this machine: the only http allowed (P044, SEC-30), so a token, an
     * AppRole secret or an unwrapped data key never crosses a network in clear.
     */
    static boolean localHttp(URI address) {
        String h = address.getHost();
        return "http".equals(address.getScheme()) && h != null
                && (h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]") || h.equals("::1"));
    }

    private static URI checkAddress(URI address) {
        if (address == null) {
            throw new CryptoException(CryptoError.CRYPTO_SETTINGS_INVALID, "the Vault address is missing");
        }
        String scheme = address.getScheme();
        if (scheme == null || !(scheme.equals("https") || scheme.equals("http")) || address.getHost() == null
                || address.getRawUserInfo() != null || address.getRawQuery() != null) {
            throw new CryptoException(CryptoError.CRYPTO_SETTINGS_INVALID,
                    "the Vault address must be http(s)://host[:port]");
        }
        if (scheme.equals("http") && !localHttp(address)) {
            throw new CryptoException(CryptoError.CRYPTO_SETTINGS_INVALID, "the Vault address must be https; plain "
                    + "http only to this machine (localhost, 127.0.0.1, [::1])");
        }
        String s = address.toString();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return URI.create(s + "/");
    }

    static String safe(String v) {
        String s = v == null ? "" : v.replaceAll("[^A-Za-z0-9 ._:,'()/-]", "?");
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }

    /** A KV secret's values. {@code toString()} shows the names only. */
    public static final class Secret {
        private final Map<String, String> values;

        Secret(Map<String, String> values) {
            this.values = Map.copyOf(values);
        }

        /** The value of one key, or null. */
        public String get(String name) {
            return values.get(name);
        }

        public Map<String, String> values() {
            return values;
        }

        @Override
        public String toString() {
            return "Secret" + new java.util.TreeSet<>(values.keySet()) + " (values not shown)";
        }
    }
}
