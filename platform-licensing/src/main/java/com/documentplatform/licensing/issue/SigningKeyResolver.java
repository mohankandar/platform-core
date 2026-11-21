package com.documentplatform.licensing.issue;

import java.security.PrivateKey;
import java.util.Optional;

/**
 * Reads a licence signing key from the secret store, by key ID. Our side only (the issuing command and later the
 * Platform Console); apps never have one. Implementations must not log, cache to disk or print the key.
 */
public interface SigningKeyResolver {

    /**
     * @return the Ed25519 private key, or empty if the store has no key under this ID ({@code ISSUE_KEY_NOT_FOUND})
     * @throws IllegalArgumentException if what the store holds is not an Ed25519 private key ({@code ISSUE_KEY_INVALID});
     *                                  the message must not contain the stored value
     * @throws RuntimeException         if the store cannot be reached ({@code ISSUE_KEY_UNAVAILABLE})
     */
    Optional<PrivateKey> resolve(String keyId);
}
