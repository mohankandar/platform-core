package com.documentplatform.fields.jdbc;

/**
 * The field tables are not ready for the app: the message says exactly what is wrong and what to do. It holds table
 * names, versions, rights and SQL states only, never connection details.
 */
public final class FieldSchemaException extends IllegalStateException {

    /** What is wrong, as a stable code. */
    public enum Problem implements com.documentplatform.errors.PlatformCode {
        /** None of the tables exist: the schema step was never run on this database schema. */
        FLD_TABLES_MISSING,
        /** Some tables exist but the version cannot be read. */
        FLD_VERSION_UNKNOWN,
        /** The tables are older than the code: the schema step for this release was not run. */
        FLD_VERSION_OLDER,
        /** The tables are newer than the code: an older app release runs against an upgraded database. */
        FLD_VERSION_NEWER,
        /** The version is right but tables are missing (dropped by hand). */
        FLD_TABLES_INCOMPLETE,
        /** The app's database user may not read or write a table. */
        FLD_PRIVILEGES_MISSING,
        /** The database refused the check itself. */
        FLD_CHECK_FAILED
    }

    private final Problem problem;

    FieldSchemaException(Problem problem, String message) {
        super(message);
        this.problem = problem;
    }

    FieldSchemaException(Problem problem, String message, Throwable cause) {
        super(message, cause);
        this.problem = problem;
    }

    public Problem problem() {
        return problem;
    }
}
