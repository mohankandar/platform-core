package com.documentplatform.eraselog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Whether the database's applied position fits the log: the checks shared by the eraser, the guard and the replay. */
final class InStep {

    static final String RESTORE_MESSAGE = "Restored from backup: run the erase replay before starting work";

    private InStep() {
    }

    /**
     * The problem with this position and log, if any; applied entries still to replay are not a problem here.
     *
     * @param otherLogs logs other than this one the database has a position for
     */
    static Optional<EraseLogException> problem(EraseLog log, ErasePosition.Position position, List<UUID> otherLogs,
                                               ChainPoint head) {
        if (!otherLogs.isEmpty()) {
            return Optional.of(new EraseLogException(EraseLogError.ERASE_LOG_WRONG_LOG, "this database has the "
                    + "applied position of another erase log (" + otherLogs.get(0) + "), not of log " + log.logId()
                    + " at " + log.location() + ": the app is pointed at the wrong log location"));
        }
        long applied = position.appliedSeq();
        if (applied > head.seq()) {
            return Optional.of(new EraseLogException(EraseLogError.ERASE_LOG_BEHIND_DATABASE, "the database has "
                    + "applied erase log entries up to " + applied + " but the log at " + log.location() + " ends at "
                    + head.seq() + ": the log lost entries or was restored; restore the log from its own backup"));
        }
        ChainPoint checkpoint = log.checkpoint();
        if (applied < checkpoint.seq()) {
            return Optional.of(new EraseLogException(EraseLogError.ERASE_LOG_GAP, "the database has applied erase "
                    + "log entries up to " + applied + " but the log keeps entries only after " + checkpoint.seq()
                    + " (older ones were pruned): the database is older than the backup retention allows"));
        }
        if (applied > 0) {
            ChainPoint expected = applied == head.seq() ? head : log.point(applied).orElse(null);
            if (expected == null || !expected.hash().equals(position.applied().hash())) {
                return Optional.of(new EraseLogException(EraseLogError.ERASE_LOG_WRONG_LOG, "entry " + applied
                        + " of the log at " + log.location() + " is not the one this database applied"));
            }
        }
        return Optional.empty();
    }

    static EraseLogException restore(long applied, long head) {
        return new EraseLogException(EraseLogError.ERASE_LOG_RESTORE_DETECTED, RESTORE_MESSAGE + " (database at "
                + "entry " + applied + ", erase log at entry " + head + ")");
    }
}
