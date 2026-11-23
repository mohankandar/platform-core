package com.documentplatform.licensing.spring;

import com.documentplatform.licensing.LicenceException;
import com.documentplatform.licensing.jdbc.LicensingSchemaException;
import com.documentplatform.licensing.spring.internal.LicensingConfigException;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;

/**
 * Turns a refused startup into Spring Boot's "APPLICATION FAILED TO START" report with the stable code, the exact cause
 * and what to do: a wrong install config, licensing tables not ready, or a stored licence that is refused.
 */
final class LicensingFailureAnalyzer implements FailureAnalyzer {

    @Override
    public FailureAnalysis analyze(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof LicensingConfigException e) {
                return new FailureAnalysis(e.getMessage(), "Correct the platform.licensing settings listed above in "
                        + "the install config and start the app again.", e);
            }
            if (t instanceof LicensingSchemaException e) {
                return new FailureAnalysis(e.getMessage() + " (" + e.problem() + ")", switch (e.problem()) {
                    case LIC_PRIVILEGES_MISSING -> "Ask the database administrator to grant the rights named above, "
                            + "then start the app again.";
                    case LIC_VERSION_NEWER -> "Install the app release that matches the database.";
                    case LIC_CHECK_FAILED -> "Check that the app's database user can connect and read the schema.";
                    default -> "Run the app's licensing schema step for this release (a separate Flyway run with "
                            + "location db/platform/licensing and history table lic_schema_history), then start the "
                            + "app again. The app never changes tables itself.";
                }, e);
            }
            if (t instanceof LicenceException e) {
                return new FailureAnalysis(e.getMessage(), e.code().action(), e);
            }
        }
        return null;
    }
}
