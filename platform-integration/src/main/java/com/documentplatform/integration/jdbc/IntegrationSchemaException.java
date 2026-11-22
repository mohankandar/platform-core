package com.documentplatform.integration.jdbc;

/** The integration tables are not ready for the app; the message says what to do. */
public final class IntegrationSchemaException extends RuntimeException {

    public enum Problem {
        INT_TABLES_MISSING, INT_TABLES_INCOMPLETE, INT_VERSION_UNKNOWN, INT_VERSION_OLDER, INT_VERSION_NEWER,
        INT_PRIVILEGES_MISSING, INT_CHECK_FAILED
    }

    private final Problem problem;

    public IntegrationSchemaException(Problem problem, String message) {
        super(problem + ": " + message);
        this.problem = problem;
    }

    public Problem problem() {
        return problem;
    }
}
