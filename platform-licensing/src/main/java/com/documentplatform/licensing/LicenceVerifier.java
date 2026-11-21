package com.documentplatform.licensing;

import com.documentplatform.licensing.json.Fields;
import com.documentplatform.licensing.json.Json;
import com.documentplatform.licensing.json.JsonException;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Objects;

import static com.documentplatform.licensing.LicenceRefusal.*;

/**
 * Checks a stored licence file and returns its content. Call it every time the licence is read (the design requires
 * an edited row to be detected), so a renewal stored in the database takes effect without a restart.
 *
 * <p>Order of checks, each with its own code: missing → not readable (malformed) → unsupported format or algorithm →
 * unknown key ID → digest (tampered: payload or digest edited) → signature (forged) →
 * content valid → expected tenant (wrong customer). Refusals are logged with the code and IDs only.
 */
public final class LicenceVerifier {

    private static final System.Logger LOG = System.getLogger(LicenceVerifier.class.getName());

    private final TrustedKeys keys;

    public LicenceVerifier(TrustedKeys keys) {
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    /**
     * @param stored           the licence file as stored (UTF-8), or null if none is stored
     * @param expectedTenantId the tenant this installation belongs to, or null when not yet known (first setup)
     * @throws LicenceException with the refusal code
     */
    public Licence verify(byte[] stored, String expectedTenantId) {
        try {
            Licence licence = check(stored, expectedTenantId);
            LOG.log(System.Logger.Level.DEBUG, "Licence {0} v{1} accepted (tenant {2}, key {3})",
                    licence.licenceId(), licence.version(), licence.tenantId(), licence.keyId());
            return licence;
        } catch (LicenceException e) {
            LOG.log(System.Logger.Level.WARNING, "Licence refused: {0}", e.code());
            throw e;
        }
    }

    public Licence verify(String stored, String expectedTenantId) {
        return verify(stored == null ? null : stored.getBytes(StandardCharsets.UTF_8), expectedTenantId);
    }

    private Licence check(byte[] stored, String expectedTenantId) {
        if (stored == null || stored.length == 0) {
            throw new LicenceException(LICENCE_MISSING);
        }
        String text = utf8(stored);
        if (text.isBlank()) {
            throw new LicenceException(LICENCE_MISSING);
        }
        Fields envelope;
        try {
            envelope = Fields.of(Json.parse(text), "licence file");
        } catch (JsonException e) {
            throw new LicenceException(LICENCE_MALFORMED, "The file is not valid JSON (" + e.getMessage() + ").");
        }
        Fields payload;
        Fields signature;
        String keyId;
        byte[] signatureBytes;
        String recordedSha;
        try {
            if (!envelope.has("format") || !(envelope.raw("format") instanceof String format)) {
                throw new JsonException("licence file.format is missing");
            }
            if (!LicenceFormat.FORMAT.equals(format)) {
                throw new LicenceException(LICENCE_UNSUPPORTED_FORMAT,
                        "This version reads " + LicenceFormat.FORMAT + " only.");
            }
            envelope.only(LicenceFormat.ENVELOPE);
            payload = envelope.object("licence");
            signature = envelope.object("signature").only(LicenceFormat.SIGNATURE);
            if (!Ed25519.ALGORITHM.equals(signature.string("algorithm"))) {
                throw new LicenceException(LICENCE_UNSUPPORTED_FORMAT, "The signature algorithm must be Ed25519.");
            }
            keyId = payload.string("keyId");
            recordedSha = signature.string("payloadSha256");
            String value = signature.string("value");
            try {
                signatureBytes = Base64.getDecoder().decode(value);
            } catch (IllegalArgumentException e) {
                throw new JsonException("signature.value is not Base64");
            }
            if (signatureBytes.length != Ed25519.SIGNATURE_LENGTH) {
                throw new JsonException("signature.value is not 64 bytes");
            }
        } catch (JsonException e) {
            throw new LicenceException(LICENCE_MALFORMED, "The file does not have the licence layout ("
                    + e.getMessage() + ").");
        }
        PublicKey key = keys.key(keyId).orElseThrow(() -> new LicenceException(LICENCE_UNKNOWN_KEY_ID,
                "Key ID '" + printable(keyId) + "' is not among the trusted keys " + keys.keyIds()
                        + ". Install the version that ships this key, or ask for a licence signed with a current key."));
        byte[] message = LicenceFormat.signedMessage(payload.map());
        // Any edit of the file is refused, including one to the digest alone.
        if (!Ed25519.sha256Hex(message).equals(recordedSha)) {
            throw new LicenceException(LICENCE_TAMPERED,
                    "The licence content no longer matches what was signed with key " + keyId + ".");
        }
        if (!Ed25519.verify(key, message, signatureBytes)) {
            throw new LicenceException(LICENCE_FORGED,
                    "The content is as recorded, but its signature does not verify with key " + keyId + ".");
        }
        Licence licence;
        try {
            licence = LicenceFormat.fromPayload(payload);
        } catch (JsonException | IllegalArgumentException e) {
            throw new LicenceException(LICENCE_MALFORMED, "The signed content is not a valid licence ("
                    + e.getMessage() + ").");
        }
        if (expectedTenantId != null && !expectedTenantId.equals(licence.tenantId())) {
            throw new LicenceException(LICENCE_WRONG_CUSTOMER, "This installation is tenant " + printable(expectedTenantId)
                    + "; the licence is for tenant " + licence.tenantId() + " (" + licence.licenceId() + ").");
        }
        return licence;
    }

    private static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new LicenceException(LICENCE_MALFORMED, "The file is not UTF-8 text.");
        }
    }

    /** An ID is shown only if it has the shape of an ID; anything else may be pasted content. */
    private static String printable(String id) {
        return id != null && id.matches("[A-Za-z0-9._-]{1,64}") ? id : "(not an ID)";
    }
}
