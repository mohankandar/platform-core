package com.documentplatform.identity.jdbc;

import java.sql.SQLException;

/** An identity table could not be read or written. Names the operation and the SQL state, never values. */
public final class IdentityStoreException extends RuntimeException {

    IdentityStoreException(String operation, SQLException cause) {
        super("Identity store: " + operation + " failed (SQL state " + cause.getSQLState() + ")", cause);
    }
}
