package com.documentplatform.licensing.issue;

/** Why the issuing command refused to issue. Stable codes; each has one exact cause in the message. */
public enum IssueError {
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
    ISSUE_KEY_NOT_FOUND("The secret store has no signing key under this key ID."),
    ISSUE_KEY_INVALID("The secret store's value for this key ID is not an Ed25519 private key."),
    ISSUE_KEY_UNAVAILABLE("The secret store could not be reached.");

    private final String meaning;

    IssueError(String meaning) {
        this.meaning = meaning;
    }

    public String meaning() {
        return meaning;
    }
}
