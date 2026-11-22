package com.documentplatform.integration.spring;

import java.util.List;

/** The integration settings, or the app beans the module needs, are not usable; every problem is listed. */
public final class IntegrationConfigException extends RuntimeException {

    private final List<String> problems;

    public IntegrationConfigException(List<String> problems) {
        super("INTEGRATION_SETTINGS_INVALID: the integration settings are not valid: " + String.join("; ", problems),
                null, false, true);
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
