package com.documentplatform.licensing.issue;

import com.documentplatform.licensing.Ed25519;
import com.documentplatform.licensing.SignedDocument;
import com.documentplatform.licensing.json.Json;

import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Signs a document in the shape {@link SignedDocument} verifies (P041: the signing side, moved out of the customer's
 * platform-licensing): {@code {"format", <body>: payload, "signature": {algorithm, payloadSha256, value}}} over the
 * message {@code format LF canonical(payload)}.
 */
public final class DocumentSigner {

    /** Signs a message with the key the payload's {@code keyId} names; the key itself stays with the signer. */
    @FunctionalInterface
    public interface MessageSigner {
        /** @return the 64-byte Ed25519 signature of {@code message} */
        byte[] sign(byte[] message);
    }

    private DocumentSigner() {
    }

    /**
     * @param payload must hold {@code keyId}
     * @throws IllegalArgumentException if the key is not an Ed25519 private key
     */
    public static String sign(String format, String body, Map<String, Object> payload, PrivateKey key) {
        return sign(format, body, payload, message -> {
            try {
                return SigningKeys.sign(key, message);
            } catch (InvalidKeyException e) {
                throw new IllegalArgumentException("the signing key for " + payload.get("keyId")
                        + " is not an Ed25519 private key");
            }
        });
    }

    /**
     * @param payload must hold {@code keyId}
     * @throws IllegalArgumentException if the signer does not return 64 bytes
     */
    public static String sign(String format, String body, Map<String, Object> payload, MessageSigner signer) {
        byte[] message = SignedDocument.signedMessage(format, payload);
        byte[] signature = signer.sign(message);
        if (signature == null || signature.length != Ed25519.SIGNATURE_LENGTH) {
            throw new IllegalArgumentException("the signer for " + payload.get("keyId")
                    + " did not return a 64-byte Ed25519 signature");
        }
        Map<String, Object> sig = new LinkedHashMap<>();
        sig.put("algorithm", Ed25519.ALGORITHM);
        sig.put("payloadSha256", Ed25519.sha256Hex(message));
        sig.put("value", Base64.getEncoder().encodeToString(signature));
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("format", format);
        file.put(body, payload);
        file.put("signature", sig);
        return Json.pretty(file);
    }
}
