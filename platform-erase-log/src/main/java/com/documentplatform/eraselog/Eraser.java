package com.documentplatform.eraselog;

import java.util.List;
import java.util.Objects;

/**
 * The everyday erase, in the decided order:
 * <ol>
 *   <li>the kind's handler checks the erasure may go ahead ({@link EraseHandler#check}; for example a legal hold);
 *       a refusal logs nothing;</li>
 *   <li>holding the applied position locked: entries already in the log but not yet applied in this database (an
 *       earlier erase that stopped half-way) are applied first, in order;</li>
 *   <li>the entry is written to the log, <b>durably</b>;</li>
 *   <li>the handler erases, and the applied position moves to the entry (one transaction when the handler uses the
 *       position's connection; otherwise right after the handler's own, which is safe because applying twice is).</li>
 * </ol>
 * A failure after step 3 leaves the entry in the log, not applied: the next erase or the replay finishes it, and until
 * then later erasures stop with the same failure. A restored database ({@link EraseGuard}) takes no erase.
 */
public final class Eraser {

    private final EraseLog log;
    private final ErasePosition position;
    private final EraseHandlers handlers;
    private final EraseGuard guard;

    public Eraser(EraseLog log, ErasePosition position, EraseHandlers handlers) {
        this(log, position, handlers, null);
    }

    /**
     * @param guard told of an entry that could not be applied ({@link EraseGuard.State#lastFailure()}), for the admin
     *              screen; may be null
     */
    public Eraser(EraseLog log, ErasePosition position, EraseHandlers handlers, EraseGuard guard) {
        this.log = Objects.requireNonNull(log, "log");
        this.position = Objects.requireNonNull(position, "position");
        this.handlers = Objects.requireNonNull(handlers, "handlers");
        this.guard = guard;
    }

    /**
     * What one erase did.
     *
     * @param caughtUp earlier entries that were in the log but not yet applied, applied first
     */
    public record Erased(EraseEntry entry, EraseHandler.Applied applied, int caughtUp) {
    }

    /**
     * Logs and erases.
     *
     * @throws EraseLogException {@code ERASE_HANDLER_MISSING}, {@code ERASE_HANDLER_FAILED} (with the handler's
     *                           code), {@code ERASE_LOG_RESTORE_DETECTED}, {@code ERASE_LOG_WRONG_LOG},
     *                           {@code ERASE_LOG_BEHIND_DATABASE}, {@code ERASE_LOG_GAP}, {@code ERASE_LOG_UNREACHABLE},
     *                           {@code ERASE_POSITION_FAILED}
     */
    public Erased erase(EraseRequest request) {
        Objects.requireNonNull(request, "request");
        EraseHandler handler = handlers.find(request.kind()).orElseThrow(() -> new EraseLogException(
                EraseLogError.ERASE_HANDLER_MISSING, "no handler is registered for kind " + request.kind()));
        try {
            handler.check(request);
        } catch (EraseHandlerException e) {
            throw new EraseLogException(EraseLogError.ERASE_HANDLER_FAILED, e.code(), request + " was refused before "
                    + "it was logged: " + e.getMessage());
        }
        return position.withLock(log.logId(), locked -> {
            ErasePosition.Position p = locked.position();
            if (p.replayRequired()) {
                throw InStep.restore(p.appliedSeq(), log.head().seq());
            }
            ChainPoint head = log.head();
            InStep.problem(log, p, position.otherLogs(log.logId()), head).ifPresent(e -> {
                throw e;
            });
            int caughtUp = 0;
            if (p.appliedSeq() < head.seq()) {
                List<EraseEntry> pending = log.after(p.appliedSeq());
                for (EraseEntry e : pending) {
                    Applying.apply(handlers, locked, e, guard);
                    caughtUp++;
                }
            }
            EraseEntry entry = log.record(request);
            return new Erased(entry, Applying.apply(handlers, locked, entry, guard), caughtUp);
        });
    }
}
