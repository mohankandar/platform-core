package com.documentplatform.licensing.issue;

/** Why the issuing command refused to issue. Stable codes; each has one exact cause in the message. */
public enum IssueError implements com.documentplatform.errors.PlatformCode {
    ISSUE_BAD_OPTION("An option is unknown, repeated or has a value of the wrong shape."),
    ISSUE_MISSING_OPTION("A required option is missing."),
    ISSUE_INVALID_LICENCE("The options do not make a valid licence."),
    ISSUE_INSTALL_TOO_LONG("An install licence runs for at most 60 days."),
    ISSUE_UNKNOWN_TENANT("The tenant ID given is not in the issued records."),
    ISSUE_TENANT_CUSTOMER_MISMATCH("The tenant ID given was issued to a different customer."),
    ISSUE_TENANT_KIND_MISMATCH("Non-production tenants and production/install tenants never share IDs."),
    ISSUE_UNKNOWN_SLOT("A slot ID given was not issued to this tenant."),
    ISSUE_ID_COLLISION("No unused ID could be generated."),
    ISSUE_RECORDS_UNREADABLE("The issued-records file cannot be read."),
    ISSUE_RECORDS_UNWRITABLE("The issued-records file cannot be written."),
    ISSUE_OUTPUT_EXISTS("The output licence file already exists; it is never overwritten."),
    ISSUE_OUTPUT_UNWRITABLE("The licence file cannot be written."),
    ISSUE_KEY_NOT_FOUND("The signer has no signing key (or no such key version) under this key ID."),
    ISSUE_KEY_INVALID("The key under this key ID is not an Ed25519 signing key."),
    ISSUE_KEY_UNAVAILABLE("The signer failed in an unexpected way."),
    ISSUE_KEY_EXPORTABLE("The Vault key allows export or plaintext backup, so its private key could leave Vault; it is"
            + " not used."),
    ISSUE_SIGNATURE_MISMATCH("The signature returned does not verify with the key's public key; nothing was written."),
    ISSUE_VAULT_NO_TOKEN("No Vault credentials: VAULT_TOKEN is not set and no --vault-login was given."),
    ISSUE_VAULT_LOGIN_REFUSED("Vault refused the login (wrong user name or password, or the user is disabled)."),
    ISSUE_VAULT_PERMISSION_DENIED("Vault refused the request: the token does not carry the licensing-signer policy,"
            + " or has expired."),
    ISSUE_VAULT_UNREACHABLE("Vault could not be reached at the address given (connection refused, timeout or"
            + " unknown host)."),
    ISSUE_VAULT_TLS("The TLS connection to Vault failed: its certificate is not trusted by this Java runtime."),
    ISSUE_VAULT_SEALED("Vault answered HTTP 503: it is sealed or not yet ready, and cannot sign until it is unsealed"
            + " and ready."),
    ISSUE_VAULT_ERROR("Vault answered with an error or a reply this command does not understand, or a standby node"
            + " redirected the request to the active node (redirects are not followed: use the active node's"
            + " address).");

    private final String meaning;

    IssueError(String meaning) {
        this.meaning = meaning;
    }

    public String meaning() {
        return meaning;
    }
}
