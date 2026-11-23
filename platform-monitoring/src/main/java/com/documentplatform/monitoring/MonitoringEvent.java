package com.documentplatform.monitoring;

import java.util.Objects;

/**
 * A check changed state, for the app to route to its notifications (the continuous-failure and summary rules are the
 * notifications module's). {@code previous} is null for a check's first result when that result is not WORKING.
 */
public record MonitoringEvent(Kind kind, CheckResult previous, CheckResult now) {

    public enum Kind {
        BECAME_DOWN,
        BECAME_HAVING_PROBLEMS,
        RECOVERED
    }

    public MonitoringEvent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(now, "now");
    }

    /** The event for a change from {@code before} to {@code after}, or null when the state did not change. */
    static MonitoringEvent between(CheckResult before, CheckResult after) {
        CheckState was = before == null ? CheckState.WORKING : before.state();
        if (was == after.state()) return null;
        return new MonitoringEvent(switch (after.state()) {
            case DOWN -> Kind.BECAME_DOWN;
            case HAVING_PROBLEMS -> Kind.BECAME_HAVING_PROBLEMS;
            case WORKING -> Kind.RECOVERED;
        }, before, after);
    }
}
