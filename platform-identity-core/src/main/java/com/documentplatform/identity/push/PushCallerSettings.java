package com.documentplatform.identity.push;

import com.documentplatform.identity.store.LoginConnection;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The product's push-API caller settings (Spring: {@code platform.identity.push.*}). The required API audience is the
 * {@code apiAudience} of the customer's ACTIVE login connection for the product. No product's claim names are built
 * in: each product configures its own; the client ID defaults cover the usual login systems.
 *
 * <p>P045, SEC-7: a push caller is a machine. A token that carries a claim only a person's sign-in has
 * ({@link #userClaims}, default {@link #DEFAULT_USER_CLAIMS}) is refused ({@code IDN_PUSH_NOT_MACHINE}), whatever
 * client ID it names; a product whose login system marks machine tokens can require that mark as well
 * ({@link #machineClaim} = {@link #machineClaimValue}, for example {@code idtyp} = {@code app}).
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
 * @param userClaims                 claims that mark a person's token: any of them present refuses the caller (empty:
 *                                   the default set; a product that must accept one names its own set)
 * @param machineClaim               optional: a claim every machine token of the login system carries; null when not
 *                                   configured
 * @param machineClaimValue          the value {@code machineClaim} must have (required with it)
 */
public record PushCallerSettings(String productCode, List<String> clientIdClaims, String entityClaim,
                                 String sourceClaim, boolean requireTokenAndCertificate, Duration cacheFor,
                                 Set<String> userClaims, String machineClaim, String machineClaimValue) {

    /**
     * The client ID claims by default. {@code sub} is no longer among them (P045, SEC-7): in a person's token it is the
     * person; a login system whose machine tokens name the client only there configures it explicitly.
     */
    public static final List<String> DEFAULT_CLIENT_ID_CLAIMS = List.of("client_id", "azp", "cid");
    public static final Duration DEFAULT_CACHE_FOR = Duration.ofSeconds(30);
    /**
     * Claims only a person's sign-in carries (an interactive sign-in, a person's name or address, the groups a person is
     * in). None of the usual login systems puts them in a client-credentials token.
     */
    public static final Set<String> DEFAULT_USER_CLAIMS = Set.of("email", "upn", "given_name", "family_name", "name",
            "amr", "auth_time", "sid", "uid", "nonce", "groups");

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
        userClaims = userClaims == null || userClaims.isEmpty() ? DEFAULT_USER_CLAIMS : Set.copyOf(userClaims);
        for (String c : userClaims) {
            if (c.isBlank()) throw new IllegalArgumentException("a user claim name is blank");
            if (clientIdClaims.contains(c)) {
                throw new IllegalArgumentException("claim " + c + " cannot both name the client and mark a person");
            }
        }
        machineClaim = blankToNull(machineClaim);
        machineClaimValue = blankToNull(machineClaimValue);
        if ((machineClaim == null) != (machineClaimValue == null)) {
            throw new IllegalArgumentException("a machine claim needs its value, and a value its claim");
        }
    }

    /** The settings without the machine-token settings: the default user claims, no machine claim. */
    public PushCallerSettings(String productCode, List<String> clientIdClaims, String entityClaim, String sourceClaim,
                              boolean requireTokenAndCertificate, Duration cacheFor) {
        this(productCode, clientIdClaims, entityClaim, sourceClaim, requireTokenAndCertificate, cacheFor, null, null,
                null);
    }

    /** The defaults: client ID from {@code client_id, azp, cid}, no entity or source claim, 30 s cache. */
    public static PushCallerSettings of(String productCode) {
        return new PushCallerSettings(productCode, DEFAULT_CLIENT_ID_CLAIMS, null, null, false, DEFAULT_CACHE_FOR);
    }

    public PushCallerSettings withClientIdClaims(List<String> claims) {
        return new PushCallerSettings(productCode, claims, entityClaim, sourceClaim, requireTokenAndCertificate,
                cacheFor, userClaims, machineClaim, machineClaimValue);
    }

    public PushCallerSettings withEntityClaim(String claim) {
        return new PushCallerSettings(productCode, clientIdClaims, claim, sourceClaim, requireTokenAndCertificate,
                cacheFor, userClaims, machineClaim, machineClaimValue);
    }

    public PushCallerSettings withSourceClaim(String claim) {
        return new PushCallerSettings(productCode, clientIdClaims, entityClaim, claim, requireTokenAndCertificate,
                cacheFor, userClaims, machineClaim, machineClaimValue);
    }

    public PushCallerSettings withTokenAndCertificateRequired(boolean required) {
        return new PushCallerSettings(productCode, clientIdClaims, entityClaim, sourceClaim, required, cacheFor,
                userClaims, machineClaim, machineClaimValue);
    }

    public PushCallerSettings withCacheFor(Duration d) {
        return new PushCallerSettings(productCode, clientIdClaims, entityClaim, sourceClaim,
                requireTokenAndCertificate, d, userClaims, machineClaim, machineClaimValue);
    }

    /** The claims that mark a person's token (P045, SEC-7). */
    public PushCallerSettings withUserClaims(Set<String> claims) {
        return new PushCallerSettings(productCode, clientIdClaims, entityClaim, sourceClaim,
                requireTokenAndCertificate, cacheFor, claims, machineClaim, machineClaimValue);
    }

    /** A claim and value every machine token must carry (P045, SEC-7). */
    public PushCallerSettings withMachineClaim(String claim, String value) {
        return new PushCallerSettings(productCode, clientIdClaims, entityClaim, sourceClaim,
                requireTokenAndCertificate, cacheFor, userClaims, claim, value);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
