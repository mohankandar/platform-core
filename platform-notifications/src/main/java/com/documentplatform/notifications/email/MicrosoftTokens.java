package com.documentplatform.notifications.email;

import com.documentplatform.notifications.FailureCode;
import com.documentplatform.notifications.secret.Secret;
import com.documentplatform.notifications.secret.SecretResolver;
import com.documentplatform.notifications.transport.HttpTransport;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Access tokens for Microsoft 365 SMTP, from the Microsoft Entra token endpoint with the OAuth 2.0 client-credentials
 * flow (scope {@value MicrosoftOAuth#SCOPE}). A token is kept until {@link #REFRESH_BEFORE} before it expires (or half
 * its lifetime, if that is shorter) and then fetched again on the next send; the app credential is read from the
 * secret store only when a token is fetched.
 *
 * <p>The token, the client secret, the certificate and its private key never reach a log, a result, a status or an
 * exception text: tokens are held as {@link Secret}, failures are {@link FailureCode}s chosen from the HTTP status and
 * Microsoft's error codes only, and the reply text is never kept.
 */
public final class MicrosoftTokens {

    /** A token is replaced this long before it expires. */
    public static final Duration REFRESH_BEFORE = Duration.ofMinutes(5);

    private static final System.Logger LOG = System.getLogger(MicrosoftTokens.class.getName());
    private static final String ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";
    /** Microsoft Entra error codes (AADSTS…) meaning the directory or the app is not known. */
    private static final List<Long> UNKNOWN_APP = List.of(700016L, 90002L, 900023L, 90072L);

    /** The token, or why there is none. */
    public record Fetch(Secret token, FailureCode failure, Duration retryAfter) {
        static Fetch ok(Secret token) {
            return new Fetch(token, null, null);
        }

        static Fetch failed(FailureCode code) {
            return new Fetch(null, code, null);
        }

        public boolean ok() {
            return token != null;
        }
    }

    public enum State {
        NOT_FETCHED_YET("Not fetched yet"), TOKEN_VALID("Token valid"),
        /** The last fetch worked, but no valid token is held now (expired, or dropped after the mail server refused it). */
        NO_TOKEN_HELD("No token held; one is fetched at the next send"), FAILING("Failing");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * How sign-in is doing, for health. Codes and times only.
     *
     * @param failure   the last fetch's failure code, null when the last fetch worked or none was made
     * @param expiresAt when the held token expires, null without one
     */
    public record Status(State state, FailureCode failure, Instant lastFetch, Instant expiresAt) {
    }

    private record Held(Secret token, Instant refreshAt, Instant expiresAt) {
    }

    private record Last(FailureCode failure, Instant at) {
    }

    private final HttpClient http;
    private final Clock clock;
    /** P059: the transport for a sign-in, or null for this class's own client (as before 1.3.0). */
    private final Function<MicrosoftOAuth, HttpTransport> transports;
    private final Duration connectTimeout;
    private final Map<MicrosoftOAuth, Held> held = new ConcurrentHashMap<>();
    private final Map<MicrosoftOAuth, Last> last = new ConcurrentHashMap<>();
    private final Map<MicrosoftOAuth, Object> locks = new ConcurrentHashMap<>();

    public MicrosoftTokens() {
        this(Clock.systemUTC(), MailServer.DEFAULT_CONNECT_TIMEOUT);
    }

    public MicrosoftTokens(Clock clock, Duration connectTimeout) {
        this(clock, connectTimeout, oauth -> null);
    }

    /**
     * P059 (LV-24.3 step 1): token requests of the sign-ins for which {@code transports} answers a transport go
     * through it (in an app: platform-outbound, when the mail server's {@code via-outbound} is on); for the others
     * it answers null, and they use this class's own client as before. A call the transport refuses (address
     * blocked, proxy sign-in refused, TLS failed) is {@link FailureCode#MAIL_OUTBOUND_REFUSED}, not retried; one that
     * finds no connection or no answer in time is {@link FailureCode#MAIL_OAUTH_TOKEN_UNREACHABLE}, retried.
     */
    public MicrosoftTokens(Clock clock, Duration connectTimeout, Function<MicrosoftOAuth, HttpTransport> transports) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transports = Objects.requireNonNull(transports, "transports");
        this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
        this.http = HttpClient.newBuilder()
                .connectTimeout(Objects.requireNonNull(connectTimeout, "connectTimeout"))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** A valid token for this sign-in: the held one, or a new one from the token endpoint. */
    public Fetch token(MicrosoftOAuth oauth, SecretResolver secrets, Duration requestTimeout) {
        Objects.requireNonNull(oauth, "oauth");
        Held h = held.get(oauth);
        if (h != null && clock.instant().isBefore(h.refreshAt())) {
            return Fetch.ok(h.token());
        }
        synchronized (locks.computeIfAbsent(oauth, k -> new Object())) {
            h = held.get(oauth);
            Instant now = clock.instant();
            if (h != null && now.isBefore(h.refreshAt())) {
                return Fetch.ok(h.token()); // another sender fetched it meanwhile
            }
            Fetch f = fetch(oauth, secrets, requestTimeout, now);
            last.put(oauth, new Last(f.failure(), now));
            if (!f.ok()) {
                held.remove(oauth);
                LOG.log(System.Logger.Level.WARNING, "Microsoft 365 mail sign-in for app {0} failed: {1}",
                        oauth.clientId(), f.failure());
            }
            return f;
        }
    }

    /**
     * Drops the held token, so the next send fetches a new one. Called when the mail server refuses the token: after
     * an admin grants a missing permission, only a new token carries it.
     */
    public void invalidate(MicrosoftOAuth oauth) {
        held.remove(oauth);
    }

    public Status status(MicrosoftOAuth oauth) {
        Last l = last.get(oauth);
        if (l == null) {
            return new Status(State.NOT_FETCHED_YET, null, null, null);
        }
        Held h = held.get(oauth);
        if (l.failure() != null) {
            return new Status(State.FAILING, l.failure(), l.at(), null);
        }
        if (h == null || !clock.instant().isBefore(h.expiresAt())) {
            return new Status(State.NO_TOKEN_HELD, null, l.at(), null);
        }
        return new Status(State.TOKEN_VALID, null, l.at(), h.expiresAt());
    }

    private Fetch fetch(MicrosoftOAuth oauth, SecretResolver secrets, Duration timeout, Instant now) {
        Optional<Secret> credential;
        try {
            credential = secrets.resolve(oauth.credential());
        } catch (RuntimeException storeDown) {
            return Fetch.failed(FailureCode.SECRET_UNAVAILABLE);
        }
        if (credential.isEmpty()) {
            return Fetch.failed(FailureCode.SECRET_NOT_FOUND);
        }
        String endpoint = oauth.tokenEndpoint().toString();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", oauth.clientId());
        form.put("scope", MicrosoftOAuth.SCOPE);
        form.put("grant_type", "client_credentials");
        if (oauth.credentialType() == MicrosoftOAuth.CredentialType.SECRET) {
            form.put("client_secret", credential.get().reveal());
        } else {
            try {
                form.put("client_assertion_type", ASSERTION_TYPE);
                form.put("client_assertion", ClientAssertion.create(credential.get().reveal(), oauth.clientId(),
                        endpoint, now));
            } catch (ClientAssertion.Unusable e) {
                return Fetch.failed(FailureCode.MAIL_OAUTH_CREDENTIAL_INVALID);
            } catch (ClientAssertion.Expired e) {
                return Fetch.failed(FailureCode.MAIL_OAUTH_CREDENTIAL_REFUSED);
            }
        }
        HttpTransport transport = transports.apply(oauth);
        if (transport != null) {
            return viaTransport(oauth, transport, encode(form), timeout, now);
        }
        HttpRequest request = HttpRequest.newBuilder(oauth.tokenEndpoint())
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(encode(form), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Fetch.failed(FailureCode.MAIL_OAUTH_TOKEN_UNREACHABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Fetch.failed(FailureCode.MAIL_OAUTH_TOKEN_UNREACHABLE);
        }
        int status = response.statusCode();
        if (status == 200) {
            return accept(oauth, response.body(), now);
        }
        if (status == 429 || status >= 500) {
            return new Fetch(null, FailureCode.MAIL_OAUTH_TOKEN_UNREACHABLE, retryAfter(response));
        }
        if (status == 400 || status == 401 || status == 403) {
            return Fetch.failed(refusal(response.body()));
        }
        return Fetch.failed(FailureCode.MAIL_OAUTH_UNEXPECTED_REPLY);
    }

    /** The same request and the same reading of the answer, through the transport. */
    private Fetch viaTransport(MicrosoftOAuth oauth, HttpTransport transport, String form, Duration timeout,
                               Instant now) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("Accept", "application/json");
        HttpTransport.Reply reply;
        try {
            reply = transport.post(oauth.tokenEndpoint(), headers, form.getBytes(StandardCharsets.UTF_8),
                    connectTimeout, timeout);
        } catch (HttpTransport.Failed f) {
            LOG.log(System.Logger.Level.WARNING, "Microsoft 365 mail sign-in for app {0} through platform.outbound: "
                    + "{1}", oauth.clientId(), f.reason());
            return Fetch.failed(f.kind() == HttpTransport.Failed.Kind.REFUSED ? FailureCode.MAIL_OUTBOUND_REFUSED
                    : FailureCode.MAIL_OAUTH_TOKEN_UNREACHABLE);
        }
        int status = reply.status();
        String body = new String(reply.body(), StandardCharsets.UTF_8);
        if (status == 200) {
            return accept(oauth, body, now);
        }
        if (status == 429 || status >= 500) {
            return new Fetch(null, FailureCode.MAIL_OAUTH_TOKEN_UNREACHABLE, retryAfter(reply.retryAfter()));
        }
        if (status == 400 || status == 401 || status == 403) {
            return Fetch.failed(refusal(body));
        }
        return Fetch.failed(FailureCode.MAIL_OAUTH_UNEXPECTED_REPLY);
    }

    private Fetch accept(MicrosoftOAuth oauth, String body, Instant now) {
        Map<String, Object> reply;
        try {
            reply = TokenJson.object(body);
        } catch (TokenJson.Invalid e) {
            return Fetch.failed(FailureCode.MAIL_OAUTH_UNEXPECTED_REPLY);
        }
        Object token = reply.get("access_token");
        Object type = reply.get("token_type");
        long lifetime = seconds(reply.get("expires_in"));
        if (!(token instanceof String t) || t.isEmpty() || t.length() > 16384 || !(type instanceof String ty)
                || !ty.equalsIgnoreCase("Bearer") || lifetime <= 0) {
            return Fetch.failed(FailureCode.MAIL_OAUTH_UNEXPECTED_REPLY);
        }
        Duration life = Duration.ofSeconds(lifetime);
        Duration margin = REFRESH_BEFORE.compareTo(life.dividedBy(2)) < 0 ? REFRESH_BEFORE : life.dividedBy(2);
        Secret secret = Secret.of(t);
        held.put(oauth, new Held(secret, now.plus(life).minus(margin), now.plus(life)));
        return Fetch.ok(secret);
    }

    /** From Microsoft's {@code error} and {@code error_codes} only; the description text is not read. */
    private static FailureCode refusal(String body) {
        Map<String, Object> reply;
        try {
            reply = TokenJson.object(body);
        } catch (TokenJson.Invalid e) {
            return FailureCode.MAIL_OAUTH_TOKEN_REFUSED;
        }
        if (reply.get("error_codes") instanceof List<?> codes) {
            for (Object c : codes) {
                if (c instanceof Long n && UNKNOWN_APP.contains(n)) {
                    return FailureCode.MAIL_OAUTH_APP_UNKNOWN;
                }
            }
        }
        Object error = reply.get("error");
        if ("invalid_client".equals(error) || "unauthorized_client".equals(error)) {
            return FailureCode.MAIL_OAUTH_CREDENTIAL_REFUSED;
        }
        return FailureCode.MAIL_OAUTH_TOKEN_REFUSED;
    }

    private static long seconds(Object v) {
        if (v instanceof Long n) return n;
        if (v instanceof String s && s.matches("\\d{1,9}")) return Long.parseLong(s);
        return -1;
    }

    private static Duration retryAfter(HttpResponse<?> response) {
        return retryAfter(response.headers().firstValue("Retry-After").orElse(null));
    }

    private static Duration retryAfter(String header) {
        return header != null && header.matches("\\d{1,6}") ? Duration.ofSeconds(Long.parseLong(header)) : null;
    }

    private static String encode(Map<String, String> form) {
        StringBuilder b = new StringBuilder();
        form.forEach((k, v) -> {
            if (!b.isEmpty()) b.append('&');
            b.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return b.toString();
    }
}
