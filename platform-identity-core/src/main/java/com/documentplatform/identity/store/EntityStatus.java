package com.documentplatform.identity.store;

/** Where an entity slot stands in this installation. */
public enum EntityStatus {
    /** In the licence; the Business Admin has not activated it yet. No app entity row exists for it. */
    NOT_ACTIVATED,
    /** Activated and named; people may sign in to it. */
    ACTIVE,
    /** Activated, but nobody may sign in to it for now. */
    SUSPENDED,
    /** Dropped from a newer licence. Kept, never deleted; nobody may sign in to it. */
    NOT_IN_LICENCE;

    /** Whether the slot is activated now (it has an app entity row and is in the licence). */
    public boolean activated() {
        return this == ACTIVE || this == SUSPENDED;
    }
}
