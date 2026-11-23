package com.documentplatform.integration.credential;

/** Where a client certificate is in its rotation. An integration has at most one CURRENT and one NEXT. */
public enum CredentialState {
    /** Used for every call. */
    CURRENT,
    /** Added ahead of a change: tested with the Test action, switched to by the admin or at a set time. */
    NEXT,
    /** No longer used (replaced, or discarded before use). Kept for the history; its key is no longer stored. */
    RETIRED
}
