package com.documentplatform.eraselog.spring;

import com.documentplatform.errors.HasPlatformCode;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;

/** Shows a refused start-up (settings or tables) as its code, what is wrong and what to do (P047). */
final class EraseLogFailureAnalyzer implements FailureAnalyzer {

    @Override
    public FailureAnalysis analyze(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof HasPlatformCode e && e.platformCode().code().matches("(ERL|ERASE)_.*")) {
                String code = e.platformCode().code();
                String message = String.valueOf(t.getMessage());
                return new FailureAnalysis(message.startsWith(code) ? message : code + ": " + message,
                        e.platformCode().fix(), t);
            }
        }
        return null;
    }
}
