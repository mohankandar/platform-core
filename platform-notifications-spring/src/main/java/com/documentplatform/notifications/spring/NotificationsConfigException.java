package com.documentplatform.notifications.spring;

import java.util.List;

/**
 * The notifications install config is wrong or incomplete. Lists every problem, each with its property key and what to
 * do; never repeats a value that may be a secret.
 */
public final class NotificationsConfigException extends IllegalStateException implements com.documentplatform.errors.HasPlatformCode {

    private final List<String> problems;

    public NotificationsConfigException(List<String> problems) {
        super(NotificationsSettingsCode.NOTIF_SETTINGS_INVALID.name() + ": Notifications configuration is not valid:\n - "
                + String.join("\n - ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }

    /** The code, for monitoring, the failure analyzer and the app's error handler. */
    @Override
    public com.documentplatform.errors.PlatformCode platformCode() {
        return NotificationsSettingsCode.NOTIF_SETTINGS_INVALID;
    }
}
