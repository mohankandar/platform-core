package com.documentplatform.integration.spring;

import java.util.List;

/** The integration settings, or the app beans the module needs, are not usable; every problem is listed. */
final class IntegrationConfigException extends RuntimeException implements com.documentplatform.errors.HasPlatformCode {

    private final List<String> problems;

    public IntegrationConfigException(List<String> problems) {
        super(IntegrationSettingsCode.INTEGRATION_SETTINGS_INVALID.name() + ": the integration settings are not valid: " + String.join("; ", problems),
                null, false, true);
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }

    /** The code, for monitoring, the failure analyzer and the app's error handler. */
    @Override
    public com.documentplatform.errors.PlatformCode platformCode() {
        return IntegrationSettingsCode.INTEGRATION_SETTINGS_INVALID;
    }
}
