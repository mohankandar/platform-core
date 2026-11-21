package com.documentplatform.licensing.issue;

import com.documentplatform.licensing.Ed25519;

import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Tests only: signs with a Base64 PKCS#8 private key held in an environment variable
 * {@code PLATFORM_LICENCE_KEY_<KEY ID>} (key ID upper-cased, every character other than A-Z and 0-9 as '_'). This was
 * the issuing command's default before P011; production signing is {@link VaultTransitSigner}, where the private key
 * never leaves Vault.
 */
final class EnvironmentSigner implements Signer {

    static final String PREFIX = "PLATFORM_LICENCE_KEY_";

    private final Map<String, String> environment;

    EnvironmentSigner(Map<String, String> environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    static String variableFor(String keyId) {
        return PREFIX + keyId.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }

    @Override
    public byte[] sign(String keyId, byte[] message) {
        String value = environment.get(variableFor(keyId));
        if (value == null || value.isBlank()) {
            throw new IssueException(IssueError.ISSUE_KEY_NOT_FOUND, "Key ID " + keyId + ".");
        }
        PrivateKey key;
        try {
            key = Ed25519.decodePrivate(value);
        } catch (IllegalArgumentException e) {
            throw new IssueException(IssueError.ISSUE_KEY_INVALID, "Key ID " + keyId + ".");
        }
        try {
            return Ed25519.sign(key, message);
        } catch (InvalidKeyException e) {
            throw new IssueException(IssueError.ISSUE_KEY_INVALID, "Key ID " + keyId + ".");
        }
    }
}
