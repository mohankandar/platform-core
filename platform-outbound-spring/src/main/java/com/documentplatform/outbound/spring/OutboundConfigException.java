package com.documentplatform.outbound.spring;

import java.util.List;

/** The outbound settings, or an app bean they need, are not usable; every problem is listed. */
public final class OutboundConfigException extends RuntimeException {

    private final List<String> problems;

    public OutboundConfigException(List<String> problems) {
        super("OUTBOUND_SETTINGS_INVALID: the outbound settings are not valid: " + String.join("; ", problems),
                null, false, true);
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
