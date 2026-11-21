package com.documentplatform.fields.jdbc;

import java.sql.SQLException;

/**
 * The database refused or failed a field-definition operation. The message names the operation and the SQL state
 * only: never connection details or the driver's own text, which can quote the values written.
 */
public final class FieldStoreException extends RuntimeException {

    private final String sqlState;

    FieldStoreException(String operation, SQLException cause) {
        super("Field definitions: could not " + operation + " (SQL state " + cause.getSQLState() + ")", cause);
        this.sqlState = cause.getSQLState();
    }

    public String sqlState() {
        return sqlState;
    }
}
