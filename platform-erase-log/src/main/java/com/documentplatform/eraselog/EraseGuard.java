package com.documentplatform.eraselog;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Keeps a restored system from taking work before the erase replay. The app asks {@link #mayTakeWork()} (or
 * {@link #state()}) before it takes work (intake, case work, exports, sign-ins to the work screens) and refuses with
 * the state's code and message while it is false.
 *
 * <ul>
 *   <li>{@link #checkAtStartup()}: the full check, once at startup, holding the applied position locked (so an erase
 *       in progress on another instance finishes first). If the database is behind the log (restored from a backup, or
 *       stopped between writing an entry and erasing), the restore is <b>recorded in the database</b>
 *       ({@code replay_required}) and the state is {@link EraseLogError#ERASE_LOG_RESTORE_DETECTED}: "Restored from
 *       backup: run the erase replay before starting work". Every instance then stays blocked until the replay has run
 *       and verified ({@link EraseReplay}).</li>
 *   <li>{@link #refresh()}: a cheap recheck (no chain walk, nothing recorded) to see the block lift after the replay
 *       ran, or a restore recorded by another instance. Entries logged but not yet applied while running are an erase
 *       in progress, not a restore.</li>
 * </ul>
 * The system also takes no work while the log cannot be read, its chain is broken, the database has applied more than
 * the log holds, belongs to another log, or is older than the log's oldest kept entry: each with its code.
 */
public final class EraseGuard {

    private final EraseLog log;
    private final ErasePosition position;
    private final Clock clock;
    private volatile State state;

    public EraseGuard(EraseLog log, ErasePosition position, Clock clock) {
        this.log = Objects.requireNonNull(log, "log");
        this.position = Objects.requireNonNull(position, "position");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.state = new State(false, EraseLogError.ERASE_LOG_RESTORE_DETECTED, "The erase log has not been checked "
                + "yet", null, 0, 0, null, false);
    }

    /**
     * The guard's state.
     *
     * @param mayTakeWork  whether the system may take work
     * @param code         null when it may
     * @param logHead      the log's last entry
     * @param applied      the database's applied position
     * @param chainChecked whether this check walked the whole chain (startup), not only the head
     */
    public record State(boolean mayTakeWork, EraseLogError code, String message, UUID logId, long logHead,
                        long applied, Instant checkedAt, boolean chainChecked) {

        /** Entries in the log not yet applied in this database. */
        public long pending() {
            return Math.max(0, logHead - applied);
        }
    }

    public boolean mayTakeWork() {
        return state.mayTakeWork();
    }

    /** The last state found. */
    public State state() {
        return state;
    }

    /** The full check at startup; records a restore when the database is behind the log. Never throws. */
    public State checkAtStartup() {
        return full(true);
    }

    /**
     * The full check while running (for example daily): walks the chain like the startup check, but entries logged and
     * not yet applied are an erase in progress, not a restore, and nothing is recorded. Never throws.
     */
    public State fullCheck() {
        return full(false);
    }

    private State full(boolean startup) {
        Instant now = clock.instant();
        EraseLog.ChainCheck chain = log.verify();
        if (!chain.intact()) {
            return set(blocked(chain.code(), chain.message() + " (erase log at " + log.location() + ")", chain.head(),
                    -1, now, true));
        }
        try {
            return set(position.withLock(log.logId(), locked -> {
                ErasePosition.Position p = locked.position();
                ChainPoint head = log.head();
                Optional<EraseLogException> problem = InStep.problem(log, p, position.otherLogs(log.logId()), head);
                if (problem.isPresent()) {
                    return blocked(problem.get().code(), problem.get().reason(), head, p.appliedSeq(), now, true);
                }
                if (p.replayRequired() || (startup && p.appliedSeq() < head.seq())) {
                    if (!p.replayRequired()) {
                        locked.markRestoreDetected(now);
                    }
                    EraseLogException e = InStep.restore(p.appliedSeq(), head.seq());
                    return blocked(e.code(), e.reason(), head, p.appliedSeq(), now, true);
                }
                return new State(true, null, p.appliedSeq() < head.seq() ? "In step; " + (head.seq()
                        - p.appliedSeq()) + " erase(s) in progress" : "The database is in step with the erase log",
                        log.logId(), head.seq(), p.appliedSeq(), now, true);
            }));
        } catch (EraseLogException e) {
            return set(blocked(e.code(), e.reason(), chain.head(), -1, now, true));
        }
    }

    /** A cheap recheck: the head of the log against the position, and a restore recorded meanwhile. Never throws. */
    public State refresh() {
        Instant now = clock.instant();
        ChainPoint head = ChainPoint.START;
        long applied = -1;
        try {
            head = log.head();
            UUID id = log.logId();
            ErasePosition.Position p = position.read(id);
            applied = p.appliedSeq();
            List<UUID> others = position.otherLogs(id);
            if (p.replayRequired()) {
                EraseLogException e = InStep.restore(applied, head.seq());
                return set(blocked(e.code(), e.reason(), head, applied, now, false));
            }
            Optional<EraseLogException> problem = InStep.problem(log, p, others, head);
            if (problem.isPresent()) {
                return set(blocked(problem.get().code(), problem.get().reason(), head, applied, now, false));
            }
            State last = state;
            if (last.code() == EraseLogError.ERASE_LOG_CHAIN_BROKEN) {
                return last; // a broken chain is only cleared by a full check (startup, or a replay)
            }
            return set(new State(true, null, applied < head.seq() ? "In step; " + (head.seq() - applied)
                    + " erase(s) in progress" : "The database is in step with the erase log", id, head.seq(), applied,
                    now, false));
        } catch (EraseLogException e) {
            return set(blocked(e.code(), e.reason(), head, applied, now, false));
        }
    }

    private State blocked(EraseLogError code, String message, ChainPoint head, long applied, Instant now,
                          boolean full) {
        UUID id;
        try {
            id = log.logId();
        } catch (EraseLogException e) {
            id = null;
        }
        return new State(false, code, message, id, head.seq(), applied, now, full);
    }

    private State set(State s) {
        state = s;
        return s;
    }
}
