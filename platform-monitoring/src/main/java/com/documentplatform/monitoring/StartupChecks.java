package com.documentplatform.monitoring;

import java.util.ArrayList;
import java.util.List;

/**
 * At start-up, runs every check that is {@link DependencyCheck#requiredAtStartup()} once. If any is DOWN the app
 * refuses to start: {@link Refused} names each DOWN check with its code, cause and fix, one line each.
 */
public final class StartupChecks {

    private StartupChecks() {
    }

    /** The app does not start: one line per DOWN required check. */
    public static final class Refused extends RuntimeException {
        private final List<CheckResult> down;

        Refused(List<CheckResult> down) {
            super(message(down), null, false, false);
            this.down = List.copyOf(down);
        }

        public List<CheckResult> down() {
            return down;
        }

        private static String message(List<CheckResult> down) {
            StringBuilder sb = new StringBuilder("The app cannot start: ").append(down.size())
                    .append(down.size() == 1 ? " required dependency is" : " required dependencies are")
                    .append(" down.");
            for (CheckResult r : down) sb.append("\n  ").append(r.line());
            return sb.toString();
        }
    }

    /**
     * Runs the required checks once (through {@code status}, so their results are kept).
     *
     * @return every required check's result
     * @throws Refused when any is DOWN
     */
    public static List<CheckResult> require(DependencyStatus status) {
        List<CheckResult> results = new ArrayList<>();
        CheckRunner runner = new CheckRunner();
        try {
            for (DependencyCheck c : status.checks()) {
                if (!c.requiredAtStartup()) continue;
                results.add(status.recordStartup(c, runner.run(c)));
            }
        } finally {
            runner.close();
        }
        List<CheckResult> down = results.stream().filter(r -> r.state() == CheckState.DOWN).toList();
        if (!down.isEmpty()) throw new Refused(down);
        return results;
    }
}
