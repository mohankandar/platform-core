package com.documentplatform.eraselog;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The erase log: writing an entry, checking the chain, reading entries, pruning. Apps normally use {@link Eraser}
 * (log first, then erase, then the applied position), {@link EraseGuard} and {@link EraseReplay}, which use this.
 *
 * <p><b>Keyed log</b> (P044, SEC-3; built with a {@link ChainKey}): every new entry carries a MAC under the key, and the
 * log carries a {@link Seal} that says from which entry on MACs are required and fixes the hash before it. The chain
 * check then also requires the seal and every MAC from it on, and the eraser and the replay act only on entries that
 * pass, so someone who can write the log's location but has no use of the key cannot add an erasure the system would
 * act on. A new log is sealed when its first entry is written; an existing log once, by {@link #seal()} (the explicit
 * migration, {@code --erase-log-seal}), which rewrites no entry. Until then a keyed log takes no work
 * ({@link EraseLogError#ERASE_LOG_NOT_SEALED}).
 */
public final class EraseLog {

    private final EraseLogStore store;
    private final Clock clock;
    private final ChainKey key;

    /** An unkeyed log (entries chained by SHA-256 only). */
    public EraseLog(EraseLogStore store, Clock clock) {
        this(store, clock, null);
    }

    /** A keyed log when {@code key} is given (see the class description); unkeyed when it is null. */
    public EraseLog(EraseLogStore store, Clock clock, ChainKey key) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.key = key;
    }

    public UUID logId() {
        return store.logId();
    }

    public String location() {
        return store.location();
    }

    /** Whether this log is keyed. */
    public boolean keyed() {
        return key != null;
    }

    /**
     * Writes the entry durably and returns it with its number and hash (and MAC, when keyed). A keyed log with no
     * entry yet is sealed first; a keyed log with entries and no seal refuses ({@code ERASE_LOG_NOT_SEALED}).
     */
    public EraseEntry record(EraseRequest request) {
        Objects.requireNonNull(request, "request");
        if (key != null) requireSealed(true);
        return store.append(request, clock.instant(), key);
    }

    public ChainPoint head() {
        return store.head();
    }

    public ChainPoint checkpoint() {
        return store.checkpoint();
    }

    /** The kept entries after {@code seq}, in order. Not checked: to act on them use {@link #pending}. */
    public List<EraseEntry> after(long seq) {
        return store.after(seq);
    }

    /**
     * The entries after the database's applied position {@code applied}, each checked before anything acts on it: it
     * follows the one before (number and {@code prev}), its hash follows from its fields, and in a keyed log the seal
     * is valid, the hash before the seal is the sealed one, and every entry from the seal on has a valid MAC.
     *
     * @throws EraseLogException {@code ERASE_LOG_CHAIN_BROKEN} naming the first bad entry, {@code ERASE_LOG_NOT_SEALED}
     */
    public List<EraseEntry> pending(ChainPoint applied) {
        Seal seal = key == null ? null : requireSealed(false);
        List<EraseEntry> out = new ArrayList<>();
        ChainPoint at = applied;
        for (EraseEntry e : store.after(applied.seq())) {
            String bad = problem(e, at, seal);
            if (bad != null) throw new EraseLogException(EraseLogError.ERASE_LOG_CHAIN_BROKEN, bad);
            out.add(e);
            at = e.point();
        }
        if (seal != null) {
            String bad = sealedEnd(at, seal);
            if (bad != null) throw new EraseLogException(EraseLogError.ERASE_LOG_CHAIN_BROKEN, bad);
        }
        return out;
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
     * The explicit migration of an existing log to a keyed one (P044, SEC-3): checks the whole chain, then seals the
     * log at its head, so the entries written so far are fixed through the hash chain and every later one needs a MAC.
     * Writes the seal only and rewrites no entry; safe to run again (returns the existing seal).
     *
     * @throws EraseLogException {@code ERASE_LOG_SETTINGS_INVALID} for an unkeyed log, the chain's code when it is
     *                           broken (nothing sealed), {@code ERASE_LOG_KEY_UNAVAILABLE}
     */
    public Seal seal() {
        if (key == null) {
            throw new EraseLogException(EraseLogError.ERASE_LOG_SETTINGS_INVALID, "the erase log has no key to seal it "
                    + "with");
        }
        Optional<Seal> existing = store.seal();
        if (existing.isPresent()) {
            ChainCheck chain = verify();
            if (!chain.intact()) throw new EraseLogException(chain.code(), "not sealed: " + chain.message());
            return existing.get();
        }
        ChainCheck chain = walk(null);
        if (!chain.intact()) {
            throw new EraseLogException(chain.code(), "not sealed: " + chain.message());
        }
        return store.sealAtHead(key);
    }

    /** The log's seal, if it has one (not checked). */
    public Optional<Seal> sealRecord() {
        return store.seal();
    }

    /**
     * The chain checked from the checkpoint: numbers without gaps, each entry's {@code prev} is the hash before it,
     * each hash follows from its fields; for a keyed log also the seal and the MACs (see the class description).
     * Reads the whole log. Never throws: a store that cannot be read is reported with its code, and the message names
     * the first bad entry.
     */
    public ChainCheck verify() {
        Seal seal = null;
        if (key != null) {
            try {
                seal = requireSealed(false);
            } catch (EraseLogException e) {
                ChainPoint cp;
                try {
                    cp = store.checkpoint();
                } catch (EraseLogException x) {
                    cp = ChainPoint.START;
                }
                return new ChainCheck(e.code(), e.reason(), cp, cp, 0);
            }
        }
        return walk(seal);
    }

    private ChainCheck walk(Seal seal) {
        ChainPoint checkpoint;
        try {
            checkpoint = store.checkpoint();
        } catch (EraseLogException e) {
            return new ChainCheck(e.code(), e.reason(), ChainPoint.START, ChainPoint.START, 0);
        }
        if (seal != null && checkpoint.seq() == seal.from() - 1 && !checkpoint.hash().equals(seal.anchor())) {
            return new ChainCheck(EraseLogError.ERASE_LOG_CHAIN_BROKEN, "the checkpoint is not the sealed point "
                    + seal.from() + " follows", checkpoint, checkpoint, 0);
        }
        Walk w = new Walk(checkpoint, seal);
        try {
            store.scan(w);
        } catch (EraseLogException e) {
            return new ChainCheck(e.code(), e.reason(), checkpoint, w.at, w.count);
        }
        if (w.code == null && seal != null) {
            String bad = sealedEnd(w.at, seal);
            if (bad != null) return new ChainCheck(EraseLogError.ERASE_LOG_CHAIN_BROKEN, bad, checkpoint, w.at, w.count);
        }
        return new ChainCheck(w.code, w.code == null ? "intact" : w.message, checkpoint, w.at, w.count);
    }

    /** The end of the chain against the seal: never before the sealed point, and the sealed hash when at it. */
    private static String sealedEnd(ChainPoint end, Seal seal) {
        if (end.seq() < seal.from() - 1) {
            return "the erase log ends at entry " + end.seq() + ", before the sealed entry " + (seal.from() - 1)
                    + ": entries were removed";
        }
        if (end.seq() == seal.from() - 1 && !end.hash().equals(seal.anchor())) {
            return "entry " + end.seq() + " is not the sealed one: the entries before the seal were changed";
        }
        return null;
    }

    /** What is wrong with {@code e} following {@code at}; null when nothing. */
    private String problem(EraseEntry e, ChainPoint at, Seal seal) {
        if (e.seq() != at.seq() + 1) {
            return "entry " + (at.seq() + 1) + " is missing (found " + e.seq() + " after " + at.seq() + ")";
        }
        if (!e.prev().equals(at.hash()) || !e.logId().equals(store.logId()) || !e.hashMatches()) {
            return "entry " + e.seq() + " was changed or does not follow entry " + at.seq();
        }
        if (seal != null) {
            if (e.seq() == seal.from() && !e.prev().equals(seal.anchor())) {
                return "entry " + e.seq() + " does not follow the sealed entry " + (seal.from() - 1)
                        + ": the entries before it were changed";
            }
            if (e.seq() >= seal.from() && (!e.keyed() || !e.macMatches(key))) {
                return "entry " + e.seq() + " has no valid MAC: it was written without the erase log's key, or changed";
            }
        }
        return null;
    }

    /** The valid seal of a keyed log; a log without entries is sealed now when {@code sealIfEmpty}. */
    private Seal requireSealed(boolean sealIfEmpty) {
        Optional<Seal> s = store.seal();
        if (s.isEmpty()) {
            if (sealIfEmpty && store.head().seq() == 0) return store.sealAtHead(key);
            throw new EraseLogException(EraseLogError.ERASE_LOG_NOT_SEALED, "the erase log at " + store.location()
                    + " is to be keyed but has no seal: seal it once with --erase-log-seal");
        }
        Seal seal = s.get();
        if (!seal.logId().equals(store.logId()) || !seal.matches(key)) {
            throw new EraseLogException(EraseLogError.ERASE_LOG_NOT_SEALED, "the erase log's seal at "
                    + store.location() + " was not made with this log's key, or was changed; restore erase-log.seal "
                    + "from the log's own backup");
        }
        return seal;
    }

    private final class Walk implements Consumer<EraseEntry> {
        final Seal seal;
        ChainPoint at;
        long count;
        EraseLogError code;
        String message;

        Walk(ChainPoint checkpoint, Seal seal) {
            this.at = checkpoint;
            this.seal = seal;
        }

        @Override
        public void accept(EraseEntry e) {
            if (code != null) {
                return;
            }
            String bad = problem(e, at, seal);
            if (bad != null) {
                code = EraseLogError.ERASE_LOG_CHAIN_BROKEN;
                message = bad;
                return;
            }
            at = e.point();
            count++;
        }
    }

    /** The highest entry any database recorded as applied with this log (P044, SEC-4); 0 when unknown. */
    public long appliedHighWater() {
        return store.appliedHighWater();
    }

    /** Records that a database has applied entry {@code seq} (raises the high-water mark only). */
    void recordApplied(long seq) {
        store.recordApplied(seq);
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
