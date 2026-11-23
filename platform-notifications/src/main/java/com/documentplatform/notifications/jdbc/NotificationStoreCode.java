package com.documentplatform.notifications.jdbc;

/**
 * A read or write of the notifications tables failed (P037, xc-catalogue-19). The exception names the operation and the SQL
 * state, never a value; it implements {@code HasPlatformCode}.
 */
public enum NotificationStoreCode implements com.documentplatform.errors.PlatformCode {
    /** A read or write of the notifications tables failed; the message names the operation and the SQL state. */
    NOTIF_STORE_FAILED
}
