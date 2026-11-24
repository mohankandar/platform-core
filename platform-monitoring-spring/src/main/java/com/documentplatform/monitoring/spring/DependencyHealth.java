package com.documentplatform.monitoring.spring;

import com.documentplatform.monitoring.CheckResult;
import com.documentplatform.monitoring.CheckState;
import com.documentplatform.monitoring.DependencyStatus;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The health views of the checks. {@code readinessState}: OUT_OF_SERVICE when a check required at start-up is DOWN
 * (never part of liveness). {@code dependencies}: informational, always UP, listing every check
 * with its state, code and cause; another system being down (an integration) never makes the app unhealthy here.
 */
final class DependencyHealth {

    private DependencyHealth() {
    }

    /**
     * Readiness: OUT_OF_SERVICE while the app refuses traffic or a check required at start-up is DOWN; else UP.
     * Integrations and other optional checks never count.
     */
    static HealthIndicator readiness(DependencyStatus status,
                                     org.springframework.boot.availability.ApplicationAvailability availability) {
        return () -> {
            List<CheckResult> required = status.required();
            boolean down = required.stream().anyMatch(r -> r.state() == CheckState.DOWN);
            boolean refusing = availability != null && availability.getReadinessState()
                    == org.springframework.boot.availability.ReadinessState.REFUSING_TRAFFIC;
            Health.Builder b = down || refusing ? Health.outOfService() : Health.up();
            // P045, SEC-23: state and code only; cause and fix are on the dependencies view and the Support screen.
            required.forEach(r -> b.withDetail(r.name(), Map.of("state", r.state().name(), "code", r.code().code())));
            return b.build();
        };
    }

    static HealthIndicator all(DependencyStatus status) {
        return () -> {
            Health.Builder b = Health.up();
            for (CheckResult r : status.results()) b.withDetail(r.name(), details(r));
            return b.build();
        };
    }

    static Map<String, Object> details(CheckResult r) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("state", r.state().name());
        d.put("code", r.code().code());
        d.put("group", r.group());
        if (r.state() != CheckState.WORKING) {
            d.put("cause", r.cause());
            d.put("fix", r.fix());
        }
        d.put("required", r.requiredAtStartup());
        d.put("checkedAt", r.checkedAt().toString());
        d.put("tookMs", r.took().toMillis());
        return d;
    }
}
