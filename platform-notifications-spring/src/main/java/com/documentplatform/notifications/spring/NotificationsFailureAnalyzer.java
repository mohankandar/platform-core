package com.documentplatform.notifications.spring;

import com.documentplatform.notifications.jdbc.NotificationSchemaException;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;

/**
 * Turns a refused startup into Spring Boot's "APPLICATION FAILED TO START" report with the specific reason and what to
 * do, instead of a stack trace: a wrong install config, or notification tables that are missing, at another version or
 * not usable by the app's database user.
 */
final class NotificationsFailureAnalyzer implements FailureAnalyzer {

    @Override
    public FailureAnalysis analyze(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof NotificationsConfigException e) {
                return new FailureAnalysis(e.getMessage(), "Correct the platform.notifications settings listed above "
                        + "in the install config and start the app again. See docs/install/notifications.md.", e);
            }
            if (t instanceof NotificationSchemaException e) {
                return new FailureAnalysis(e.getMessage() + " (" + e.problem() + ")", switch (e.problem()) {
                    case PRIVILEGES_MISSING -> "Ask the database administrator to grant the rights named above, then "
                            + "start the app again.";
                    case VERSION_NEWER -> "Install the app release that matches the database.";
                    case CHECK_FAILED -> "Check that the app's database user can connect and read the schema.";
                    default -> "Run the app's database schema step for this release (it creates and upgrades the "
                            + "notif_ tables), then start the app again. The app never changes tables itself.";
                }, e);
            }
        }
        return null;
    }
}
