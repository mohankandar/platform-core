package com.documentplatform.outbound.spring;

import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;

/** Turns wrong outbound settings, or a missing app bean, into Spring Boot's "APPLICATION FAILED TO START". */
public final class OutboundFailureAnalyzer implements FailureAnalyzer {

    @Override
    public FailureAnalysis analyze(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof OutboundConfigException e) {
                return new FailureAnalysis(e.getMessage(), "Correct the platform.outbound settings listed above in the "
                        + "install config (docs/install/integration.md, \"Outbound settings\"), or give the app the "
                        + "beans named above, and start the app again.", e);
            }
        }
        return null;
    }
}
