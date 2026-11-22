package com.documentplatform.eraselog.testkit;

import com.documentplatform.eraselog.ChainPoint;
import com.documentplatform.eraselog.EraseEntry;
import com.documentplatform.eraselog.EraseLogError;
import com.documentplatform.eraselog.EraseLogException;
import com.documentplatform.eraselog.EraseLogStore;
import com.documentplatform.eraselog.EraseRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * An erase log in memory, with the file store's rules (numbers, chain, checkpoint after pruning). A test can make it
 * unreachable ({@link #down(boolean)}) or tamper with it ({@link #replace}, {@link #remove}) to see the chain check
 * fail. Test scope only.
 */
public final class InMemoryEraseLogStore implements EraseLogStore {

    private final UUID logId;
    private final List<EraseEntry> entries = new ArrayList<>();
    private ChainPoint checkpoint = ChainPoint.START;
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

    /** Removes entry {@code seq} (tampering). */
    public synchronized void remove(long seq) {
        entries.removeIf(e -> e.seq() == seq);
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
        gate();
        EraseEntry e = EraseEntry.link(logId, headPoint(), loggedAt, request);
        entries.add(e);
        return e;
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
