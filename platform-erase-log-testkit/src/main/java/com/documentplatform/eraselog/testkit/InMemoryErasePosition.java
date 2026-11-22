package com.documentplatform.eraselog.testkit;

import com.documentplatform.eraselog.EraseEntry;
import com.documentplatform.eraselog.EraseLogError;
import com.documentplatform.eraselog.EraseLogException;
import com.documentplatform.eraselog.ErasePosition;

import java.sql.Connection;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The applied position in memory, with Postgres' rules (one at a time per log, never moves back). {@link #snapshot()}
 * and {@link #restore(Map)} stand for a database backup and its restore. Test scope only.
 */
public final class InMemoryErasePosition implements ErasePosition {

    private final Map<UUID, Position> rows = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean down;

    /** Every call fails while this is on (the database is down). */
    public InMemoryErasePosition down(boolean down) {
        this.down = down;
        return this;
    }

    /** A copy of the rows: what a backup would hold. */
    public synchronized Map<UUID, Position> snapshot() {
        return Map.copyOf(rows);
    }

    /** Puts back the rows of a backup. */
    public synchronized void restore(Map<UUID, Position> backup) {
        rows.clear();
        rows.putAll(backup);
    }

    @Override
    public synchronized Position read(UUID logId) {
        gate();
        return rows.getOrDefault(logId, Position.start(logId));
    }

    @Override
    public synchronized List<UUID> otherLogs(UUID logId) {
        gate();
        return rows.keySet().stream().filter(id -> !id.equals(logId)).sorted().toList();
    }

    @Override
    public <T> T withLock(UUID logId, Function<Locked, T> work) {
        gate();
        lock.lock();
        try {
            return work.apply(new MemLocked(read(logId)));
        } finally {
            lock.unlock();
        }
    }

    private final class MemLocked implements Locked {
        private Position position;

        MemLocked(Position position) {
            this.position = position;
        }

        @Override
        public Position position() {
            return position;
        }

        @Override
        public <T> T applyAndAdvance(EraseEntry entry, Supplier<T> apply) {
            if (entry.seq() != position.appliedSeq() + 1 || !entry.prev().equals(position.applied().hash())) {
                throw new IllegalStateException("entry " + entry.seq() + " is not the next after "
                        + position.appliedSeq());
            }
            T result = apply.get();
            gate();
            position = new Position(position.logId(), entry.point(), position.replayRequired(),
                    position.restoreDetected(), position.verifiedAt());
            save(position);
            return result;
        }

        @Override
        public void markRestoreDetected(Instant at) {
            gate();
            position = new Position(position.logId(), position.applied(), true,
                    position.restoreDetected() == null ? at : position.restoreDetected(), position.verifiedAt());
            save(position);
        }

        @Override
        public void verified(Instant at) {
            gate();
            position = new Position(position.logId(), position.applied(), false, null, at);
            save(position);
        }

        @Override
        public Optional<Connection> connection() {
            return Optional.empty();
        }
    }

    private synchronized void save(Position p) {
        Position old = rows.get(p.logId());
        if (old != null && p.appliedSeq() < old.appliedSeq()) {
            throw new IllegalStateException("the applied position never moves back");
        }
        rows.put(p.logId(), p);
    }

    private void gate() {
        if (down) {
            throw new EraseLogException(EraseLogError.ERASE_POSITION_FAILED, "the in-memory position is switched off "
                    + "by the test");
        }
    }
}
