package com.documentplatform.licensing.spring.notifications;

import com.documentplatform.licensing.spring.LicensingConfigException;
import com.documentplatform.licensing.spring.LicensingProperties;
import com.documentplatform.notifications.AppLink;
import com.documentplatform.notifications.NotificationException;
import com.documentplatform.notifications.RecordIdentifier;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** The checked {@code platform.licensing.notifications.*} config. */
record LicensingNotificationsConfig(Duration checkInterval, LicensingNotices.Options options) {

    static final Duration MIN_INTERVAL = Duration.ofMinutes(1);
    static final Duration MAX_INTERVAL = Duration.ofHours(24);

    /** @throws LicensingConfigException listing every problem, by property key, without echoing values */
    static LicensingNotificationsConfig from(LicensingProperties.Notifications p) {
        String prefix = LicensingProperties.PREFIX + ".notifications.";
        List<String> problems = new ArrayList<>();
        Duration interval = p.getCheckInterval();
        if (interval == null || interval.compareTo(MIN_INTERVAL) < 0 || interval.compareTo(MAX_INTERVAL) > 0) {
            problems.add(prefix + "check-interval must be 1 minute to 24 hours (for example 1h)");
        }
        AppLink link = null;
        if (p.getLink() != null && !p.getLink().isBlank()) {
            try {
                link = AppLink.of(p.getLink().strip());
            } catch (NotificationException e) {
                problems.add(prefix + "link must be a path inside the app, such as /admin/licence (no host, query or"
                        + " fragment)");
            }
        }
        RecordIdentifier version = null;
        if (p.getReleaseVersion() != null && !p.getReleaseVersion().isBlank()) {
            try {
                version = RecordIdentifier.of(p.getReleaseVersion().strip());
            } catch (NotificationException e) {
                problems.add(prefix + "release-version must be up to 64 of letters, digits and . _ : / # -");
            }
        }
        LocalDate released = null;
        if (p.getReleaseDate() != null && !p.getReleaseDate().isBlank()) {
            try {
                released = LocalDate.parse(p.getReleaseDate().strip());
            } catch (DateTimeParseException e) {
                problems.add(prefix + "release-date must be a date written YYYY-MM-DD");
            }
        }
        if (!problems.isEmpty()) {
            throw new LicensingConfigException(problems);
        }
        return new LicensingNotificationsConfig(interval, new LicensingNotices.Options(link, version, released));
    }
}
