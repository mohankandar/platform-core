package com.documentplatform.integration.spring;

/**
 * The start-up refusal of integrations's Spring wiring (P037): a platform.integration.* setting is missing or not valid, or a bean the
 * module needs is missing. The exception carrying it implements {@code HasPlatformCode}; its message lists every
 * problem, each naming the setting or bean, never a value.
 */
public enum IntegrationSettingsCode implements com.documentplatform.errors.PlatformCode {
    /** A setting is missing or not valid, or a bean the module needs is missing (the message lists every problem). */
    INTEGRATION_SETTINGS_INVALID
}
