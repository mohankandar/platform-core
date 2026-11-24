package com.documentplatform.fields.jdbc;

import java.sql.SQLException;

/**
 * The database refused or failed a field-definition operation. The message names the operation and the SQL state
 * only: never connection details or the driver's own text, which can quote the values written.
 */
public final class FieldStoreException extends RuntimeException
        implements com.documentplatform.errors.HasPlatformCode {

    private final String sqlState;

    FieldStoreException(String operation, SQLException cause) {
        super(FieldStoreCode.FLD_STORE_FAILED.name() + ": Field definitions: could not "
                + operation + " (SQL state " + cause.getSQLState() + ")", withoutText(cause));
        this.sqlState = cause.getSQLState();
    }

    /** P045, SEC-56: the state and the stack only; the driver's text can quote row values ("Detail: Key (...)="). */
    private static SQLException withoutText(SQLException e) {
        SQLException safe = new SQLException("SQL state " + e.getSQLState(), e.getSQLState(), e.getErrorCode());
        safe.setStackTrace(e.getStackTrace());
        return safe;
    }

    /** {@code FLD_STORE_FAILED}: the code for monitoring and the app's error handler. */
    @Override
    public com.documentplatform.errors.PlatformCode platformCode() {
        return FieldStoreCode.FLD_STORE_FAILED;
    }

    public String sqlState() {
        return sqlState;
    }
}
