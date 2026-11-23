package com.documentplatform.identity.spring;

import com.documentplatform.identity.push.PushCallerInput;
import org.springframework.security.oauth2.jwt.Jwt;

import java.net.URI;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;

/**
 * Turns what Spring Security and the servlet container have verified into a {@link PushCallerInput}: the decoded
 * {@link Jwt} (signature and expiry already checked by the app's JWT decoder) and/or the client certificate of the
 * request (checked by the app's TLS layer).
 *
 * <p>Platform-core reads no headers. A certificate forwarded by a TLS-terminating proxy in a header is the app's own
 * business: trust such a header only from the app's own proxy, configured by the app (and strip it from every other
 * request), then pass the parsed certificate here.
 *
 * <pre>
 * &#64;PostMapping("/push")
 * ResponseEntity&lt;?&gt; push(&#64;AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
 *     PushCallerResult r = pushCaller.check(PushCallerRequests.input(jwt,
 *             PushCallerRequests.certificate(request.getAttribute(PushCallerRequests.CERTIFICATE_ATTRIBUTE))
 *                     .orElse(null)));
 *     if (!r.allowed()) return ResponseEntity.status(403).body(PushCallerResult.NEUTRAL);
 *     ...
 * }
 * </pre>
 */
public final class PushCallerRequests {

    /** The servlet request attribute holding the verified client certificate chain ({@code X509Certificate[]}). */
    public static final String CERTIFICATE_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";

    private PushCallerRequests() {
    }

    /** A token caller. The issuer is the token's {@code iss} exactly as sent. */
    public static PushCallerInput input(Jwt jwt) {
        return input(jwt, null);
    }

    /**
     * A token and/or certificate caller.
     *
     * @param jwt         the decoded token, or null for a certificate-only caller
     * @param certificate the verified client certificate, or null
     */
    public static PushCallerInput input(Jwt jwt, X509Certificate certificate) {
        if (jwt == null) {
            if (certificate == null) throw new IllegalArgumentException("a token or a client certificate is needed");
            return PushCallerInput.certificate(certificate);
        }
        String iss = jwt.getClaimAsString("iss");
        if (iss == null || iss.isBlank()) throw new IllegalArgumentException("the token has no issuer");
        URI issuer = URI.create(iss);
        List<String> audiences = jwt.getAudience() == null ? List.of() : jwt.getAudience();
        return certificate == null ? PushCallerInput.token(issuer, audiences, jwt.getClaims())
                : PushCallerInput.tokenAndCertificate(issuer, audiences, jwt.getClaims(), certificate);
    }

    /** The client's own certificate (the first of the chain) from the servlet attribute's value; empty when none. */
    public static Optional<X509Certificate> certificate(Object requestAttribute) {
        if (requestAttribute instanceof X509Certificate[] chain && chain.length > 0 && chain[0] != null) {
            return Optional.of(chain[0]);
        }
        if (requestAttribute instanceof X509Certificate c) return Optional.of(c);
        return Optional.empty();
    }
}
