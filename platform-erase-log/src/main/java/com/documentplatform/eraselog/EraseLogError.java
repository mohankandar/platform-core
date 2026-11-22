package com.documentplatform.eraselog;

/**
 * Why an erase log call refused or failed, or why the guard keeps the system from taking work. Stable codes; the
 * message with each names ids (log, sequence number, kind, record) and what to do, never a value.
 */
public enum EraseLogError {
    /** A field of an entry is missing or not of its form (the message names the field, not what was given). */
    ERASE_ENTRY_INVALID,
    /** A field of an entry looks like a value (an e-mail address, sealed data, a fingerprint): the log holds no values. */
    ERASE_ENTRY_VALUE_REFUSED,
    /** A setting is missing or not valid (location, backup retention). */
    ERASE_LOG_SETTINGS_INVALID,
    /** The log's location cannot be read or written (not mounted, no rights, disk full). */
    ERASE_LOG_UNREACHABLE,
    /** A line of the log is not an entry of a known format. */
    ERASE_LOG_FORMAT,
    /** An entry was changed, removed or put out of order: its hash does not follow from the one before. */
    ERASE_LOG_CHAIN_BROKEN,
    /**
     * The database is behind the log: it was restored from a backup (or stopped between writing an entry and
     * erasing). "Restored from backup: run the erase replay before starting work."
     */
    ERASE_LOG_RESTORE_DETECTED,
    /** The database has applied entries the log does not have: the log lost entries, or it is not this database's log. */
    ERASE_LOG_BEHIND_DATABASE,
    /** The database's applied position belongs to another log, or its entry's hash differs from this log's. */
    ERASE_LOG_WRONG_LOG,
    /** The database is older than the log's oldest kept entry (pruned): the erasures in between cannot be replayed. */
    ERASE_LOG_GAP,
    /** The applied position in the app's database cannot be read or written. */
    ERASE_POSITION_FAILED,
    /** No handler is registered for an entry's kind. */
    ERASE_HANDLER_MISSING,
    /** A handler refused or failed; the message carries the handler's own code. */
    ERASE_HANDLER_FAILED,
    /** After the replay, a handler found something in the log still readable (or could not check). */
    ERASE_VERIFY_FAILED
}
