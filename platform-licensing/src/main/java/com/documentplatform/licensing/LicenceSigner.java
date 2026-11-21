package com.documentplatform.licensing;

import com.documentplatform.licensing.json.Json;

import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Writes a signed licence file ({@link LicenceFormat}). The private key is used and dropped; it is never written. */
public final class LicenceSigner {

    private LicenceSigner() {
    }

    /**
     * @return the licence file text (UTF-8 when stored), pretty-printed with sorted keys
     * @throws IllegalArgumentException if the key is not an Ed25519 private key
     */
    public static String sign(Licence licence, PrivateKey key) {
        Map<String, Object> payload = LicenceFormat.payload(licence);
        byte[] message = LicenceFormat.signedMessage(payload);
        byte[] signature;
        try {
            signature = Ed25519.sign(key, message);
        } catch (InvalidKeyException e) {
            throw new IllegalArgumentException("the signing key for " + licence.keyId() + " is not an Ed25519 private key");
        }
        Map<String, Object> sig = new LinkedHashMap<>();
        sig.put("algorithm", Ed25519.ALGORITHM);
        sig.put("payloadSha256", Ed25519.sha256Hex(message));
        sig.put("value", Base64.getEncoder().encodeToString(signature));
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("format", LicenceFormat.FORMAT);
        file.put("licence", payload);
        file.put("signature", sig);
        return Json.pretty(file);
    }
}
