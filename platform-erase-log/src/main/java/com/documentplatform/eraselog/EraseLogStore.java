package com.documentplatform.eraselog;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Where the erase log is kept: <b>outside the database it protects</b>, on storage backed up separately, so that
 * restoring the database cannot roll the log back. Implementations: {@code file.FileEraseLogStore} (append-only file
 * on a configured location); tests: the in-memory store of {@code platform-erase-log-testkit}.
 *
 * <p>Every method throws {@link EraseLogException} ({@link EraseLogError#ERASE_LOG_UNREACHABLE},
 * {@link EraseLogError#ERASE_LOG_FORMAT}). Implementations are safe for several threads and, for the file store,
 * several processes writing the same log.
 *
 * <p>SPI: implemented by stores; apps call {@link Eraser} (and {@link EraseReplay}, {@link EraseGuard}), never this
 * store: an entry appended outside {@code Eraser} is an erasure nobody did.
 */
public interface EraseLogStore {

    /** The log's own ID, made when the log was created. The applied position in the database names it. */
    UUID logId();

    /**
     * Adds the entry after the current last one ({@link EraseEntry#link}) and returns once it is <b>durably</b>
     * written (on disk, not in a cache). Only then may the erasure be acted on.
     */
    EraseEntry append(EraseRequest request, Instant loggedAt);

    /** The last pruned entry's point; {@link ChainPoint#START} when nothing was pruned. */
    ChainPoint checkpoint();

    /** The last entry's point; the checkpoint when no entry is kept. Cheap (reads the end only). */
    ChainPoint head();

    /** The kept entries after {@code seq}, in order. Cheap for the newest few (reads from the end). */
    List<EraseEntry> after(long seq);

    /** Every kept entry after the checkpoint, in order, one at a time (the log may be large). No chain check. */
    void scan(Consumer<EraseEntry> each);

    /** Removes the entries up to and including {@code seq}; the checkpoint becomes that entry's point. */
    void pruneThrough(long seq);

    /** Where the log is, for messages and health (a path, never a secret). */
    String location();

    /**
     * Like {@link #append(EraseRequest, Instant)}, with the entry's MAC under {@code key} (P044, SEC-3), computed
     * inside the store's lock so it covers the entry's final number and place. The default writes unkeyed entries
     * only.
     */
    default EraseEntry append(EraseRequest request, Instant loggedAt, ChainKey key) {
        if (key == null) return append(request, loggedAt);
        throw new EraseLogException(EraseLogError.ERASE_LOG_SETTINGS_INVALID, "this erase log store cannot write "
                + "keyed entries");
    }

    /** The log's seal (where its keyed part starts), if it has one. */
    default Optional<Seal> seal() {
        return Optional.empty();
    }

    /**
     * Seals the log at its current head, holding the store's lock, and returns the seal; returns the existing seal
     * when the log has one. Writes the seal only; no entry is rewritten.
     */
    default Seal sealAtHead(ChainKey key) {
        throw new EraseLogException(EraseLogError.ERASE_LOG_SETTINGS_INVALID, "this erase log store cannot be sealed");
    }

    /**
     * The highest entry any database has recorded as applied with this log (P044, SEC-4), kept with the log, outside
     * the database: a database position below it means the database was restored. 0 when unknown.
     */
    default long appliedHighWater() {
        return 0;
    }

    /** Raises {@link #appliedHighWater()} to {@code seq} (never lowers it). */
    default void recordApplied(long seq) {
    }
}
