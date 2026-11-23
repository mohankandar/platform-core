package com.documentplatform.identity.spring;

import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;

/** Shows a {@link PushCallerConfigException} at start-up as what is wrong and what to do (P037). */
public final class IdentityFailureAnalyzer implements FailureAnalyzer {

    @Override
    public FailureAnalysis analyze(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof PushCallerConfigException e) {
                return new FailureAnalysis(e.getMessage(), "Correct the " + PushCallerProperties.PREFIX
                        + " settings listed above (docs/install/identity.md), or give the app the beans named above, "
                        + "and start the app again.", e);
            }
        }
        return null;
    }
}
