package com.documentplatform.licensing.issue;

import com.documentplatform.licensing.Ed25519;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Signs with a HashiCorp Vault Transit key of type {@code ed25519}. The private key never leaves Vault: this class
 * sends the message to {@code POST /v1/<mount>/sign/<name>} and gets the signature back. Every signature is in Vault's
 * audit log; the request IDs are handed to our run log ({@link #auditReferences()}).
 *
 * <p><b>Key IDs</b> are the Transit key name and version, {@code lic-2026:1}, so a Vault key rotation (a new version)
 * is a new key ID and licences signed with an older version keep verifying. A bare name ({@code lic-2026}) means its
 * latest version. A key that allows export or plaintext backup is refused ({@link IssueError#ISSUE_KEY_EXPORTABLE}).
 * Every signature is checked against the key's public key before it is used.
 *
 * <p><b>Credentials:</b> a token from {@code VAULT_TOKEN}, or a login with Vault's userpass method (the password is
 * asked on the console without echo, and the token it gives is revoked when the run ends). The token is never
 * logged, printed, written to a file or put in an exception message.
 *
 * <p>Uses the JDK's {@link HttpClient}, the JVM's trust store and proxy settings; redirects are not followed (a
 * token is never sent to another address).
 */
public final class VaultTransitSigner implements Signer {

    public static final String DEFAULT_MOUNT = "transit";
    public static final String DEFAULT_LOGIN_MOUNT = "userpass";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    private static final System.Logger LOG = System.getLogger(VaultTransitSigner.class.getName());
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern KEY_ID = Pattern.compile("([a-z0-9][a-z0-9._-]{0,63})(?::([1-9][0-9]{0,8}))?");
    private static final Pattern MOUNT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}");
    private static final Pattern USER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._@-]{0,127}");
    private static final Pattern SIGNATURE = Pattern.compile("vault:v([1-9][0-9]{0,8}):([A-Za-z0-9+/=]+)");
    /** DER prefix of an X.509 SubjectPublicKeyInfo for a 32-byte Ed25519 key. */
    private static final byte[] X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    /** Reads a password from the person running the command, without echo. */
    @FunctionalInterface
    public interface PasswordPrompt {
        /** @return the password, or null if none can be read (no console) */
        char[] read(String user);
    }

    /** How the signer gets a Vault token. */
    public sealed interface Credentials permits Token, Userpass {
    }

    /** A token given to the run (normally from {@code VAULT_TOKEN}). */
    public record Token(String value) implements Credentials {
        public Token {
            if (value == null || value.isBlank()) {
                throw new IssueException(IssueError.ISSUE_VAULT_NO_TOKEN, "");
            }
        }

        @Override
        public String toString() {
            return "Token[(not shown)]";
        }
    }

    /** A login with Vault's userpass method as a named user (the owner or the backup signer). */
    public record Userpass(String mount, String user, PasswordPrompt prompt) implements Credentials {
        public Userpass {
            Objects.requireNonNull(prompt, "prompt");
            if (mount == null || !MOUNT.matcher(mount).matches()) {
                throw new IssueException(IssueError.ISSUE_BAD_OPTION, "The Vault login mount is not a valid path.");
            }
            if (user == null || !USER.matcher(user).matches()) {
                throw new IssueException(IssueError.ISSUE_BAD_OPTION,
                        "--vault-user must be 1 to 128 of A-Z a-z 0-9 . _ @ -.");
            }
        }
    }

    /** What the signer knows about one Transit key, read with {@code GET /v1/<mount>/keys/<name>}. */
    private record KeyInfo(String type, boolean exportable, boolean plaintextBackup, int latest,
                           Map<Integer, PublicKey> publicKeys) {
    }

    private record Reply(int status, Map<String, Object> body) {
    }

    private final URI address;
    private final String mount;
    private final String namespace;
    private final Credentials credentials;
    private final Duration timeout;
    private final HttpClient http;
    private final Map<String, KeyInfo> keys = new HashMap<>();
    private final List<String> requestIds = new ArrayList<>();
    private String token;
    private boolean loggedIn;

    /**
     * @param address   Vault's address, for example {@code https://vault.internal:8200}
     * @param mount     the Transit mount path, normally {@value #DEFAULT_MOUNT}
     * @param namespace the Vault Enterprise / HCP namespace, or null
     */
    public VaultTransitSigner(URI address, String mount, String namespace, Credentials credentials, Duration timeout) {
        this.address = checkAddress(address);
        if (mount == null || !MOUNT.matcher(mount).matches() || mount.contains("..")) {
            throw new IssueException(IssueError.ISSUE_BAD_OPTION, "--vault-mount is not a valid Vault mount path.");
        }
        this.mount = strip(mount);
        this.namespace = namespace == null || namespace.isBlank() ? null : namespace.strip();
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(ProxySelector.getDefault())
                .build();
    }

    /**
     * The signer the issuing command uses, from its options and the environment:
     * {@code --vault-addr} (else {@code VAULT_ADDR}), {@code --vault-mount} (default {@value #DEFAULT_MOUNT}),
     * {@code --vault-login token|userpass} (default token: {@code VAULT_TOKEN}), {@code --vault-user} (userpass),
     * {@code --vault-login-mount} (default {@value #DEFAULT_LOGIN_MOUNT}) and {@code VAULT_NAMESPACE}.
     */
    public static VaultTransitSigner from(Map<String, String> options, Map<String, String> env, PasswordPrompt prompt) {
        String addr = options.getOrDefault("--vault-addr", env.get("VAULT_ADDR"));
        if (addr == null || addr.isBlank()) {
            throw new IssueException(IssueError.ISSUE_MISSING_OPTION,
                    "Vault's address is required: --vault-addr or the VAULT_ADDR environment variable.");
        }
        URI uri;
        try {
            uri = URI.create(addr.strip());
        } catch (IllegalArgumentException e) {
            throw new IssueException(IssueError.ISSUE_BAD_OPTION, "The Vault address is not a valid URL.");
        }
        String login = options.getOrDefault("--vault-login", "token");
        Credentials credentials = switch (login) {
            case "token" -> {
                if (options.containsKey("--vault-user")) {
                    throw new IssueException(IssueError.ISSUE_BAD_OPTION, "--vault-user is used with --vault-login userpass.");
                }
                String t = env.get("VAULT_TOKEN");
                if (t == null || t.isBlank()) {
                    throw new IssueException(IssueError.ISSUE_VAULT_NO_TOKEN,
                            "Set VAULT_TOKEN for this run, or log in with --vault-login userpass --vault-user NAME.");
                }
                yield new Token(t.strip());
            }
            case "userpass" -> {
                String user = options.get("--vault-user");
                if (user == null || user.isBlank()) {
                    throw new IssueException(IssueError.ISSUE_MISSING_OPTION, "--vault-user is required with"
                            + " --vault-login userpass.");
                }
                yield new Userpass(options.getOrDefault("--vault-login-mount", DEFAULT_LOGIN_MOUNT), user, prompt);
            }
            default -> throw new IssueException(IssueError.ISSUE_BAD_OPTION, "--vault-login must be token or userpass.");
        };
        return new VaultTransitSigner(uri, options.getOrDefault("--vault-mount", DEFAULT_MOUNT),
                env.get("VAULT_NAMESPACE"), credentials, DEFAULT_TIMEOUT);
    }

    /** Reads a password on the console without echo; null without a console. */
    public static PasswordPrompt consolePrompt() {
        return user -> {
            java.io.Console console = System.console();
            return console == null ? null : console.readPassword("Vault password for %s: ", user);
        };
    }

    @Override
    public String describe() {
        return "vault-transit";
    }

    @Override
    public List<String> auditReferences() {
        return List.copyOf(requestIds);
    }

    @Override
    public String resolveKeyId(String requested) {
        Matcher m = parse(requested);
        String name = m.group(1);
        KeyInfo info = usable(name);
        if (m.group(2) == null) {
            return name + ":" + info.latest();
        }
        int version = Integer.parseInt(m.group(2));
        version(info, name, version);
        return requested;
    }

    @Override
    public byte[] sign(String keyId, byte[] message) {
        String resolved = resolveKeyId(keyId);
        Matcher m = parse(resolved);
        String name = m.group(1);
        int version = Integer.parseInt(m.group(2));
        PublicKey publicKey = version(usable(name), name, version);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input", Base64.getEncoder().encodeToString(message));
        body.put("key_version", version);
        Reply r = call("POST", "/v1/" + mount + "/sign/" + name, VaultJson.write(body), true);
        expectOk(r, "signing with key " + resolved);
        String value = VaultJson.object(r.body(), "data").flatMap(d -> VaultJson.string(d, "signature"))
                .orElseThrow(() -> new IssueException(IssueError.ISSUE_VAULT_ERROR,
                        "The sign reply for key " + resolved + " has no signature."));
        Matcher sig = SIGNATURE.matcher(value);
        if (!sig.matches() || Integer.parseInt(sig.group(1)) != version) {
            throw new IssueException(IssueError.ISSUE_VAULT_ERROR, "The sign reply for key " + resolved
                    + " is not a signature by version " + version + ".");
        }
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(sig.group(2));
        } catch (IllegalArgumentException e) {
            throw new IssueException(IssueError.ISSUE_VAULT_ERROR, "The sign reply for key " + resolved
                    + " is not Base64.");
        }
        if (signature.length != Ed25519.SIGNATURE_LENGTH || !Ed25519.verify(publicKey, message, signature)) {
            throw new IssueException(IssueError.ISSUE_SIGNATURE_MISMATCH, "Key " + resolved + ".");
        }
        VaultJson.string(r.body(), "request_id").filter(id -> id.matches("[A-Za-z0-9-]{1,64}"))
                .ifPresent(requestIds::add);
        LOG.log(System.Logger.Level.INFO, "Signed with Vault Transit key {0}", resolved);
        return signature;
    }

    @Override
    public Map<String, PublicKey> publicKeys(String keyName) {
        if (keyName == null || !NAME.matcher(keyName).matches()) {
            throw new IssueException(IssueError.ISSUE_BAD_OPTION, "A Vault key name is 1 to 64 of a-z 0-9 . _ -"
                    + " (no version).");
        }
        KeyInfo info = usable(keyName);
        Map<String, PublicKey> out = new TreeMap<>();
        info.publicKeys().forEach((v, k) -> out.put(keyName + ":" + v, k));
        return out;
    }

    /** Revokes a token this signer logged in for. Never throws; a token from VAULT_TOKEN is left alone. */
    @Override
    public void close() {
        if (loggedIn && token != null) {
            try {
                Reply r = call("POST", "/v1/auth/token/revoke-self", "{}", true);
                if (r.status() != 204 && r.status() != 200) {
                    LOG.log(System.Logger.Level.WARNING, "The Vault login token was not revoked (HTTP {0}); it"
                            + " expires with its TTL", r.status());
                }
            } catch (IssueException e) {
                LOG.log(System.Logger.Level.WARNING, "The Vault login token was not revoked ({0}); it expires with"
                        + " its TTL", e.code());
            }
        }
        token = null;
        loggedIn = false;
    }

    @Override
    public String toString() {
        return "VaultTransitSigner[" + address + ", mount " + mount + "]";
    }

    private static Matcher parse(String keyId) {
        Matcher m = keyId == null ? null : KEY_ID.matcher(keyId);
        if (m == null || !m.matches()) {
            throw new IssueException(IssueError.ISSUE_BAD_OPTION, "A Vault key ID is NAME or NAME:VERSION, for example"
                    + " lic-2026:1.");
        }
        return m;
    }

    private KeyInfo usable(String name) {
        KeyInfo info = keyInfo(name);
        if (!"ed25519".equals(info.type())) {
            throw new IssueException(IssueError.ISSUE_KEY_INVALID, "Vault key " + name + " is of type "
                    + safe(info.type()) + "; licences need type ed25519.");
        }
        if (info.exportable() || info.plaintextBackup()) {
            throw new IssueException(IssueError.ISSUE_KEY_EXPORTABLE, "Vault key " + name + ": exportable="
                    + info.exportable() + ", allow_plaintext_backup=" + info.plaintextBackup()
                    + ". Create a new key with both false; they cannot be switched back off.");
        }
        return info;
    }

    private static PublicKey version(KeyInfo info, String name, int version) {
        PublicKey key = info.publicKeys().get(version);
        if (key == null) {
            throw new IssueException(IssueError.ISSUE_KEY_NOT_FOUND, "Vault key " + name + " has no version " + version
                    + " (latest is " + info.latest() + ").");
        }
        return key;
    }

    private KeyInfo keyInfo(String name) {
        KeyInfo cached = keys.get(name);
        if (cached != null) {
            return cached;
        }
        Reply r = call("GET", "/v1/" + mount + "/keys/" + name, null, true);
        if (r.status() == 404) {
            throw new IssueException(IssueError.ISSUE_KEY_NOT_FOUND, "Vault has no Transit key " + name + " under mount "
                    + mount + ".");
        }
        expectOk(r, "reading key " + name);
        Map<String, Object> data = VaultJson.object(r.body(), "data").orElseThrow(() ->
                new IssueException(IssueError.ISSUE_VAULT_ERROR, "The reply for key " + name + " has no data."));
        String type = VaultJson.string(data, "type").orElse("(unknown)");
        int latest = VaultJson.integer(data, "latest_version").map(Long::intValue).orElse(0);
        Map<Integer, PublicKey> publicKeys = new TreeMap<>();
        if ("ed25519".equals(type)) {
            Map<String, Object> versions = VaultJson.object(data, "keys").orElse(Map.of());
            for (Map.Entry<String, Object> e : versions.entrySet()) {
                if (!e.getKey().matches("[1-9][0-9]{0,8}") || !(e.getValue() instanceof Map<?, ?>)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> v = (Map<String, Object>) e.getValue();
                String raw = VaultJson.string(v, "public_key").orElse(null);
                if (raw != null) {
                    publicKeys.put(Integer.parseInt(e.getKey()), publicKey(raw, name, e.getKey()));
                }
            }
        }
        KeyInfo info = new KeyInfo(type, VaultJson.bool(data, "exportable"),
                VaultJson.bool(data, "allow_plaintext_backup"), latest, publicKeys);
        keys.put(name, info);
        return info;
    }

    /** Vault gives the raw 32-byte Ed25519 public key; apps use its X.509 form. */
    static PublicKey publicKey(String rawBase64, String name, String version) {
        try {
            byte[] raw = Base64.getDecoder().decode(rawBase64);
            if (raw.length != 32) {
                throw new IllegalArgumentException();
            }
            byte[] der = Arrays.copyOf(X509_PREFIX, X509_PREFIX.length + 32);
            System.arraycopy(raw, 0, der, X509_PREFIX.length, 32);
            return KeyFactory.getInstance(Ed25519.ALGORITHM).generatePublic(new X509EncodedKeySpec(der));
        } catch (IllegalArgumentException | java.security.GeneralSecurityException e) {
            throw new IssueException(IssueError.ISSUE_KEY_INVALID, "Vault key " + name + " version " + version
                    + " has no valid Ed25519 public key.");
        }
    }

    private void expectOk(Reply r, String what) {
        int s = r.status();
        if (s == 200 || s == 204) {
            return;
        }
        throw new IssueException(switch (s) {
            case 401, 403 -> IssueError.ISSUE_VAULT_PERMISSION_DENIED;
            case 503 -> IssueError.ISSUE_VAULT_SEALED;
            default -> IssueError.ISSUE_VAULT_ERROR;
        }, "Vault answered HTTP " + s + " to " + what + vaultError(r) + (s >= 300 && s < 400
                ? " (a redirect, which is not followed: use the active node's address)" : "") + ".");
    }

    /** Vault's own error text, cut short and kept to safe characters; it never holds the token. */
    private static String vaultError(Reply r) {
        Object errors = r.body().get("errors");
        if (errors instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof String first) {
            return " (" + safe(first.replaceAll("\\s+", " ").strip()) + ")";
        }
        return "";
    }

    private static String safe(String v) {
        String s = v == null ? "" : v.replaceAll("[^A-Za-z0-9 ._:,'()/-]", "?");
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }

    private String token() {
        if (token != null) {
            return token;
        }
        switch (credentials) {
            case Token t -> token = t.value();
            case Userpass u -> login(u);
        }
        return token;
    }

    private void login(Userpass u) {
        char[] password = u.prompt().read(u.user());
        if (password == null || password.length == 0) {
            throw new IssueException(IssueError.ISSUE_VAULT_NO_TOKEN, "No password could be read for Vault user "
                    + u.user() + " (no console?).");
        }
        String body;
        try {
            body = VaultJson.write(Map.of("password", new String(password)));
        } finally {
            Arrays.fill(password, '\0');
        }
        Reply r = call("POST", "/v1/auth/" + strip(u.mount()) + "/login/"
                + URLEncoder.encode(u.user(), StandardCharsets.UTF_8), body, false);
        if (r.status() == 400 || r.status() == 401 || r.status() == 403) {
            throw new IssueException(IssueError.ISSUE_VAULT_LOGIN_REFUSED, "User " + u.user() + " (HTTP "
                    + r.status() + ").");
        }
        expectOk(r, "the login of user " + u.user());
        Map<String, Object> auth = VaultJson.object(r.body(), "auth").orElse(Map.of());
        if (auth.get("mfa_requirement") != null) {
            throw new IssueException(IssueError.ISSUE_VAULT_LOGIN_REFUSED, "User " + u.user() + " needs login MFA,"
                    + " which this command does not complete: log in with the vault CLI and set VAULT_TOKEN.");
        }
        token = VaultJson.string(auth, "client_token").filter(t -> !t.isBlank()).orElseThrow(() ->
                new IssueException(IssueError.ISSUE_VAULT_ERROR, "The login reply for user " + u.user()
                        + " has no token."));
        loggedIn = true;
        LOG.log(System.Logger.Level.INFO, "Logged in to Vault as {0}", u.user());
    }

    private Reply call(String method, String path, String body, boolean authenticated) {
        HttpRequest.Builder b = HttpRequest.newBuilder(address.resolve(path))
                .timeout(timeout)
                .header("X-Vault-Request", "true")
                .header("Accept", "application/json");
        if (authenticated) {
            b.header("X-Vault-Token", token());
        }
        if (namespace != null) {
            b.header("X-Vault-Namespace", namespace);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        HttpResponse<String> response;
        try {
            response = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpConnectTimeoutException | ConnectException e) {
            throw unreachable(e);
        } catch (HttpTimeoutException e) {
            throw new IssueException(IssueError.ISSUE_VAULT_UNREACHABLE, "No answer from " + where() + " within "
                    + timeout.toSeconds() + " s.");
        } catch (SSLException e) {
            throw new IssueException(IssueError.ISSUE_VAULT_TLS, where() + ": " + e.getClass().getSimpleName()
                    + ". Add Vault's CA certificate to the Java trust store this command runs with.");
        } catch (IOException e) {
            throw unreachable(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IssueException(IssueError.ISSUE_VAULT_UNREACHABLE, "Interrupted while calling " + where() + ".");
        }
        Map<String, Object> json;
        String text = response.body();
        if (text == null || text.isBlank()) {
            json = Map.of();
        } else {
            try {
                json = VaultJson.parseObject(text);
            } catch (VaultJson.Malformed e) {
                if (response.statusCode() == 200) {
                    throw new IssueException(IssueError.ISSUE_VAULT_ERROR, "The reply from " + where()
                            + " is not JSON: is the address Vault's?");
                }
                json = Map.of();
            }
        }
        return new Reply(response.statusCode(), json);
    }

    private IssueException unreachable(IOException e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause != cause.getCause()) {
            cause = cause.getCause();
        }
        if (cause instanceof SSLException) {
            return new IssueException(IssueError.ISSUE_VAULT_TLS, where() + ": " + cause.getClass().getSimpleName()
                    + ". Add Vault's CA certificate to the Java trust store this command runs with.");
        }
        return new IssueException(IssueError.ISSUE_VAULT_UNREACHABLE, where() + " (" + cause.getClass().getSimpleName()
                + ").");
    }

    private String where() {
        return "Vault at " + address.getScheme() + "://" + address.getHost() + (address.getPort() > 0
                ? ":" + address.getPort() : "");
    }

    private static URI checkAddress(URI address) {
        Objects.requireNonNull(address, "address");
        String scheme = address.getScheme();
        if (scheme == null || !(scheme.equals("https") || scheme.equals("http")) || address.getHost() == null
                || address.getRawUserInfo() != null || address.getRawQuery() != null) {
            throw new IssueException(IssueError.ISSUE_BAD_OPTION, "The Vault address must be http(s)://host[:port].");
        }
        String s = address.toString();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return URI.create(s + "/");
    }

    private static String strip(String path) {
        String p = path.strip();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}
