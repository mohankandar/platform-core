package com.documentplatform.identity.push;

import java.util.Objects;
import java.util.Optional;

/**
 * The answer for one push-API call: the caller's identity, or a refusal. Give the caller {@link #NEUTRAL} only; the
 * code is for the app's log and the IT Admin.
 *
 * @param fingerprint the certificate's fingerprint ({@code cert-sha256:<hex>}) when a certificate was presented, for
 *                    the IT Admin (a fingerprint is public); null otherwise
 */
public record PushCallerResult(PushCallerIdentity identity, PushRefusal refusal, String fingerprint) {

    /** What the caller is told when refused: nothing about why. */
    public static final String NEUTRAL = "The caller is not allowed to call this API.";

    public PushCallerResult {
        if ((identity == null) == (refusal == null)) {
            throw new IllegalArgumentException("either an identity or a refusal");
        }
    }

    static PushCallerResult allowed(PushCallerIdentity identity, String fingerprint) {
        return new PushCallerResult(Objects.requireNonNull(identity), null, fingerprint);
    }

    static PushCallerResult refused(PushRefusal refusal, String fingerprint) {
        return new PushCallerResult(null, Objects.requireNonNull(refusal), fingerprint);
    }

    public boolean allowed() {
        return identity != null;
    }

    public Optional<PushCallerIdentity> caller() {
        return Optional.ofNullable(identity);
    }

    public Optional<PushRefusal> refusalCode() {
        return Optional.ofNullable(refusal);
    }

    @Override
    public String toString() {
        return allowed() ? "PushCallerResult[allowed " + identity.slotId() + "/" + identity.sourceKey() + "]"
                : "PushCallerResult[" + refusal + "]";
    }
}
