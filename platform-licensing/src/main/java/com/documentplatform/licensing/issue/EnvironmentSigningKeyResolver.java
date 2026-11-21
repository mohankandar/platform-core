package com.documentplatform.licensing.issue;

import com.documentplatform.licensing.Ed25519;

import java.security.PrivateKey;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads the signing key from an environment variable that the secret store's agent (or the operator's shell, from
 * the secret store) sets for the one run: {@code PLATFORM_LICENCE_KEY_<KEY ID>}, with the key ID upper-cased and every
 * character other than A-Z and 0-9 replaced by '_'. The value is the Base64 PKCS#8 private key. This is the
 * command's default until the Vault-backed resolver exists.
 */
public final class EnvironmentSigningKeyResolver implements SigningKeyResolver {

    public static final String PREFIX = "PLATFORM_LICENCE_KEY_";

    private final Map<String, String> environment;

    public EnvironmentSigningKeyResolver(Map<String, String> environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    public static String variableFor(String keyId) {
        return PREFIX + keyId.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }

    @Override
    public Optional<PrivateKey> resolve(String keyId) {
        String value = environment.get(variableFor(keyId));
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Ed25519.decodePrivate(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(variableFor(keyId) + " does not hold a Base64 PKCS#8 Ed25519 private key");
        }
    }
}
