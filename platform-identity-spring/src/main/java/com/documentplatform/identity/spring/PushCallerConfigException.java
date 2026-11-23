package com.documentplatform.identity.spring;

import java.util.List;

/**
 * The push-API caller settings ({@code platform.identity.push.*}) are not valid, or the {@code PushCallerDirectory} is
 * missing: {@link IdentitySettingsCode#IDENTITY_PUSH_SETTINGS_INVALID} (P037). The message lists every problem, each
 * naming the setting or bean, never a value. {@link IdentityFailureAnalyzer} shows it at start-up.
 */
public final class PushCallerConfigException extends IllegalStateException
        implements com.documentplatform.errors.HasPlatformCode {

    private final List<String> problems;

    public PushCallerConfigException(List<String> problems) {
        super(IdentitySettingsCode.IDENTITY_PUSH_SETTINGS_INVALID.name() + ": " + PushCallerProperties.PREFIX + ": "
                + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }

    /** The code, for monitoring, the failure analyzer and the app's error handler. */
    @Override
    public com.documentplatform.errors.PlatformCode platformCode() {
        return IdentitySettingsCode.IDENTITY_PUSH_SETTINGS_INVALID;
    }
}
