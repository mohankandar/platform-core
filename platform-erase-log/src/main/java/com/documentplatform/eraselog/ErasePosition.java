package com.documentplatform.eraselog;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * The erase log's applied position, kept <b>in the app's own database</b> (the database the log protects): up to which
 * entry the erasures are in this database. Restoring the database from a backup restores an older position, which is
 * how the guard sees a restore. Implementations: {@code jdbc.PostgresErasePosition} ({@code erl_position}); tests: the
 * in-memory one of {@code platform-erase-log-testkit}.
 *
 * <p>Failures: {@link EraseLogException} with {@link EraseLogError#ERASE_POSITION_FAILED}.
 *
 * <p>SPI: implemented by stores; apps call {@link Eraser} and {@link EraseReplay}, never this interface. Only
 * those two call {@link Locked#applyAndAdvance} (moving the position past an entry) and {@link Locked#verified}
 * (lifting the restore guard after a replay); an app that called them could skip an erasure or lift the guard
 * without replaying.
 */
public interface ErasePosition {

    /** The position for this log; {@link Position#start} when the database has none yet. Does not lock. */
    Position read(UUID logId);

    /** The IDs of other logs this database has a position for (not empty means it was used with another log). */
    List<UUID> otherLogs(UUID logId);

    /**
     * Runs {@code work} holding this log's position locked (Postgres: an advisory lock per log), so erasures and
     * the replay apply entries one at a time and in order, also across app instances. Whatever
     * {@link Locked#applyAndAdvance} recorded is committed at once and kept, even when {@code work} then throws.
     */
    <T> T withLock(UUID logId, Function<Locked, T> work);

    /** The locked position, inside {@link #withLock}. */
    interface Locked {

        Position position();

        /**
         * Runs {@code apply} (one handler applying {@code entry}), then records {@code entry} as applied, in one
         * transaction when the store has one: if {@code apply} throws, what it did through {@link #connection()} is
         * undone and the position is not moved. {@code entry} must be the next one after the position.
         */
        <T> T applyAndAdvance(EraseEntry entry, java.util.function.Supplier<T> apply);

        /** Marks that a restore was seen: work stays blocked until {@link #verified} after a replay. */
        void markRestoreDetected(Instant at);

        /** The replay ran and every handler confirmed nothing in the log is readable: the guard lifts. */
        void verified(Instant at);

        /**
         * The database transaction that also moves the position, when the store has one: a handler that erases in this
         * same database may use it, so the erase and the position commit together. Empty for stores without one.
         */
        Optional<Connection> connection();
    }

    /**
     * Where this database stands.
     *
     * @param applied         the last entry applied in this database ({@link ChainPoint#START} for none)
     * @param replayRequired  a restore was seen and the replay has not been verified since
     * @param restoreDetected when the restore was seen, if it was
     * @param verifiedAt      when the last replay was verified, if ever
     */
    record Position(UUID logId, ChainPoint applied, boolean replayRequired, Instant restoreDetected,
                    Instant verifiedAt) {
        public Position {
            Objects.requireNonNull(logId, "logId");
            Objects.requireNonNull(applied, "applied");
        }

        public static Position start(UUID logId) {
            return new Position(logId, ChainPoint.START, false, null, null);
        }

        public long appliedSeq() {
            return applied.seq();
        }
    }
}
