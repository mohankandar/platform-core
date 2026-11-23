package com.documentplatform.notifications.jdbc;

import java.sql.SQLException;

/** A database operation of a notification store failed. The message names the operation and the SQL state only. */
public final class NotificationStoreException extends RuntimeException
        implements com.documentplatform.errors.HasPlatformCode {

    NotificationStoreException(String operation, SQLException cause) {
        super(NotificationStoreCode.NOTIF_STORE_FAILED.name() + ": Notification store failed to "
                + operation + " (SQL state " + cause.getSQLState() + ")", cause);
    }

    /** {@code NOTIF_STORE_FAILED}: the code for monitoring and the app's error handler. */
    @Override
    public com.documentplatform.errors.PlatformCode platformCode() {
        return NotificationStoreCode.NOTIF_STORE_FAILED;
    }
}
