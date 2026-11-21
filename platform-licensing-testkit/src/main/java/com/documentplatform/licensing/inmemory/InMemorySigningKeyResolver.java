package com.documentplatform.licensing.inmemory;

import com.documentplatform.licensing.issue.SigningKeyResolver;

import java.security.PrivateKey;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Signing keys in memory, for tests (throwaway keys only). */
public final class InMemorySigningKeyResolver implements SigningKeyResolver {

    private final Map<String, PrivateKey> keys = new HashMap<>();

    public InMemorySigningKeyResolver put(String keyId, PrivateKey key) {
        keys.put(keyId, key);
        return this;
    }

    @Override
    public Optional<PrivateKey> resolve(String keyId) {
        return Optional.ofNullable(keys.get(keyId));
    }
}
