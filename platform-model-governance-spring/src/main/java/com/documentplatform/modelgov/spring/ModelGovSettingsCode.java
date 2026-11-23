package com.documentplatform.modelgov.spring;

/**
 * The start-up refusal of model governance's Spring wiring (P037): a platform.model-governance.* setting is missing or not valid, or a bean the
 * module needs is missing. The exception carrying it implements {@code HasPlatformCode}; its message lists every
 * problem, each naming the setting or bean, never a value.
 */
public enum ModelGovSettingsCode implements com.documentplatform.errors.PlatformCode {
    /** A setting is missing or not valid, or a bean the module needs is missing (the message lists every problem). */
    MOD_SETTINGS_INVALID
}
