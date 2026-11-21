package com.documentplatform.licensing.jdbc;

import java.sql.SQLException;

/** A licensing table could not be read or written. Names the operation and the SQL state, never values. */
public final class LicensingStoreException extends RuntimeException {

    LicensingStoreException(String operation, SQLException cause) {
        super("Licensing store: " + operation + " failed (SQL state " + cause.getSQLState() + ")", cause);
    }
}
