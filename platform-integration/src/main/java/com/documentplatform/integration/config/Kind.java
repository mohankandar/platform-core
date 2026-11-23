package com.documentplatform.integration.config;

/**
 * What the integration does. The call engine runs {@link #API_PUSH}; the others are part of the model so their
 * configurations can be stored and versioned now, and are built in later tasks (folder export P025, callbacks P026,
 * intake P027).
 */
public enum Kind {
    /** Outbound: configured HTTP calls to a destination's API. */
    API_PUSH(Direction.OUTBOUND),
    /** Outbound: files to a folder or share with a manifest written last. */
    FOLDER_EXPORT(Direction.OUTBOUND),
    /** Inbound: polling a source's API. */
    API_POLL(Direction.INBOUND),
    /** Inbound: a watched folder or share with a trigger file. */
    WATCHED_FOLDER(Direction.INBOUND),
    /** Outbound: a signed notice to a configured receiver that a case is done or failed (through an outbox). */
    CALLBACK(Direction.OUTBOUND);

    private final Direction direction;

    Kind(Direction direction) {
        this.direction = direction;
    }

    public Direction direction() {
        return direction;
    }
}
