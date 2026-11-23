package com.documentplatform.eraselog;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The erase replay after a restore, and the verify step. Run it after restoring the database and before the system
 * takes work; running it again is safe.
 *
 * <ol>
 *   <li>Holding the applied position locked: the chain is checked (a broken chain stops the replay); the position must
 *       fit the log (not another log's, not ahead of it, not older than its oldest kept entry).</li>
 *   <li><b>Replay:</b> every entry after the database's applied position goes through its kind's handler, in order,
 *       and the position moves with each. Something already erased counts as done. A handler that refuses or fails
 *       stops the replay with its code ({@link EraseLogError#ERASE_HANDLER_FAILED}); a kind without a handler with
 *       {@link EraseLogError#ERASE_HANDLER_MISSING}. What was applied before stays applied; a rerun goes on from
 *       there.</li>
 *   <li><b>Verify:</b> every handler confirms that nothing of any entry in the log can be read. Only then is the
 *       restore cleared in the database, and the guard lifts ({@link EraseGuard#refresh()} sees it).</li>
 * </ol>
 */
public final class EraseReplay {

    private final EraseLog log;
    private final ErasePosition position;
    private final EraseHandlers handlers;
    private final Clock clock;
    private final EraseGuard guard;

    public EraseReplay(EraseLog log, ErasePosition position, EraseHandlers handlers, Clock clock) {
        this(log, position, handlers, clock, null);
    }

    /**
     * @param guard told of the entry the replay stopped at ({@link EraseGuard.State#lastFailure()}); may be null
     */
    public EraseReplay(EraseLog log, ErasePosition position, EraseHandlers handlers, Clock clock, EraseGuard guard) {
        this.log = Objects.requireNonNull(log, "log");
        this.position = Objects.requireNonNull(position, "position");
        this.handlers = Objects.requireNonNull(handlers, "handlers");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.guard = guard;
    }

    /**
     * What one run did.
     *
     * @param code      null when the replay and the verify step passed and the guard lifts
     * @param applied   per outcome, how many entries this run applied
     * @param verified  how many entries the verify step checked
     * @param readable  entries the verify step found still readable (their numbers), at most 100
     * @param stoppedAt the entry the replay stopped at, if it stopped
     */
    public record Report(EraseLogError code, String handlerCode, String message, long from, long head,
                         Map<EraseHandler.Applied, Integer> applied, long verified, List<Long> readable,
                         Long stoppedAt, Instant at) {
        public Report {
            applied = Map.copyOf(applied);
            readable = List.copyOf(readable);
        }

        public boolean passed() {
            return code == null;
        }

        public int appliedCount() {
            return applied.values().stream().mapToInt(Integer::intValue).sum();
        }

        /** A few lines for the operator: counts, codes and entry numbers only. */
        public String describe() {
            StringBuilder b = new StringBuilder();
            b.append(passed() ? "Erase replay done and verified" : "Erase replay NOT complete: " + code
                    + (handlerCode == null ? "" : " (" + handlerCode + ")")).append('\n');
            b.append("  database was at entry ").append(from).append(", log at entry ").append(head).append('\n');
            b.append("  applied now: ").append(applied.getOrDefault(EraseHandler.Applied.ERASED, 0))
                    .append(" erased, ").append(applied.getOrDefault(EraseHandler.Applied.ALREADY_GONE, 0))
                    .append(" already gone\n");
            b.append("  verified: ").append(verified).append(" entries").append('\n');
            if (!readable.isEmpty()) {
                b.append("  still readable: entries ").append(readable).append('\n');
            }
            b.append("  ").append(message);
            return b.toString();
        }
    }

    /** Runs the replay and the verify step. Never throws: the report says what happened. */
    public Report run() {
        Instant now = clock.instant();
        Map<EraseHandler.Applied, Integer> applied = new EnumMap<>(EraseHandler.Applied.class);
        long[] from = {-1};
        long[] head = {-1};
        try {
            return position.withLock(log.logId(), locked -> {
                ErasePosition.Position p = locked.position();
                from[0] = p.appliedSeq();
                EraseLog.ChainCheck chain = log.verify();
                head[0] = chain.head().seq();
                if (!chain.intact()) {
                    return failed(chain.code(), null, chain.message() + "; restore the erase log from its own backup",
                            from[0], head[0], applied, 0, List.of(), null, now);
                }
                Optional<EraseLogException> problem = InStep.problem(log, p, position.otherLogs(log.logId()),
                        chain.head());
                if (problem.isPresent()) {
                    return failed(problem.get().code(), null, problem.get().reason(), from[0], head[0], applied, 0,
                            List.of(), null, now);
                }
                List<EraseEntry> pending;
                try {
                    pending = log.pending(p.applied()); // checked again: chain, seal and MACs, before acting
                } catch (EraseLogException x) {
                    return failed(x.code(), null, x.reason() + "; restore the erase log from its own backup", from[0],
                            head[0], applied, 0, List.of(), null, now);
                }
                for (EraseEntry e : pending) {
                    try {
                        applied.merge(Applying.apply(log, handlers, locked, e, guard), 1, Integer::sum);
                    } catch (EraseLogException x) {
                        return failed(x.code(), x.handlerCode().orElse(null), x.reason() + "; fix the cause and run "
                                + "the replay again (it goes on from entry " + e.seq() + ")", from[0], head[0],
                                applied, 0, List.of(), e.seq(), now);
                    }
                }
                Verify v = new Verify();
                try {
                    log.scan(v);
                } catch (EraseLogException x) {
                    return failed(x.code(), null, x.reason(), from[0], head[0], applied, v.count, v.readable, null,
                            now);
                }
                if (v.failure != null) {
                    return failed(EraseLogError.ERASE_VERIFY_FAILED, v.failureCode, v.failure, from[0], head[0],
                            applied, v.count, v.readable, null, now);
                }
                if (!v.readable.isEmpty()) {
                    return failed(EraseLogError.ERASE_VERIFY_FAILED, null, v.readableCount + " entr"
                            + (v.readableCount == 1 ? "y is" : "ies are") + " still readable after the replay; the "
                            + "system stays blocked", from[0], head[0], applied, v.count, v.readable, null, now);
                }
                locked.verified(now);
                return new Report(null, null, "Every entry in the erase log is erased; the system may take work",
                        from[0], head[0], applied, v.count, List.of(), null, now);
            });
        } catch (EraseLogException e) {
            return failed(e.code(), null, e.reason(), from[0], head[0], applied, 0, List.of(), null, now);
        }
    }

    private final class Verify implements java.util.function.Consumer<EraseEntry> {
        long count;
        long readableCount;
        final List<Long> readable = new ArrayList<>();
        String failure;
        String failureCode;

        @Override
        public void accept(EraseEntry e) {
            if (failure != null) {
                return;
            }
            Optional<EraseHandler> h = handlers.find(e.kind());
            if (h.isEmpty()) {
                failure = "no handler is registered for kind " + e.kind() + " (entry " + e.seq() + ")";
                failureCode = EraseLogError.ERASE_HANDLER_MISSING.name();
                return;
            }
            EraseHandler.Verified result;
            try {
                result = h.get().verify(e);
            } catch (EraseHandlerException x) {
                failure = "entry " + e.seq() + " could not be checked: " + x.getMessage();
                failureCode = x.code();
                return;
            } catch (RuntimeException x) {
                failure = "entry " + e.seq() + " could not be checked (" + x.getClass().getSimpleName() + ")";
                failureCode = EraseLogError.ERASE_HANDLER_ERROR.name();
                return;
            }
            count++;
            if (result != EraseHandler.Verified.GONE) {
                readableCount++;
                if (readable.size() < 100) {
                    readable.add(e.seq());
                }
            }
        }
    }

    private static Report failed(EraseLogError code, String handlerCode, String message, long from, long head,
                                 Map<EraseHandler.Applied, Integer> applied, long verified, List<Long> readable,
                                 Long stoppedAt, Instant at) {
        return new Report(code, handlerCode, message, from, head, applied, verified, readable, stoppedAt, at);
    }
}
