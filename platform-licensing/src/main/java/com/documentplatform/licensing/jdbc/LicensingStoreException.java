package com.documentplatform.licensing.jdbc;

import java.sql.SQLException;

/** A licensing table could not be read or written. Names the operation and the SQL state, never values. */
public final class LicensingStoreException extends RuntimeException
        implements com.documentplatform.errors.HasPlatformCode {

    LicensingStoreException(String operation, SQLException cause) {
        super(LicensingStoreCode.LIC_STORE_FAILED.name() + ": Licensing store: "
                + operation + " failed (SQL state " + cause.getSQLState() + ")", cause);
    }

    /** {@code LIC_STORE_FAILED}: the code for monitoring and the app's error handler. */
    @Override
    public com.documentplatform.errors.PlatformCode platformCode() {
        return LicensingStoreCode.LIC_STORE_FAILED;
    }
}
