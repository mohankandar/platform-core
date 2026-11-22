package com.documentplatform.integration.signin;

import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.integration.config.Limits;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.integration.safety.Installation;
import com.documentplatform.notifications.secret.Secret;
import com.documentplatform.notifications.secret.SecretRef;
import com.documentplatform.notifications.secret.SecretResolver;

import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns the sign-in bundle into request headers, reading secrets for each attempt. Sign-in always goes in headers,
 * never in the address.
 */
public final class SignInHeaders {

    /** The headers, or why sign-in could not be prepared (as an outcome and a code). */
    public record Prepared(List<Map.Entry<String, String>> headers, Outcome failure, IntegrationCode code) {
        static Prepared ok(List<Map.Entry<String, String>> h) {
            return new Prepared(h, null, null);
        }

        static Prepared failed(Outcome o, IntegrationCode c) {
            return new Prepared(List.of(), o, c);
        }

        public boolean ok() {
            return failure == null;
        }

        @Override
        public String toString() {
            return ok() ? "Prepared[" + headers.size() + " header(s)]" : "Prepared[" + failure + " " + code + "]";
        }
    }

    private final Installation installation;
    private final SecretResolver secrets;
    private final ClientCredentialsTokens tokens;

    public SignInHeaders(Installation installation, SecretResolver secrets, ClientCredentialsTokens tokens) {
        this.installation = Objects.requireNonNull(installation, "installation");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
    }

    public Prepared prepare(SignIn signIn, Limits limits, boolean useProxy) {
        return prepare(signIn, limits, useProxy, List.of());
    }

    /**
     * @param requiredClaims claims an OAuth 2.0 access token must carry (it must then be a JWT); checked for presence
     *                       only, from the token's payload, which is read here and kept nowhere
     */
    public Prepared prepare(SignIn signIn, Limits limits, boolean useProxy, List<String> requiredClaims) {
        Prepared p = headers(signIn, limits, useProxy);
        if (!p.ok() || requiredClaims.isEmpty() || !(signIn instanceof SignIn.OAuth2ClientCredentials)) return p;
        String token = p.headers().get(0).getValue().substring("Bearer ".length());
        if (!hasClaims(token, requiredClaims)) {
            return Prepared.failed(Outcome.SIGN_IN_FAILED, IntegrationCode.INT_TOKEN_CLAIMS_MISSING);
        }
        return p;
    }

    /** Whether the JWT's payload has every claim (with a non-empty value). False for a token that is not a JWT. */
    static boolean hasClaims(String token, List<String> claims) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) return false;
        try {
            java.util.Map<String, Object> payload = com.documentplatform.integration.json.Json.object(
                    new String(java.util.Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            for (String c : claims) {
                Object v = payload.get(c);
                if (v == null || (v instanceof String s && s.isBlank())) return false;
            }
            return true;
        } catch (com.documentplatform.integration.json.Json.Invalid | IllegalArgumentException e) {
            return false;
        }
    }

    private Prepared headers(SignIn signIn, Limits limits, boolean useProxy) {
        if (signIn instanceof SignIn.None) {
            if (installation.hosted() || !installation.allowSignInNone()) {
                return Prepared.failed(Outcome.NOT_CONFIGURED, IntegrationCode.INT_SIGN_IN_NONE_NOT_ALLOWED);
            }
            return Prepared.ok(List.of());
        }
        if (signIn instanceof SignIn.Basic b) {
            return secret(b.password(), pw -> header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (b.username() + ":" + pw).getBytes(StandardCharsets.UTF_8))));
        }
        if (signIn instanceof SignIn.ApiKey k) {
            return secret(k.key(), key -> header(k.header(), key));
        }
        if (signIn instanceof SignIn.BearerFixed t) {
            return secret(t.token(), token -> header("Authorization", "Bearer " + token));
        }
        SignIn.OAuth2ClientCredentials o = (SignIn.OAuth2ClientCredentials) signIn;
        ClientCredentialsTokens.Fetch f = tokens.token(o, secrets, limits, useProxy);
        if (!f.ok()) {
            return Prepared.failed(outcomeOf(f.failure()), f.failure());
        }
        return Prepared.ok(header("Authorization", "Bearer " + f.token().reveal()));
    }

    /** The result class of a token failure. */
    public static Outcome outcomeOf(IntegrationCode tokenFailure) {
        return switch (tokenFailure) {
            case INT_SECRET_NOT_FOUND, INT_ADDRESS_NOT_SECURE -> Outcome.NOT_CONFIGURED;
            case INT_SECRET_UNAVAILABLE, INT_TOKEN_UNREACHABLE, INT_ADDRESS_BLOCKED, INT_TLS_FAILED,
                 INT_PROXY_REFUSED -> Outcome.CANT_CONNECT;
            default -> Outcome.SIGN_IN_FAILED;
        };
    }

    /** After the destination refused the sign-in: an OAuth 2.0 token is dropped so the next attempt gets a new one. */
    public void refused(SignIn signIn) {
        if (signIn instanceof SignIn.OAuth2ClientCredentials o) tokens.invalidate(o);
    }

    private interface Use {
        List<Map.Entry<String, String>> with(String secret);
    }

    private Prepared secret(SecretRef ref, Use use) {
        Optional<Secret> s;
        try {
            s = secrets.resolve(ref);
        } catch (RuntimeException storeDown) {
            return Prepared.failed(Outcome.CANT_CONNECT, IntegrationCode.INT_SECRET_UNAVAILABLE);
        }
        if (s.isEmpty()) return Prepared.failed(Outcome.NOT_CONFIGURED, IntegrationCode.INT_SECRET_NOT_FOUND);
        String v = s.get().reveal();
        if (v.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            return Prepared.failed(Outcome.NOT_CONFIGURED, IntegrationCode.INT_VALUE_NOT_ALLOWED);
        }
        return Prepared.ok(use.with(v));
    }

    private static List<Map.Entry<String, String>> header(String name, String value) {
        return List.of(new AbstractMap.SimpleImmutableEntry<>(name, value));
    }
}
