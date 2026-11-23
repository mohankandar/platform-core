package com.documentplatform.licensing;

import com.documentplatform.licensing.json.Fields;
import com.documentplatform.licensing.json.Json;
import com.documentplatform.licensing.json.JsonException;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The signed envelope shared by every signed file (licence, last-seen reset):
 * <pre>
 * { "format": "&lt;format&gt;", "&lt;body&gt;": { ...payload with "keyId"... },
 *   "signature": { "algorithm": "Ed25519", "payloadSha256": "&lt;hex&gt;", "value": "&lt;Base64&gt;" } }
 * </pre>
 * Signed message: UTF-8 of {@code format + "\n" + canonical(payload)}. {@code payloadSha256} is the SHA-256 of that
 * message; any edit (payload or digest) gives {@link Problem#TAMPERED}, a signature not made by the named key gives
 * {@link Problem#FORGED}. See {@code docs/design/licensing-file-format.md}.
 */
public final class SignedDocument {

    /** Why a document was not accepted, before the caller maps it to its own stable code. */
    public enum Problem { MISSING, MALFORMED, UNSUPPORTED_FORMAT, UNKNOWN_KEY_ID, TAMPERED, FORGED }

    /** A document failed to open. The detail names the exact cause with IDs only. */
    public static final class Failure extends RuntimeException {
        private final Problem problem;

        Failure(Problem problem, String detail) {
            super(detail);
            this.problem = problem;
        }

        public Problem problem() {
            return problem;
        }
    }

    private static final Set<String> SIGNATURE = Set.of("algorithm", "payloadSha256", "value");

    private SignedDocument() {
    }

    public static byte[] signedMessage(String format, Object payload) {
        return (format + "\n" + Json.canonical(payload)).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Checks the envelope and the signature and returns the payload.
     *
     * @throws Failure with the problem and its exact cause
     */
    public static Fields open(byte[] stored, String format, String body, TrustedKeys keys) {
        if (stored == null || stored.length == 0) {
            throw new Failure(Problem.MISSING, "");
        }
        String text = utf8(stored);
        if (text.isBlank()) {
            throw new Failure(Problem.MISSING, "");
        }
        Fields envelope;
        try {
            envelope = Fields.of(Json.parse(text), "file");
        } catch (JsonException e) {
            throw new Failure(Problem.MALFORMED, "The file is not valid JSON (" + e.getMessage() + ").");
        }
        Fields payload;
        String keyId;
        byte[] signatureBytes;
        String recordedSha;
        try {
            if (!envelope.has("format") || !(envelope.raw("format") instanceof String found)) {
                throw new JsonException("file.format is missing");
            }
            if (!format.equals(found)) {
                throw new Failure(Problem.UNSUPPORTED_FORMAT, "This version reads " + format + " here.");
            }
            envelope.only(Set.of("format", body, "signature"));
            payload = envelope.object(body);
            Fields signature = envelope.object("signature").only(SIGNATURE);
            if (!Ed25519.ALGORITHM.equals(signature.string("algorithm"))) {
                throw new Failure(Problem.UNSUPPORTED_FORMAT, "The signature algorithm must be Ed25519.");
            }
            keyId = payload.string("keyId");
            recordedSha = signature.string("payloadSha256");
            try {
                signatureBytes = Base64.getDecoder().decode(signature.string("value"));
            } catch (IllegalArgumentException e) {
                throw new JsonException("signature.value is not Base64");
            }
            if (signatureBytes.length != Ed25519.SIGNATURE_LENGTH) {
                throw new JsonException("signature.value is not 64 bytes");
            }
        } catch (JsonException e) {
            throw new Failure(Problem.MALFORMED, "The file does not have the expected layout (" + e.getMessage() + ").");
        }
        PublicKey key = keys.key(keyId).orElseThrow(() -> new Failure(Problem.UNKNOWN_KEY_ID,
                "Key ID '" + printable(keyId) + "' is not among the trusted keys " + keys.keyIds()
                        + ". Install the version that ships this key, or ask for a file signed with a current key."));
        byte[] message = signedMessage(format, payload.map());
        if (!Ed25519.sha256Hex(message).equals(recordedSha)) {
            throw new Failure(Problem.TAMPERED, "The content no longer matches what was signed with key " + keyId + ".");
        }
        if (!Ed25519.verify(key, message, signatureBytes)) {
            throw new Failure(Problem.FORGED,
                    "The content is as recorded, but its signature does not verify with key " + keyId + ".");
        }
        return payload;
    }

    private static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new Failure(Problem.MALFORMED, "The file is not UTF-8 text.");
        }
    }

    /** An ID is shown only if it has the shape of an ID; anything else may be pasted content. */
    static String printable(String id) {
        return id != null && id.matches("[A-Za-z0-9._:-]{1,74}") ? id : "(not an ID)";
    }
}
