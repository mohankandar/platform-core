package com.documentplatform.licensing.jdbc;

/**
 * The licensing tables are not ready for the app: the message says exactly what is wrong and what to do. It holds
 * table names, versions, rights and SQL states only, never connection details.
 */
public final class LicensingSchemaException extends IllegalStateException {

    /** What is wrong, as a stable code. */
    public enum Problem implements com.documentplatform.errors.PlatformCode {
        /** None of the tables exist: the schema step was never run on this database schema. */
        LIC_TABLES_MISSING,
        /** Some tables exist but the version cannot be read. */
        LIC_VERSION_UNKNOWN,
        /** The tables are older than the code: the schema step for this release was not run. */
        LIC_VERSION_OLDER,
        /** The tables are newer than the code: an older app release runs against an upgraded database. */
        LIC_VERSION_NEWER,
        /** The version is right but tables are missing (dropped by hand). */
        LIC_TABLES_INCOMPLETE,
        /** The app's database user may not read or write a table. */
        LIC_PRIVILEGES_MISSING,
        /** The database refused the check itself. */
        LIC_CHECK_FAILED
    }

    private final Problem problem;

    LicensingSchemaException(Problem problem, String message) {
        super(message);
        this.problem = problem;
    }

    LicensingSchemaException(Problem problem, String message, Throwable cause) {
        super(message, cause);
        this.problem = problem;
    }

    public Problem problem() {
        return problem;
    }
}
