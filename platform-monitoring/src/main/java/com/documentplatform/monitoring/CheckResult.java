package com.documentplatform.monitoring;

import com.documentplatform.errors.PlatformCode;
import com.documentplatform.errors.PlatformError;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The latest result of one check: name, group, whether it is required at start-up, the state, the code, the exact
 * cause (the code's cause with the check's safe details), the fix, when it ran and how long it took.
 */
public record CheckResult(String name, String group, boolean requiredAtStartup, CheckState state, PlatformCode code,
                          String cause, String fix, Instant checkedAt, Duration took) {

    public CheckResult {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(fix, "fix");
        Objects.requireNonNull(checkedAt, "checkedAt");
        Objects.requireNonNull(took, "took");
    }

    /** The result of a verdict. */
    public static CheckResult of(DependencyCheck check, Verdict v, Instant at, Duration took) {
        String cause = PlatformError.fill(v.code(), v.details());
        return new CheckResult(check.name(), check.group(), check.requiredAtStartup(), v.state(), v.code(), cause,
                v.code().fix(), at, took);
    }

    /** One line for a refusal or a log: {@code name: CODE: cause Fix: fix}. */
    public String line() {
        return name + ": " + code.code() + ": " + cause + " Fix: " + fix;
    }

    @Override
    public String toString() {
        return "CheckResult[" + name + " " + state + " " + code.code() + "]";
    }
}
