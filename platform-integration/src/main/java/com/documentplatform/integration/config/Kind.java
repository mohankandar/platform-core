package com.documentplatform.integration.config;

/**
 * What the integration does: {@link #API_PUSH} and {@link #CALLBACK} run by the call engine, {@link #FOLDER_EXPORT}
 * by {@code folder.FolderExport} (P025), {@link #API_POLL} by {@code engine.PollEngine} and {@link #WATCHED_FOLDER}
 * by {@code watch.WatchedFolder} (P027).
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
