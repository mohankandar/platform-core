package com.documentplatform.eraselog.testkit;

import com.documentplatform.eraselog.ChainKey;
import com.documentplatform.eraselog.ChainPoint;
import com.documentplatform.eraselog.EraseEntry;
import com.documentplatform.eraselog.EraseLogError;
import com.documentplatform.eraselog.EraseLogException;
import com.documentplatform.eraselog.EraseLogStore;
import com.documentplatform.eraselog.EraseRequest;
import com.documentplatform.eraselog.Seal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * An erase log in memory, with the file store's rules (numbers, chain, checkpoint after pruning, keyed entries, the
 * seal, the applied high-water mark). A test can make it unreachable ({@link #down(boolean)}) or tamper with it
 * ({@link #replace}, {@link #remove}, {@link #forge}, {@link #dropSeal}, {@link #lowerAppliedHighWater}) to see the
 * checks fail. Test scope only.
 */
public final class InMemoryEraseLogStore implements EraseLogStore {

    private final UUID logId;
    private final List<EraseEntry> entries = new ArrayList<>();
    private ChainPoint checkpoint = ChainPoint.START;
    private Seal seal;
    private long appliedHighWater;
    private volatile boolean down;

    public InMemoryEraseLogStore() {
        this(UUID.randomUUID());
    }

    public InMemoryEraseLogStore(UUID logId) {
        this.logId = logId;
    }

    /** Every call fails as unreachable while this is on (the log's volume is not mounted). */
    public InMemoryEraseLogStore down(boolean down) {
        this.down = down;
        return this;
    }

    /** Replaces entry {@code seq} by what {@code change} makes of it, keeping its stored hash as it is. */
    public synchronized void replace(long seq, UnaryOperator<EraseEntry> change) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).seq() == seq) {
                entries.set(i, change.apply(entries.get(i)));
                return;
            }
        }
        throw new IllegalArgumentException("no entry " + seq);
    }

    /**
     * Tampering by someone who can write the log but has no use of its key: appends a correctly hash-chained entry
     * without a MAC (or with the MAC of {@code otherKey}, when given).
     */
    public synchronized EraseEntry forge(EraseRequest request, Instant loggedAt, ChainKey otherKey) {
        EraseEntry e = EraseEntry.link(logId, headPoint(), loggedAt, request, otherKey);
        entries.add(e);
        return e;
    }

    /** Removes entry {@code seq} (tampering). */
    public synchronized void remove(long seq) {
        entries.removeIf(e -> e.seq() == seq);
    }

    /** Removes the seal (tampering). */
    public synchronized void dropSeal() {
        seal = null;
    }

    /** Sets the applied high-water mark back (tampering). */
    public synchronized void lowerAppliedHighWater(long seq) {
        appliedHighWater = seq;
    }

    /** How many entries are kept. */
    public synchronized int size() {
        return entries.size();
    }

    @Override
    public UUID logId() {
        return logId;
    }

    @Override
    public synchronized EraseEntry append(EraseRequest request, Instant loggedAt) {
        return append(request, loggedAt, null);
    }

    @Override
    public synchronized EraseEntry append(EraseRequest request, Instant loggedAt, ChainKey key) {
        gate();
        EraseEntry e = EraseEntry.link(logId, headPoint(), loggedAt, request, key);
        entries.add(e);
        return e;
    }

    @Override
    public synchronized Optional<Seal> seal() {
        gate();
        return Optional.ofNullable(seal);
    }

    @Override
    public synchronized Seal sealAtHead(ChainKey key) {
        gate();
        if (seal == null) seal = Seal.make(key, logId, headPoint());
        return seal;
    }

    @Override
    public synchronized long appliedHighWater() {
        gate();
        return appliedHighWater;
    }

    @Override
    public synchronized void recordApplied(long seq) {
        gate();
        appliedHighWater = Math.max(appliedHighWater, seq);
    }

    @Override
    public synchronized ChainPoint checkpoint() {
        gate();
        return checkpoint;
    }

    @Override
    public synchronized ChainPoint head() {
        gate();
        return headPoint();
    }

    @Override
    public synchronized List<EraseEntry> after(long seq) {
        gate();
        return entries.stream().filter(e -> e.seq() > seq).toList();
    }

    @Override
    public void scan(Consumer<EraseEntry> each) {
        List<EraseEntry> copy;
        synchronized (this) {
            gate();
            copy = List.copyOf(entries);
        }
        copy.forEach(each);
    }

    @Override
    public synchronized void pruneThrough(long seq) {
        gate();
        EraseEntry at = entries.stream().filter(e -> e.seq() == seq).findFirst().orElseThrow(() ->
                new EraseLogException(EraseLogError.ERASE_LOG_FORMAT, "entry " + seq + " is not in the log"));
        entries.removeIf(e -> e.seq() <= seq);
        checkpoint = at.point();
    }

    @Override
    public String location() {
        return "memory:" + logId;
    }

    private ChainPoint headPoint() {
        return entries.isEmpty() ? checkpoint : entries.get(entries.size() - 1).point();
    }

    private void gate() {
        if (down) {
            throw new EraseLogException(EraseLogError.ERASE_LOG_UNREACHABLE, "the in-memory erase log is switched off "
                    + "by the test");
        }
    }
}
