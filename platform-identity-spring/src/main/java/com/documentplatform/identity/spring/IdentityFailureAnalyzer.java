package com.documentplatform.identity.spring;

import com.documentplatform.errors.HasPlatformCode;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;

/**
 * Shows a refused start-up as what is wrong and what to do: a {@link PushCallerConfigException} (P037), and identity tables
 * that are not ready (P047; by its code, as platform-identity-jdbc is optional).
 */
final class IdentityFailureAnalyzer implements FailureAnalyzer {

    @Override
    public FailureAnalysis analyze(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof PushCallerConfigException e) {
                return new FailureAnalysis(e.getMessage(), "Correct the " + PushCallerProperties.PREFIX
                        + " settings listed above (docs/install/identity.md), or give the app the beans named above, "
                        + "and start the app again.", e);
            }
            if (t instanceof HasPlatformCode e && e.platformCode().code().startsWith("IDN_")) {
                String code = e.platformCode().code();
                return new FailureAnalysis(code + ": " + t.getMessage(), e.platformCode().fix(), t);
            }
        }
        return null;
    }
}
