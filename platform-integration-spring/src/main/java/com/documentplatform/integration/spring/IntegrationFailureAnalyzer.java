package com.documentplatform.integration.spring;

import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;

/** Turns a wrong integration install config, or a missing app bean, into Spring Boot's "APPLICATION FAILED TO START". */
final class IntegrationFailureAnalyzer implements FailureAnalyzer {

    @Override
    public FailureAnalysis analyze(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof IntegrationConfigException e) {
                return new FailureAnalysis(e.getMessage(), "Correct the platform.integration settings listed above in "
                        + "the install config (docs/install/integration.md), or give the app the beans named above, "
                        + "and start the app again.", e);
            }
        }
        return null;
    }
}
