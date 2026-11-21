package com.documentplatform.licensing;

import com.documentplatform.licensing.json.Fields;
import com.documentplatform.licensing.json.JsonException;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A signed instruction from our side to set an installation's latest time seen back to {@code resetTo}: the way out
 * when a wrong future server clock poisoned it (licence dates use the later of the server clock and the latest time
 * seen). File format {@value #FORMAT}, same envelope as the licence ({@link SignedDocument}), body {@code "reset"}.
 * Single use (its ID is recorded) and valid only until {@code notAfter}, so it cannot be replayed later to roll the
 * time back again.
 */
public record LastSeenReset(String resetId, String tenantId, Instant resetTo, Instant notAfter, Instant issuedAt,
                            String keyId) {

    public static final String FORMAT = "platform-last-seen-reset/1";
    private static final Set<String> FIELDS = Set.of("resetId", "tenantId", "resetTo", "notAfter", "issuedAt", "keyId");

    /** What applying a reset did. */
    public enum Outcome {
        /** The latest time seen was set back. */
        APPLIED,
        /** This reset was applied before; nothing changed. */
        ALREADY_APPLIED,
        /** The latest time seen was not after the reset time; nothing to undo (the reset is recorded as used). */
        NOT_NEEDED
    }

    public LastSeenReset {
        Licence.requireId(resetId, "reset ID");
        Licence.requireId(tenantId, "tenant ID");
        Objects.requireNonNull(resetTo, "resetTo");
        Objects.requireNonNull(notAfter, "notAfter");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Licence.requireKeyId(keyId);
        if (notAfter.isBefore(resetTo)) {
            throw new IllegalArgumentException("notAfter is before resetTo");
        }
    }

    public Map<String, Object> payload() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("resetId", resetId);
        m.put("tenantId", tenantId);
        m.put("resetTo", resetTo.toString());
        m.put("notAfter", notAfter.toString());
        m.put("issuedAt", issuedAt.toString());
        m.put("keyId", keyId);
        return m;
    }

    public String sign(PrivateKey key) {
        return SignedDocument.sign(FORMAT, "reset", payload(), key);
    }

    /** Signs through a signing service that keeps the private key ({@link SignedDocument.MessageSigner}). */
    public String sign(SignedDocument.MessageSigner signer) {
        return SignedDocument.sign(FORMAT, "reset", payload(), signer);
    }

    /**
     * Checks the signature and the tenant. Validity in time is checked when applying ({@link ClockGuard#apply}).
     *
     * @throws ResetException with its code
     */
    public static LastSeenReset verify(byte[] file, TrustedKeys keys, String expectedTenantId) {
        Fields p;
        try {
            p = SignedDocument.open(file, FORMAT, "reset", keys);
        } catch (SignedDocument.Failure f) {
            throw new ResetException(switch (f.problem()) {
                case MISSING -> ResetRefusal.RESET_MISSING;
                case MALFORMED -> ResetRefusal.RESET_MALFORMED;
                case UNSUPPORTED_FORMAT -> ResetRefusal.RESET_UNSUPPORTED_FORMAT;
                case UNKNOWN_KEY_ID -> ResetRefusal.RESET_UNKNOWN_KEY_ID;
                case TAMPERED -> ResetRefusal.RESET_TAMPERED;
                case FORGED -> ResetRefusal.RESET_FORGED;
            }, f.getMessage());
        }
        LastSeenReset reset;
        try {
            p.only(FIELDS);
            reset = new LastSeenReset(p.string("resetId"), p.string("tenantId"),
                    LicenceFormat.instant(p.string("resetTo")), LicenceFormat.instant(p.string("notAfter")),
                    LicenceFormat.instant(p.string("issuedAt")), p.string("keyId"));
        } catch (JsonException | IllegalArgumentException e) {
            throw new ResetException(ResetRefusal.RESET_MALFORMED, "The signed content is not a valid reset ("
                    + e.getMessage() + ").");
        }
        if (expectedTenantId != null && !expectedTenantId.equals(reset.tenantId())) {
            throw new ResetException(ResetRefusal.RESET_WRONG_CUSTOMER, "This installation is tenant "
                    + SignedDocument.printable(expectedTenantId) + "; the reset is for tenant " + reset.tenantId() + ".");
        }
        return reset;
    }

    public static LastSeenReset verify(String file, TrustedKeys keys, String expectedTenantId) {
        return verify(file == null ? null : file.getBytes(StandardCharsets.UTF_8), keys, expectedTenantId);
    }
}
