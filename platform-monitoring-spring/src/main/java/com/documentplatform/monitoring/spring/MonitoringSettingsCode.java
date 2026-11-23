package com.documentplatform.monitoring.spring;

/**
 * The start-up refusal of monitoring's Spring wiring (P037): a platform.monitoring.* setting is missing or not valid, or a bean the
 * module needs is missing. The exception carrying it implements {@code HasPlatformCode}; its message lists every
 * problem, each naming the setting or bean, never a value.
 */
public enum MonitoringSettingsCode implements com.documentplatform.errors.PlatformCode {
    /** A setting is missing or not valid, or a bean the module needs is missing (the message lists every problem). */
    MON_SETTINGS_INVALID
}
