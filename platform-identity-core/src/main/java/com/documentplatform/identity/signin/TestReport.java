package com.documentplatform.identity.signin;

import java.util.Objects;
import java.util.Optional;

/**
 * The result of a test sign-in: passed, or the first check that failed with its specific cause. Messages are neutral:
 * they name the check and the field, never a token, claim value or key.
 *
 * @param failure      the failed check, or null when it passed
 * @param message      for the IT Admin: what was checked, or what failed and what to look at
 * @param reason       for a fetch that was refused, the outbound rule's code (for example {@code INT_TLS_FAILED} or
 *                     {@code HTTP_404}); else null
 * @param tokenChecked whether a token was checked too (else only the discovery document and keys)
 */
public record TestReport(Code failure, String message, String reason, boolean tokenChecked) {

    /** Why a test sign-in failed. */
    public enum Code {
        /** The issuer's discovery document could not be fetched (connection, outbound rule, or an HTTP error). */
        IDN_TEST_ISSUER_UNREACHABLE,
        /** The discovery document is not valid JSON, or its {@code issuer} is not exactly the connection's issuer. */
        IDN_TEST_DISCOVERY_MISMATCH,
        /** No {@code jwks_uri}, the key set could not be fetched, or it holds no usable signing key. */
        IDN_TEST_KEYS_MISSING,
        /** The pasted text is not a signed JWT (three parts, readable header and claims). */
        IDN_TEST_TOKEN_UNREADABLE,
        /** The token's signature does not verify with the issuer's keys (or its algorithm is not accepted). */
        IDN_TEST_TOKEN_SIGNATURE,
        /** The token's {@code iss} is not the connection's issuer. */
        IDN_TEST_TOKEN_ISSUER,
        /** The token's {@code aud} does not name the API audience the product expects. */
        IDN_TEST_AUDIENCE,
        /** The token has expired, is not valid yet, or has no expiry. */
        IDN_TEST_EXPIRED,
        /** The token has no groups claim (under the claim name the app reads). */
        IDN_TEST_GROUPS_CLAIM_MISSING
    }

    public TestReport {
        Objects.requireNonNull(message, "message");
    }

    static TestReport pass(String message, boolean tokenChecked) {
        return new TestReport(null, message, null, tokenChecked);
    }

    static TestReport fail(Code code, String message, String reason, boolean tokenChecked) {
        return new TestReport(Objects.requireNonNull(code, "code"), message, reason, tokenChecked);
    }

    public boolean passed() {
        return failure == null;
    }

    public Optional<Code> failureCode() {
        return Optional.ofNullable(failure);
    }
}
