package com.documentplatform.licensing.issue;

import java.security.PublicKey;
import java.util.Map;

/**
 * Signs licences and last-seen resets on our side (the issuing command, later the Platform Console). The private key
 * stays with the signer: only the message to sign goes in, only the 64-byte Ed25519 signature comes out. The
 * production signer is {@link VaultTransitSigner}; the key never leaves Vault.
 *
 * <p>Implementations never log, print or keep on disk a key, a token or a password, and their refusals carry an
 * {@link IssueError} code and IDs only.
 */
public interface Signer extends AutoCloseable {

    /**
     * The key ID that will sign, as written into the licence. A signer may complete a short form: Vault turns a bare
     * Transit key name ({@code lic-2026}) into its latest version ({@code lic-2026:2}).
     *
     * @throws IssueException with the code of the refusal
     */
    default String resolveKeyId(String requested) {
        return requested;
    }

    /**
     * @return the 64-byte Ed25519 signature of {@code message} by the key {@code keyId}
     * @throws IssueException with the code of the refusal ({@link IssueError#ISSUE_KEY_NOT_FOUND},
     *                        {@link IssueError#ISSUE_KEY_INVALID}, {@link IssueError#ISSUE_VAULT_PERMISSION_DENIED}, ...)
     */
    byte[] sign(String keyId, byte[] message);

    /**
     * The public keys of every version of the named key, by key ID, for the trusted-keys file apps ship.
     *
     * @throws IssueException with the code of the refusal; {@link IssueError#ISSUE_BAD_OPTION} if this signer cannot
     *                        list public keys
     */
    default Map<String, PublicKey> publicKeys(String keyName) {
        throw new IssueException(IssueError.ISSUE_BAD_OPTION, "This signer cannot export public keys.");
    }

    /** What the run log records about the signer (for example "vault-transit"); never a secret. */
    default String describe() {
        return getClass().getSimpleName();
    }

    /**
     * References to the signer's own audit trail for the signatures made in this run (Vault request IDs), so our run
     * log and Vault's audit log can be matched. Never a secret.
     */
    default java.util.List<String> auditReferences() {
        return java.util.List.of();
    }

    /** Ends the signer's session (Vault: revokes a token it logged in for). Never throws. */
    @Override
    default void close() {
    }
}
