package com.documentplatform.licensing;

import com.documentplatform.licensing.json.Fields;
import com.documentplatform.licensing.json.Json;
import com.documentplatform.licensing.json.JsonException;

import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The public keys an app trusts, by key ID. Apps ship this set; a new signing key is added beside the old ones, so
 * licences signed with an earlier key keep verifying after a rotation.
 *
 * <p>File form ({@value #FORMAT}):
 * <pre>
 * {"format":"platform-licence-keys/1","keys":[{"algorithm":"Ed25519","keyId":"lic-2026-01","publicKey":"&lt;Base64 X.509&gt;"}]}
 * </pre>
 */
public final class TrustedKeys {

    public static final String FORMAT = "platform-licence-keys/1";

    private final Map<String, PublicKey> keys;

    private TrustedKeys(Map<String, PublicKey> keys) {
        this.keys = Collections.unmodifiableMap(new TreeMap<>(keys));
    }

    public static TrustedKeys of(Map<String, PublicKey> keys) {
        Map<String, PublicKey> copy = new TreeMap<>();
        keys.forEach((id, key) -> {
            Licence.requireKeyId(id);
            if (!"Ed25519".equals(key.getAlgorithm()) && !"EdDSA".equals(key.getAlgorithm())) {
                throw new IllegalArgumentException("key " + id + " is not an Ed25519 key");
            }
            copy.put(id, key);
        });
        return new TrustedKeys(copy);
    }

    /** @throws IllegalArgumentException naming the field that is wrong */
    public static TrustedKeys parse(String json) {
        try {
            Fields f = Fields.of(Json.parse(json), "keys file").only(Set.of("format", "keys"));
            if (!FORMAT.equals(f.string("format"))) {
                throw new IllegalArgumentException("keys file format is not " + FORMAT);
            }
            Map<String, PublicKey> keys = new TreeMap<>();
            for (Object o : f.list("keys")) {
                Fields k = Fields.of(o, "keys[]").only(Set.of("algorithm", "keyId", "publicKey"));
                if (!Ed25519.ALGORITHM.equals(k.string("algorithm"))) {
                    throw new IllegalArgumentException("keys[].algorithm must be Ed25519");
                }
                String id = k.string("keyId");
                if (keys.put(id, Ed25519.decodePublic(k.string("publicKey"))) != null) {
                    throw new IllegalArgumentException("key ID " + id + " is listed twice");
                }
            }
            return of(keys);
        } catch (JsonException e) {
            throw new IllegalArgumentException("keys file is not valid: " + e.getMessage());
        }
    }

    public String toJson() {
        List<Object> list = new ArrayList<>();
        keys.forEach((id, key) -> list.add(Map.of("algorithm", Ed25519.ALGORITHM, "keyId", id,
                "publicKey", Ed25519.encodePublic(key))));
        return Json.pretty(Map.of("format", FORMAT, "keys", list));
    }

    public Optional<PublicKey> key(String keyId) {
        return Optional.ofNullable(keys.get(keyId));
    }

    public Set<String> keyIds() {
        return keys.keySet();
    }
}
