package com.documentplatform.licensing.spring;

import java.util.List;

/** The licensing install config is wrong or incomplete. Lists every problem with its property key and what to do. */
public final class LicensingConfigException extends IllegalStateException implements com.documentplatform.errors.HasPlatformCode {

    private final List<String> problems;

    public LicensingConfigException(List<String> problems) {
        super(LicensingSettingsCode.LIC_SETTINGS_INVALID.name() + ": Licensing configuration is not valid:\n - "
                + String.join("\n - ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }

    /** The code, for monitoring, the failure analyzer and the app's error handler. */
    @Override
    public com.documentplatform.errors.PlatformCode platformCode() {
        return LicensingSettingsCode.LIC_SETTINGS_INVALID;
    }
}
