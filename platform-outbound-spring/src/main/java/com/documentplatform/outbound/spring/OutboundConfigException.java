package com.documentplatform.outbound.spring;

import java.util.List;

/** The outbound settings, or an app bean they need, are not usable; every problem is listed. */
public final class OutboundConfigException extends RuntimeException implements com.documentplatform.errors.HasPlatformCode {

    private final List<String> problems;

    public OutboundConfigException(List<String> problems) {
        super(OutboundSettingsCode.OUTBOUND_SETTINGS_INVALID.name() + ": the outbound settings are not valid: " + String.join("; ", problems),
                null, false, true);
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }

    /** The code, for monitoring, the failure analyzer and the app's error handler. */
    @Override
    public com.documentplatform.errors.PlatformCode platformCode() {
        return OutboundSettingsCode.OUTBOUND_SETTINGS_INVALID;
    }
}
