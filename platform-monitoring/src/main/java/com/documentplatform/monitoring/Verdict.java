package com.documentplatform.monitoring;

import com.documentplatform.errors.PlatformCode;

import java.util.Map;
import java.util.Objects;

/**
 * What one run of a check found: the state, the code, and safe details for the code's cause (names and addresses of
 * things; values that look like secrets are removed when the result is built).
 */
public record Verdict(CheckState state, PlatformCode code, Map<String, ?> details) {

    public Verdict {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(code, "code");
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static Verdict working() {
        return new Verdict(CheckState.WORKING, MonitoringCode.MON_WORKING, Map.of());
    }

    public static Verdict working(PlatformCode code, Map<String, ?> details) {
        return new Verdict(CheckState.WORKING, code, details);
    }

    public static Verdict problems(PlatformCode code, Map<String, ?> details) {
        return new Verdict(CheckState.HAVING_PROBLEMS, code, details);
    }

    public static Verdict problems(PlatformCode code) {
        return problems(code, Map.of());
    }

    public static Verdict down(PlatformCode code, Map<String, ?> details) {
        return new Verdict(CheckState.DOWN, code, details);
    }

    public static Verdict down(PlatformCode code) {
        return down(code, Map.of());
    }

    /** P045, SEC-54: the state and code only; details are scrubbed when the result is built, not here. */
    @Override
    public String toString() {
        return "Verdict[" + state + " " + code.code() + "]";
    }
}
