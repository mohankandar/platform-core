package com.documentplatform.eraselog;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/** Applies one entry through its handler and moves the position; turns handler failures into erase log codes. */
final class Applying {

    private Applying() {
    }

    /**
     * The context the erase log hands a handler, bound to the one entry being applied (P044, SEC-5). Only this class
     * makes one, so a handler can tell an erasure from the log from a direct call
     * ({@link EraseHandler.Context#issuedFor}).
     */
    static final class Issued implements EraseHandler.Context {
        private final ErasePosition.Locked locked;
        private final EraseEntry entry;

        private Issued(ErasePosition.Locked locked, EraseEntry entry) {
            this.locked = locked;
            this.entry = entry;
        }

        @Override
        public Optional<Connection> connection() {
            return locked.connection();
        }

        boolean isFor(EraseEntry e) {
            return entry == e;
        }
    }

    /**
     * @param log   told that the entry was applied ({@link EraseLog#recordApplied}), for the restore check (SEC-4)
     * @param guard told of a failure ({@link EraseGuard#applyFailed}), when given; may be null
     */
    static EraseHandler.Applied apply(EraseLog log, EraseHandlers handlers, ErasePosition.Locked locked,
                                      EraseEntry entry, EraseGuard guard) {
        EraseHandler.Applied a;
        try {
            a = apply(handlers, locked, entry);
        } catch (EraseLogException e) {
            if (guard != null) {
                guard.applyFailed(entry, e);
            }
            throw e;
        }
        log.recordApplied(entry.seq());
        return a;
    }

    /**
     * Applies again an entry at or below the position, whose data came back (the app's file storage restored without
     * the database, P055). Handlers are safe to run twice; the position stays where it is.
     */
    static EraseHandler.Applied again(EraseHandlers handlers, ErasePosition.Locked locked, EraseEntry entry,
                                      EraseGuard guard) {
        try {
            return run(handlers, locked, entry, apply -> {
                boolean done = false;
                try {
                    EraseHandler.Applied a = apply.get();
                    end(locked, true);
                    done = true;
                    return a;
                } finally {
                    if (!done) end(locked, false);
                }
            });
        } catch (EraseLogException e) {
            if (guard != null) {
                guard.applyFailed(entry, e);
            }
            throw e;
        }
    }

    /** Commits (or undoes) what a handler did through the position's connection, when there is one. */
    private static void end(ErasePosition.Locked locked, boolean commit) {
        Connection c = locked.connection().orElse(null);
        if (c == null) return;
        try {
            if (c.getAutoCommit()) return;
            if (commit) {
                c.commit();
            } else {
                c.rollback();
            }
        } catch (SQLException e) {
            if (commit) {
                throw new EraseLogException(EraseLogError.ERASE_POSITION_FAILED, "the erasure could not be committed "
                        + "(SQL state " + e.getSQLState() + ")");
            }
            // the handler's own failure is the one to report; the connection is closed after the lock
        }
    }

    private static EraseHandler.Applied apply(EraseHandlers handlers, ErasePosition.Locked locked, EraseEntry entry) {
        return run(handlers, locked, entry, apply -> locked.applyAndAdvance(entry, apply));
    }

    private static EraseHandler.Applied run(EraseHandlers handlers, ErasePosition.Locked locked, EraseEntry entry,
                                            Function<Supplier<EraseHandler.Applied>, EraseHandler.Applied> how) {
        EraseHandler handler = handlers.require(entry);
        EraseHandler.Context ctx = new Issued(locked, entry);
        try {
            return how.apply(() -> {
                try {
                    EraseHandler.Applied a = handler.apply(entry, ctx);
                    if (a == null) {
                        throw new EraseHandlerException(EraseLogError.ERASE_HANDLER_ERROR, "the " + entry.kind()
                                + " handler gave no outcome");
                    }
                    return a;
                } catch (EraseHandlerException | EraseLogException e) {
                    throw e;
                } catch (RuntimeException e) {
                    throw EraseHandlerException.unexpected(entry.kind(), e);
                }
            });
        } catch (EraseHandlerException e) {
            throw new EraseLogException(EraseLogError.ERASE_HANDLER_FAILED, e.code(), "entry " + entry.seq() + " ("
                    + entry.kind() + " of " + entry.request().recordType() + " " + entry.request().recordId()
                    + ") was not applied: " + e.getMessage());
        }
    }
}
