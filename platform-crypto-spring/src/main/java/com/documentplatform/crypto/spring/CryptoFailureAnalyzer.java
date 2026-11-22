package com.documentplatform.crypto.spring;

import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;

/** Turns a wrong crypto install config into Spring Boot's "APPLICATION FAILED TO START" report. */
public final class CryptoFailureAnalyzer implements FailureAnalyzer {

    @Override
    public FailureAnalysis analyze(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof CryptoConfigException e) {
                return new FailureAnalysis(e.getMessage(), "Correct the platform.crypto settings listed above in the "
                        + "install config and start the app again. The app's Vault login needs the "
                        + "platform-crypto-app policy; the customer's keys are created by the setup command.", e);
            }
        }
        return null;
    }
}
