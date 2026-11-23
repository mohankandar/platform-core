package com.documentplatform.notifications.spring;

/**
 * The start-up refusal of notifications's Spring wiring (P037): a platform.notifications.* setting is missing or not valid, or a bean the
 * module needs is missing. The exception carrying it implements {@code HasPlatformCode}; its message lists every
 * problem, each naming the setting or bean, never a value.
 */
public enum NotificationsSettingsCode implements com.documentplatform.errors.PlatformCode {
    /** A setting is missing or not valid, or a bean the module needs is missing (the message lists every problem). */
    NOTIF_SETTINGS_INVALID
}
