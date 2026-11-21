package com.documentplatform.licensing;

import com.documentplatform.licensing.json.Fields;
import com.documentplatform.licensing.json.JsonException;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static com.documentplatform.licensing.LicenceRefusal.*;

/**
 * Checks a stored licence file and returns its content. Call it every time the licence is read (an edited row must be
 * detected), so a renewal stored in the database takes effect without a restart.
 *
 * <p>Order of checks, each with its own code: missing → not readable (malformed) → unsupported format or algorithm →
 * unknown key ID → digest (tampered: payload or digest edited) → signature (forged) → content valid → expected tenant
 * (wrong customer). Refusals are logged with the code only.
 */
public final class LicenceVerifier {

    private static final System.Logger LOG = System.getLogger(LicenceVerifier.class.getName());

    private final TrustedKeys keys;

    public LicenceVerifier(TrustedKeys keys) {
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    public TrustedKeys keys() {
        return keys;
    }

    /**
     * @param stored           the licence file as stored (UTF-8), or null if none is stored
     * @param expectedTenantId the tenant this installation belongs to, or null when not yet known (first setup)
     * @throws LicenceException with the refusal code
     */
    public Licence verify(byte[] stored, String expectedTenantId) {
        try {
            return check(stored, expectedTenantId);
        } catch (LicenceException e) {
            LOG.log(System.Logger.Level.WARNING, "Licence refused: {0}", e.code());
            throw e;
        }
    }

    public Licence verify(String stored, String expectedTenantId) {
        return verify(stored == null ? null : stored.getBytes(StandardCharsets.UTF_8), expectedTenantId);
    }

    private Licence check(byte[] stored, String expectedTenantId) {
        Fields payload;
        try {
            payload = SignedDocument.open(stored, LicenceFormat.FORMAT, "licence", keys);
        } catch (SignedDocument.Failure f) {
            throw new LicenceException(switch (f.problem()) {
                case MISSING -> LICENCE_MISSING;
                case MALFORMED -> LICENCE_MALFORMED;
                case UNSUPPORTED_FORMAT -> LICENCE_UNSUPPORTED_FORMAT;
                case UNKNOWN_KEY_ID -> LICENCE_UNKNOWN_KEY_ID;
                case TAMPERED -> LICENCE_TAMPERED;
                case FORGED -> LICENCE_FORGED;
            }, f.getMessage());
        }
        Licence licence;
        try {
            licence = LicenceFormat.fromPayload(payload);
        } catch (JsonException | IllegalArgumentException e) {
            throw new LicenceException(LICENCE_MALFORMED, "The signed content is not a valid licence ("
                    + e.getMessage() + ").");
        }
        if (expectedTenantId != null && !expectedTenantId.equals(licence.tenantId())) {
            throw new LicenceException(LICENCE_WRONG_CUSTOMER, "This installation is tenant "
                    + SignedDocument.printable(expectedTenantId) + "; the licence is for tenant " + licence.tenantId()
                    + " (" + licence.licenceId() + ").");
        }
        return licence;
    }
}
