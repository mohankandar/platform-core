package com.documentplatform.eraselog;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The erase log: writing an entry, checking the chain, reading entries, pruning. Apps normally use {@link Eraser}
 * (log first, then erase, then the applied position), {@link EraseGuard} and {@link EraseReplay}, which use this.
 */
public final class EraseLog {

    private final EraseLogStore store;
    private final Clock clock;

    public EraseLog(EraseLogStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public UUID logId() {
        return store.logId();
    }

    public String location() {
        return store.location();
    }

    /** Writes the entry durably and returns it with its number and hash. */
    public EraseEntry record(EraseRequest request) {
        return store.append(Objects.requireNonNull(request, "request"), clock.instant());
    }

    public ChainPoint head() {
        return store.head();
    }

    public ChainPoint checkpoint() {
        return store.checkpoint();
    }

    /** The kept entries after {@code seq}, in order. */
    public List<EraseEntry> after(long seq) {
        return store.after(seq);
    }

    /** Every kept entry, in order. */
    public void scan(Consumer<EraseEntry> each) {
        store.scan(each);
    }

    /** The point of entry {@code seq} (the checkpoint's when it is the checkpoint), if the log has it. */
    public Optional<ChainPoint> point(long seq) {
        ChainPoint checkpoint = store.checkpoint();
        if (seq == checkpoint.seq()) {
            return Optional.of(checkpoint);
        }
        if (seq < checkpoint.seq()) {
            return Optional.empty();
        }
        List<EraseEntry> from = store.after(seq - 1);
        return from.isEmpty() || from.get(0).seq() != seq ? Optional.empty() : Optional.of(from.get(0).point());
    }

    /**
     * The chain checked from the checkpoint: numbers without gaps, each entry's {@code prev} is the hash before it,
     * each hash follows from its fields. Reads the whole log. Never throws: a store that cannot be read is reported
     * with its code.
     */
    public ChainCheck verify() {
        ChainPoint checkpoint;
        try {
            checkpoint = store.checkpoint();
        } catch (EraseLogException e) {
            return new ChainCheck(e.code(), e.reason(), ChainPoint.START, ChainPoint.START, 0);
        }
        Walk w = new Walk(store.logId(), checkpoint);
        try {
            store.scan(w);
        } catch (EraseLogException e) {
            return new ChainCheck(e.code(), e.reason(), checkpoint, w.at, w.count);
        }
        return new ChainCheck(w.code, w.code == null ? "intact" : w.message, checkpoint, w.at, w.count);
    }

    private static final class Walk implements Consumer<EraseEntry> {
        final UUID logId;
        ChainPoint at;
        long count;
        EraseLogError code;
        String message;

        Walk(UUID logId, ChainPoint checkpoint) {
            this.logId = logId;
            this.at = checkpoint;
        }

        @Override
        public void accept(EraseEntry e) {
            if (code != null) {
                return;
            }
            if (e.seq() != at.seq() + 1) {
                code = EraseLogError.ERASE_LOG_CHAIN_BROKEN;
                message = "entry " + (at.seq() + 1) + " is missing (found " + e.seq() + " after " + at.seq() + ")";
                return;
            }
            if (!e.prev().equals(at.hash()) || !e.logId().equals(logId) || !e.hashMatches()) {
                code = EraseLogError.ERASE_LOG_CHAIN_BROKEN;
                message = "entry " + e.seq() + " was changed or does not follow entry " + at.seq();
                return;
            }
            at = e.point();
            count++;
        }
    }

    /**
     * Prunes the entries older than the log's retention ({@link EraseRetention#logRetention()}) that the database has
     * applied ({@code seq <= appliedSeq}); the newest pruned entry becomes the checkpoint, so the chain stays
     * verifiable from it. A broken chain is not pruned.
     *
     * @return how many entries were pruned (0 when none was old enough)
     */
    public int prune(Instant now, EraseRetention retention, long appliedSeq) {
        ChainCheck chain = verify();
        if (!chain.intact()) {
            throw new EraseLogException(chain.code(), "not pruned: " + chain.message());
        }
        Instant cutoff = now.minus(retention.logRetention());
        long[] through = {chain.checkpoint().seq()};
        int[] count = {0};
        boolean[] stop = {false};
        store.scan(e -> {
            if (stop[0] || e.seq() > appliedSeq || !e.loggedAt().isBefore(cutoff)) {
                stop[0] = true;
                return;
            }
            through[0] = e.seq();
            count[0]++;
        });
        if (count[0] > 0) {
            store.pruneThrough(through[0]);
        }
        return count[0];
    }

    /**
     * What {@link #verify()} found.
     *
     * @param code       null when intact
     * @param checkpoint where the kept chain starts
     * @param head       the last entry that checks out (the last entry when intact)
     * @param entries    how many kept entries checked out
     */
    public record ChainCheck(EraseLogError code, String message, ChainPoint checkpoint, ChainPoint head,
                             long entries) {
        public boolean intact() {
            return code == null;
        }
    }
}
