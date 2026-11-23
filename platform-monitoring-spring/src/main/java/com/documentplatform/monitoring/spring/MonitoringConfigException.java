package com.documentplatform.monitoring.spring;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The monitoring settings ({@code platform.monitoring.*}) are not valid: {@link
 * MonitoringSettingsCode#MON_SETTINGS_INVALID} (P037, xc-catalogue-12). Every problem is listed, each naming the
 * setting, never a value; {@link MonitoringFailureAnalyzer} shows it at start-up.
 */
public final class MonitoringConfigException extends IllegalStateException
        implements com.documentplatform.errors.HasPlatformCode {

    private final List<String> problems;

    public MonitoringConfigException(List<String> problems) {
        super(MonitoringSettingsCode.MON_SETTINGS_INVALID.name() + ": the monitoring settings are not valid: "
                + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    /** Checks the settings; @throws MonitoringConfigException listing every problem */
    static void check(MonitoringProperties p) {
        String prefix = MonitoringProperties.PREFIX + ".";
        List<String> problems = new ArrayList<>();
        positive(problems, prefix + "every", p.getEvery());
        positive(problems, prefix + "tick", p.getTick());
        p.getFolders().forEach((name, f) -> {
            String k = prefix + "folders." + name + ".";
            if (f.getPath() == null || f.getPath().isBlank()) problems.add(k + "path is missing");
            if (f.getWarnAt() < 1 || f.getWarnAt() > 100) problems.add(k + "warn-at must be 1 to 100 (per cent)");
            if (f.getDownAt() < 1 || f.getDownAt() > 100) problems.add(k + "down-at must be 1 to 100 (per cent)");
            if (f.getWarnAt() > f.getDownAt()) problems.add(k + "warn-at must not be above down-at");
        });
        p.getServices().forEach((name, s) -> {
            String k = prefix + "services." + name + ".";
            if (s.getUrl() == null) {
                problems.add(k + "url is missing");
            } else if (!"http".equals(s.getUrl().getScheme()) && !"https".equals(s.getUrl().getScheme())) {
                problems.add(k + "url must be an http or https address");
            }
            if (s.getExpectedStatus() < 100 || s.getExpectedStatus() > 599) {
                problems.add(k + "expected-status must be an HTTP status (100 to 599)");
            }
            positive(problems, k + "timeout", s.getTimeout());
        });
        if (!problems.isEmpty()) throw new MonitoringConfigException(problems);
    }

    private static void positive(List<String> problems, String key, Duration d) {
        if (d == null || d.isZero() || d.isNegative()) problems.add(key + " must be more than zero (for example 60s)");
    }

    public List<String> problems() {
        return problems;
    }

    /** The code, for monitoring, the failure analyzer and the app's error handler. */
    @Override
    public com.documentplatform.errors.PlatformCode platformCode() {
        return MonitoringSettingsCode.MON_SETTINGS_INVALID;
    }
}
