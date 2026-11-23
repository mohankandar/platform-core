package com.documentplatform.identity.push;

import java.net.URI;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What the app's server set-up has already verified about a push-API caller: a token (its signature and expiry
 * checked by the app's JWT decoder) and/or a client certificate (checked by the app's TLS layer against the
 * customer's CA). Never logged: {@link #toString()} names only what is present.
 *
 * @param issuer      the token's verified issuer, exactly as in the token; null without a token
 * @param audiences   the token's audiences; empty without a token
 * @param claims      the token's claims; empty without a token
 * @param certificate the verified client certificate; null without one
 */
public record PushCallerInput(URI issuer, List<String> audiences, Map<String, Object> claims,
                              X509Certificate certificate) {

    public PushCallerInput {
        audiences = audiences == null ? List.of() : audiences.stream().filter(Objects::nonNull).toList();
        claims = claims == null ? Map.of() : Map.copyOf(claims);
        if (issuer == null && certificate == null) {
            throw new IllegalArgumentException("a verified token or a verified client certificate is needed");
        }
    }

    /** A verified token. */
    public static PushCallerInput token(URI issuer, Collection<String> audiences, Map<String, Object> claims) {
        return new PushCallerInput(Objects.requireNonNull(issuer, "issuer"), List.copyOf(audiences), claims, null);
    }

    /** A verified client certificate, without a token. */
    public static PushCallerInput certificate(X509Certificate certificate) {
        return new PushCallerInput(null, List.of(), Map.of(), Objects.requireNonNull(certificate, "certificate"));
    }

    /** A verified token on a connection with a verified client certificate. */
    public static PushCallerInput tokenAndCertificate(URI issuer, Collection<String> audiences,
                                                      Map<String, Object> claims, X509Certificate certificate) {
        return new PushCallerInput(Objects.requireNonNull(issuer, "issuer"), List.copyOf(audiences), claims,
                Objects.requireNonNull(certificate, "certificate"));
    }

    public boolean hasToken() {
        return issuer != null;
    }

    public boolean hasCertificate() {
        return certificate != null;
    }

    @Override
    public String toString() {
        return "PushCallerInput[" + (hasToken() ? "token" : "") + (hasToken() && hasCertificate() ? "+" : "")
                + (hasCertificate() ? "certificate" : "") + "]";
    }
}
