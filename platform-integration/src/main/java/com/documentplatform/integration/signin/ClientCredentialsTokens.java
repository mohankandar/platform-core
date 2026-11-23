package com.documentplatform.integration.signin;

import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.integration.config.Limits;
import com.documentplatform.integration.config.SignIn.OAuth2ClientCredentials;
import com.documentplatform.integration.http.Outbound;
import com.documentplatform.integration.http.RetryAfter;
import com.documentplatform.integration.json.Json;
import com.documentplatform.notifications.secret.Secret;
import com.documentplatform.notifications.secret.SecretResolver;

import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The generic OAuth 2.0 client-credentials token client: any token address, scope or audience; a client secret (in
 * the form or as HTTP Basic) or a certificate (RFC 7523 client assertion); tokens kept until {@link #REFRESH_BEFORE}
 * before they expire (or half their lifetime, if shorter). Generalised from the notifications module's
 * {@code MicrosoftTokens}; Microsoft is a preset ({@link OAuth2ClientCredentials#microsoft}). Token requests go through
 * {@link Outbound}, so the address guard, proxy and private CA apply to them too.
 *
 * <p>The token, the client secret, the certificate and its key never reach a log, a result or an exception text:
 * tokens are held as {@link Secret}, failures are {@link IntegrationCode}s chosen from the HTTP status and the
 * standard {@code error} field only, and the reply text is never kept.
 */
public final class ClientCredentialsTokens {

    public static final Duration REFRESH_BEFORE = Duration.ofMinutes(5);

    private static final System.Logger LOG = System.getLogger(ClientCredentialsTokens.class.getName());
    private static final String ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";
    private static final int MAX_REPLY = 64 * 1024;
    /** Microsoft Entra error codes (AADSTS…) meaning the directory or the app is not known. */
    private static final List<Long> UNKNOWN_CLIENT = List.of(700016L, 90002L, 900023L, 90072L);

    /** The token, or why there is none. */
    public record Fetch(Secret token, IntegrationCode failure, Duration retryAfter) {
        static Fetch ok(Secret token) {
            return new Fetch(token, null, null);
        }

        static Fetch failed(IntegrationCode code) {
            return new Fetch(null, code, null);
        }

        public boolean ok() {
            return token != null;
        }
    }

    public enum State { NOT_FETCHED_YET, TOKEN_VALID, NO_TOKEN_HELD, FAILING }

    /** For health: codes and times only. */
    public record Status(State state, IntegrationCode failure, Instant lastFetch, Instant expiresAt) {
    }

    /** {@code certificate}: the fingerprint of the client certificate in use when it was fetched, or null. */
    private record Held(Secret token, Instant refreshAt, Instant expiresAt, String certificate) {
        boolean usableAt(Instant now, String inUse) {
            return now.isBefore(refreshAt) && Objects.equals(certificate, inUse);
        }
    }

    private record Last(IntegrationCode failure, Instant at) {
    }

    private final Outbound outbound;
    private final Clock clock;
    private final Map<OAuth2ClientCredentials, Held> held = new ConcurrentHashMap<>();
    private final Map<OAuth2ClientCredentials, Last> last = new ConcurrentHashMap<>();
    private final Map<OAuth2ClientCredentials, Object> locks = new ConcurrentHashMap<>();

    public ClientCredentialsTokens(Outbound outbound, Clock clock) {
        this.outbound = Objects.requireNonNull(outbound, "outbound");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** A valid token: the held one, or a new one from the token address. */
    public Fetch token(OAuth2ClientCredentials client, SecretResolver secrets, Limits limits, boolean useProxy) {
        return token(client, secrets, limits, useProxy, null);
    }

    /** As above; the token request presents {@code certificate} (mutual TLS) when it is not null. */
    public Fetch token(OAuth2ClientCredentials client, SecretResolver secrets, Limits limits, boolean useProxy,
                       com.documentplatform.integration.credential.ClientCertificate certificate) {
        return token(client, secrets, limits, useProxy, certificate,
                certificate == null ? null : certificate.fingerprint());
    }

    /**
     * As above. {@code certificateInUse} is the fingerprint of the integration's client certificate in use (mutual
     * TLS; null without one), whether or not the token request presents it. A held token fetched while another
     * certificate was in use is dropped and a new one fetched: switching the certificate (by the admin, at its set
     * time, or on another instance of the app) drops the held token at once.
     */
    public Fetch token(OAuth2ClientCredentials client, SecretResolver secrets, Limits limits, boolean useProxy,
                       com.documentplatform.integration.credential.ClientCertificate certificate,
                       String certificateInUse) {
        Objects.requireNonNull(client, "client");
        Held h = held.get(client);
        if (h != null && h.usableAt(clock.instant(), certificateInUse)) {
            return Fetch.ok(h.token());
        }
        synchronized (locks.computeIfAbsent(client, k -> new Object())) {
            h = held.get(client);
            Instant now = clock.instant();
            if (h != null && h.usableAt(now, certificateInUse)) {
                return Fetch.ok(h.token());
            }
            if (h != null && !Objects.equals(h.certificate(), certificateInUse)) {
                held.remove(client);
                LOG.log(System.Logger.Level.INFO, "Token for client {0} dropped: the client certificate changed",
                        client.clientId());
            }
            Fetch f = fetch(client, secrets, limits, useProxy, now, certificate, certificateInUse);
            last.put(client, new Last(f.failure(), now));
            if (!f.ok()) {
                held.remove(client);
                LOG.log(System.Logger.Level.WARNING, "Token request for client {0} failed: {1}", client.clientId(),
                        f.failure());
            }
            return f;
        }
    }

    /** Drops the held token, so the next attempt fetches a new one (after the destination refused it). */
    public void invalidate(OAuth2ClientCredentials client) {
        held.remove(client);
    }

    public Status status(OAuth2ClientCredentials client) {
        Last l = last.get(client);
        if (l == null) return new Status(State.NOT_FETCHED_YET, null, null, null);
        if (l.failure() != null) return new Status(State.FAILING, l.failure(), l.at(), null);
        Held h = held.get(client);
        if (h == null || !clock.instant().isBefore(h.expiresAt())) return new Status(State.NO_TOKEN_HELD, null, l.at(), null);
        return new Status(State.TOKEN_VALID, null, l.at(), h.expiresAt());
    }

    private Fetch fetch(OAuth2ClientCredentials client, SecretResolver secrets, Limits limits, boolean useProxy,
                        Instant now, com.documentplatform.integration.credential.ClientCertificate certificate,
                        String certificateInUse) {
        Optional<Secret> credential;
        try {
            credential = secrets.resolve(client.credential());
        } catch (RuntimeException storeDown) {
            return Fetch.failed(IntegrationCode.INT_SECRET_UNAVAILABLE);
        }
        if (credential.isEmpty()) {
            return Fetch.failed(IntegrationCode.INT_SECRET_NOT_FOUND);
        }
        String address = client.tokenAddress().toString();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        headers.add(new AbstractMap.SimpleImmutableEntry<>("Content-Type", "application/x-www-form-urlencoded"));
        headers.add(new AbstractMap.SimpleImmutableEntry<>("Accept", "application/json"));
        if (client.credentialType() == OAuth2ClientCredentials.CredentialType.SECRET) {
            if (client.clientAuth() == OAuth2ClientCredentials.ClientAuth.BASIC) {
                String pair = urlForm(client.clientId()) + ":" + urlForm(credential.get().reveal());
                headers.add(new AbstractMap.SimpleImmutableEntry<>("Authorization",
                        "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8))));
            } else {
                form.put("client_id", client.clientId());
                form.put("client_secret", credential.get().reveal());
            }
        } else {
            form.put("client_id", client.clientId());
            try {
                form.put("client_assertion_type", ASSERTION_TYPE);
                form.put("client_assertion", ClientAssertion.create(credential.get().reveal(), client.clientId(),
                        address, now));
            } catch (ClientAssertion.Unusable e) {
                return Fetch.failed(IntegrationCode.INT_TOKEN_CREDENTIAL_INVALID);
            } catch (ClientAssertion.Expired e) {
                return Fetch.failed(IntegrationCode.INT_TOKEN_CREDENTIAL_EXPIRED);
            }
        }
        if (client.scope() != null) form.put("scope", client.scope());
        if (client.audience() != null) form.put("audience", client.audience());

        Outbound.Answer answer;
        try {
            answer = outbound.send(new Outbound.Call("POST", client.tokenAddress(), headers,
                            HttpRequest.BodyPublishers.ofString(encode(form), StandardCharsets.UTF_8),
                            limits.readTimeout()),
                    limits.connectTimeout(), MAX_REPLY, useProxy, certificate);
        } catch (Outbound.Failure f) {
            // Blocked addresses and plain http keep their own codes; anything else is "unreachable".
            IntegrationCode c = f.code();
            return Fetch.failed(c == IntegrationCode.INT_ADDRESS_BLOCKED || c == IntegrationCode.INT_ADDRESS_NOT_SECURE
                    || c == IntegrationCode.INT_TLS_FAILED || c == IntegrationCode.INT_PROXY_REFUSED
                    || c == IntegrationCode.INT_PROXY_SIGN_IN_REFUSED
                    || c == IntegrationCode.INT_SECRET_NOT_FOUND || c == IntegrationCode.INT_SECRET_UNAVAILABLE || c == IntegrationCode.INT_CREDENTIAL_REFUSED
                    ? c : IntegrationCode.INT_TOKEN_UNREACHABLE);
        }
        int status = answer.status();
        String body = new String(answer.body(), StandardCharsets.UTF_8);
        if (status == 200 && !answer.tooLarge()) {
            return accept(client, body, now, certificateInUse);
        }
        if (status == 429 || status >= 500) {
            return new Fetch(null, IntegrationCode.INT_TOKEN_UNREACHABLE,
                    RetryAfter.parse(answer.header("Retry-After").orElse(null), now));
        }
        if (status == 400 || status == 401 || status == 403) {
            return Fetch.failed(refusal(body));
        }
        return Fetch.failed(IntegrationCode.INT_TOKEN_UNEXPECTED_REPLY);
    }

    private Fetch accept(OAuth2ClientCredentials client, String body, Instant now, String certificateInUse) {
        Map<String, Object> reply;
        try {
            reply = Json.object(body);
        } catch (Json.Invalid e) {
            return Fetch.failed(IntegrationCode.INT_TOKEN_UNEXPECTED_REPLY);
        }
        Object token = reply.get("access_token");
        Object type = reply.get("token_type");
        long lifetime = seconds(reply.get("expires_in"));
        if (!(token instanceof String t) || t.isEmpty() || t.length() > 16384
                || t.chars().anyMatch(c -> c <= 0x20 || c >= 0x7f)
                || !(type instanceof String ty) || !ty.equalsIgnoreCase("Bearer") || lifetime <= 0) {
            return Fetch.failed(IntegrationCode.INT_TOKEN_UNEXPECTED_REPLY);
        }
        Duration life = Duration.ofSeconds(lifetime);
        Duration margin = REFRESH_BEFORE.compareTo(life.dividedBy(2)) < 0 ? REFRESH_BEFORE : life.dividedBy(2);
        Secret secret = Secret.of(t);
        held.put(client, new Held(secret, now.plus(life).minus(margin), now.plus(life), certificateInUse));
        return Fetch.ok(secret);
    }

    /** From the standard {@code error} field (and Microsoft's {@code error_codes}); the description is not read. */
    private static IntegrationCode refusal(String body) {
        Map<String, Object> reply;
        try {
            reply = Json.object(body);
        } catch (Json.Invalid e) {
            return IntegrationCode.INT_TOKEN_REFUSED;
        }
        if (reply.get("error_codes") instanceof List<?> codes) {
            for (Object c : codes) {
                if (c instanceof Long n && UNKNOWN_CLIENT.contains(n)) return IntegrationCode.INT_TOKEN_CLIENT_UNKNOWN;
            }
        }
        Object error = reply.get("error");
        if ("invalid_client".equals(error) || "unauthorized_client".equals(error)) {
            return IntegrationCode.INT_TOKEN_CREDENTIAL_REFUSED;
        }
        return IntegrationCode.INT_TOKEN_REFUSED;
    }

    private static long seconds(Object v) {
        if (v instanceof Long n) return n;
        if (v instanceof String s && s.matches("\\d{1,9}")) return Long.parseLong(s);
        return -1;
    }

    private static String urlForm(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String encode(Map<String, String> form) {
        StringBuilder b = new StringBuilder();
        form.forEach((k, v) -> {
            if (!b.isEmpty()) b.append('&');
            b.append(urlForm(k)).append('=').append(urlForm(v));
        });
        return b.toString();
    }
}
