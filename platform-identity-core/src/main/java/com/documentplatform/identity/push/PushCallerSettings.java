package com.documentplatform.identity.push;

import com.documentplatform.identity.store.LoginConnection;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * The product's push-API caller settings (Spring: {@code platform.identity.push.*}). The required API audience is the
 * {@code apiAudience} of the customer's ACTIVE login connection for the product. No product's claim names are built
 * in: each product configures its own; the client ID defaults cover the usual login systems.
 *
 * @param productCode                the product whose connection and bindings apply
 * @param clientIdClaims             the claims the client ID is read from, the first one present wins (default
 *                                   {@link #DEFAULT_CLIENT_ID_CLAIMS})
 * @param entityClaim                optional: a claim that, when in the token, must equal the binding's entity (its
 *                                   slot ID or entity UUID); null when not configured
 * @param sourceClaim                optional: a claim that, when in the token, must equal the binding's source key
 * @param requireTokenAndCertificate both a token and a client certificate, pointing to the same binding
 * @param cacheFor                   how long an answer (allowed or refused) is kept per caller; 0 keeps none. A
 *                                   binding switched off takes effect within this time.
 */
public record PushCallerSettings(String productCode, List<String> clientIdClaims, String entityClaim,
                                 String sourceClaim, boolean requireTokenAndCertificate, Duration cacheFor) {

    public static final List<String> DEFAULT_CLIENT_ID_CLAIMS = List.of("client_id", "azp", "cid", "sub");
    public static final Duration DEFAULT_CACHE_FOR = Duration.ofSeconds(30);

    public PushCallerSettings {
        LoginConnection.requireProductCode(productCode);
        clientIdClaims = clientIdClaims == null || clientIdClaims.isEmpty() ? DEFAULT_CLIENT_ID_CLAIMS
                : List.copyOf(clientIdClaims);
        for (String c : clientIdClaims) {
            if (c.isBlank()) throw new IllegalArgumentException("a client ID claim name is blank");
        }
        entityClaim = blankToNull(entityClaim);
        sourceClaim = blankToNull(sourceClaim);
        Objects.requireNonNull(cacheFor, "cacheFor");
        if (cacheFor.isNegative() || cacheFor.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("cacheFor must be 0 to 10 minutes");
        }
    }

    /** The defaults: client ID from {@code client_id, azp, cid, sub}, no entity or source claim, 30 s cache. */
    public static PushCallerSettings of(String productCode) {
        return new PushCallerSettings(productCode, DEFAULT_CLIENT_ID_CLAIMS, null, null, false, DEFAULT_CACHE_FOR);
    }

    public PushCallerSettings withClientIdClaims(List<String> claims) {
        return new PushCallerSettings(productCode, claims, entityClaim, sourceClaim, requireTokenAndCertificate,
                cacheFor);
    }

    public PushCallerSettings withEntityClaim(String claim) {
        return new PushCallerSettings(productCode, clientIdClaims, claim, sourceClaim, requireTokenAndCertificate,
                cacheFor);
    }

    public PushCallerSettings withSourceClaim(String claim) {
        return new PushCallerSettings(productCode, clientIdClaims, entityClaim, claim, requireTokenAndCertificate,
                cacheFor);
    }

    public PushCallerSettings withTokenAndCertificateRequired(boolean required) {
        return new PushCallerSettings(productCode, clientIdClaims, entityClaim, sourceClaim, required, cacheFor);
    }

    public PushCallerSettings withCacheFor(Duration d) {
        return new PushCallerSettings(productCode, clientIdClaims, entityClaim, sourceClaim,
                requireTokenAndCertificate, d);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
