package com.documentplatform.notifications.jdbc;

/**
 * The notification tables are not ready for the app: the message says exactly what is wrong and what to do. It holds
 * table names, versions, rights and SQL states only, never connection details.
 */
public final class NotificationSchemaException extends IllegalStateException {

    /** What is wrong, as a stable code. */
    public enum Problem implements com.documentplatform.errors.PlatformCode {
        /** None of the tables exist: the schema step was never run on this database schema. */
        TABLES_MISSING,
        /** Some tables exist but the version cannot be read. */
        VERSION_UNKNOWN,
        /** The tables are older than the code: the schema step for this release was not run. */
        VERSION_OLDER,
        /** The tables are newer than the code: an older app release runs against an upgraded database. */
        VERSION_NEWER,
        /** The version is right but tables are missing (dropped by hand). */
        TABLES_INCOMPLETE,
        /** The app's database user may not read or write a table. */
        PRIVILEGES_MISSING,
        /** The database refused the check itself. */
        CHECK_FAILED
    }

    private final Problem problem;

    NotificationSchemaException(Problem problem, String message) {
        super(message);
        this.problem = problem;
    }

    NotificationSchemaException(Problem problem, String message, Throwable cause) {
        super(message, cause);
        this.problem = problem;
    }

    public Problem problem() {
        return problem;
    }
}
