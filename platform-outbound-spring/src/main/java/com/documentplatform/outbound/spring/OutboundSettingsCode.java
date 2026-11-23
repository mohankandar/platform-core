package com.documentplatform.outbound.spring;

/**
 * The start-up refusal of outbound calls's Spring wiring (P037): a platform.outbound.* setting is missing or not valid, or a bean the
 * module needs is missing. The exception carrying it implements {@code HasPlatformCode}; its message lists every
 * problem, each naming the setting or bean, never a value.
 */
public enum OutboundSettingsCode implements com.documentplatform.errors.PlatformCode {
    /** A setting is missing or not valid, or a bean the module needs is missing (the message lists every problem). */
    OUTBOUND_SETTINGS_INVALID
}
