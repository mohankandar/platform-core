package com.documentplatform.licensing;

import com.documentplatform.licensing.json.Fields;
import com.documentplatform.licensing.json.Json;
import com.documentplatform.licensing.json.JsonException;

import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The public keys an app trusts, by key ID. Apps ship this set inside their own build ({@link LicenceBuild}); a new signing key is added beside the old ones, so
 * licences signed with an earlier key keep verifying after a rotation.
 *
 * <p>File form ({@value #FORMAT}):
 * <pre>
 * {"format":"platform-licence-keys/1","keys":[{"algorithm":"Ed25519","keyId":"lic-2026-01","publicKey":"&lt;Base64 X.509&gt;"}]}
 * </pre>
 *
 * A key may carry {@code "development": true}: a development key (demo and local test licences only), which no
 * production build trusts ({@link LicenceBuild}). Some local test features (an {@code http} login issuer on this machine) are allowed only
 * for a licence signed by such a key ({@link #isDevelopmentKey}). Files without the field load as before.
 */
public final class TrustedKeys {

    public static final String FORMAT = "platform-licence-keys/1";

    private final Map<String, PublicKey> keys;
    private final Set<String> development;

    private TrustedKeys(Map<String, PublicKey> keys, Set<String> development) {
        this.keys = Collections.unmodifiableMap(new TreeMap<>(keys));
        this.development = Set.copyOf(development);
    }

    public static TrustedKeys of(Map<String, PublicKey> keys) {
        return of(keys, Set.of());
    }

    /** @param developmentKeyIds the IDs of {@code keys} that are development keys */
    public static TrustedKeys of(Map<String, PublicKey> keys, Set<String> developmentKeyIds) {
        Map<String, PublicKey> copy = new TreeMap<>();
        keys.forEach((id, key) -> {
            Licence.requireKeyId(id);
            if (!"Ed25519".equals(key.getAlgorithm()) && !"EdDSA".equals(key.getAlgorithm())) {
                throw new IllegalArgumentException("key " + id + " is not an Ed25519 key");
            }
            copy.put(id, key);
        });
        for (String id : developmentKeyIds) {
            if (!copy.containsKey(id)) throw new IllegalArgumentException("development key " + id + " is not listed");
        }
        return new TrustedKeys(copy, developmentKeyIds);
    }

    /** @throws IllegalArgumentException naming the field that is wrong */
    public static TrustedKeys parse(String json) {
        try {
            Fields f = Fields.of(Json.parse(json), "keys file").only(Set.of("format", "keys"));
            if (!FORMAT.equals(f.string("format"))) {
                throw new IllegalArgumentException("keys file format is not " + FORMAT);
            }
            Map<String, PublicKey> keys = new TreeMap<>();
            Set<String> development = new TreeSet<>();
            for (Object o : f.list("keys")) {
                Fields k = Fields.of(o, "keys[]").only(Set.of("algorithm", "keyId", "publicKey", "development"));
                if (!Ed25519.ALGORITHM.equals(k.string("algorithm"))) {
                    throw new IllegalArgumentException("keys[].algorithm must be Ed25519");
                }
                String id = k.string("keyId");
                if (keys.put(id, Ed25519.decodePublic(k.string("publicKey"))) != null) {
                    throw new IllegalArgumentException("key ID " + id + " is listed twice");
                }
                if (k.has("development")) {
                    if (!(k.map().get("development") instanceof Boolean flag)) {
                        throw new IllegalArgumentException("keys[].development must be true or false");
                    }
                    if (flag) development.add(id);
                }
            }
            return of(keys, development);
        } catch (JsonException e) {
            throw new IllegalArgumentException("keys file is not valid: " + e.getMessage());
        }
    }

    public String toJson() {
        List<Object> list = new ArrayList<>();
        keys.forEach((id, key) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("algorithm", Ed25519.ALGORITHM);
            m.put("keyId", id);
            m.put("publicKey", Ed25519.encodePublic(key));
            if (development.contains(id)) m.put("development", true);
            list.add(m);
        });
        return Json.pretty(Map.of("format", FORMAT, "keys", list));
    }

    public Optional<PublicKey> key(String keyId) {
        return Optional.ofNullable(keys.get(keyId));
    }

    public Set<String> keyIds() {
        return keys.keySet();
    }

    /** These keys without the development keys. */
    TrustedKeys withoutDevelopmentKeys() {
        Map<String, PublicKey> kept = new TreeMap<>(keys);
        kept.keySet().removeAll(development);
        return new TrustedKeys(kept, Set.of());
    }

    /** Whether the key is listed here as a development key ({@code "development": true}). */
    public boolean isDevelopmentKey(String keyId) {
        return development.contains(keyId);
    }
}
