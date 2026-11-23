package com.documentplatform.monitoring.spring;

import com.documentplatform.monitoring.StartupChecks;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;

/** Turns a start-up refusal into Spring Boot's "APPLICATION FAILED TO START": one line per DOWN required check. */
final class MonitoringFailureAnalyzer implements FailureAnalyzer {

    @Override
    public FailureAnalysis analyze(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof MonitoringConfigException e) {
                return new FailureAnalysis(e.getMessage(), "Correct the platform.monitoring settings listed above "
                        + "(docs/install/monitoring.md), then start the app again.", e);
            }
            if (t instanceof StartupChecks.Refused r) {
                return new FailureAnalysis(r.getMessage(), "Do what each line's Fix says (docs/install/monitoring.md "
                        + "lists every check), then start the app again.", r);
            }
        }
        return null;
    }
}
