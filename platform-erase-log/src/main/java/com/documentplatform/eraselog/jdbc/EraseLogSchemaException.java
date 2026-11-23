package com.documentplatform.eraselog.jdbc;

/** The {@code erl_} tables are not usable; {@link #problem()} says how, the message what to do. */
public final class EraseLogSchemaException extends RuntimeException {

    public enum Problem implements com.documentplatform.errors.PlatformCode {
        ERL_TABLES_MISSING,
        ERL_TABLES_INCOMPLETE,
        ERL_VERSION_UNKNOWN,
        ERL_VERSION_OLDER,
        ERL_VERSION_NEWER,
        ERL_PRIVILEGES_MISSING,
        ERL_CHECK_FAILED
    }

    private final Problem problem;

    public EraseLogSchemaException(Problem problem, String message) {
        super(problem + ": " + message, null, false, true);
        this.problem = problem;
    }

    public Problem problem() {
        return problem;
    }
}
