package com.documentplatform.licensing.spring;

/**
 * The start-up refusal of licensing's Spring wiring (P037): a platform.licensing.* setting is missing or not valid, or a bean the
 * module needs is missing. The exception carrying it implements {@code HasPlatformCode}; its message lists every
 * problem, each naming the setting or bean, never a value.
 */
public enum LicensingSettingsCode implements com.documentplatform.errors.PlatformCode {
    /** A setting is missing or not valid, or a bean the module needs is missing (the message lists every problem). */
    LIC_SETTINGS_INVALID
}
