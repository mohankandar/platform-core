package com.documentplatform.identity.spring;

/**
 * The start-up refusal of push-API caller's Spring wiring (P037): a platform.identity.push.* setting is missing or not valid, or a bean the
 * module needs is missing. The exception carrying it implements {@code HasPlatformCode}; its message lists every
 * problem, each naming the setting or bean, never a value.
 */
public enum IdentitySettingsCode implements com.documentplatform.errors.PlatformCode {
    /** A setting is missing or not valid, or a bean the module needs is missing (the message lists every problem). */
    IDENTITY_PUSH_SETTINGS_INVALID
}
