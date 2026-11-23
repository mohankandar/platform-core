package com.documentplatform.identity.jdbc;

import java.sql.SQLException;

/** An identity table could not be read or written. Names the operation and the SQL state, never values. */
public final class IdentityStoreException extends RuntimeException
        implements com.documentplatform.errors.HasPlatformCode {

    IdentityStoreException(String operation, SQLException cause) {
        super(IdentityStoreCode.IDN_STORE_FAILED.name() + ": Identity store: "
                + operation + " failed (SQL state " + cause.getSQLState() + ")", cause);
    }

    /** {@code IDN_STORE_FAILED}: the code for monitoring and the app's error handler. */
    @Override
    public com.documentplatform.errors.PlatformCode platformCode() {
        return IdentityStoreCode.IDN_STORE_FAILED;
    }
}
