package com.documentplatform.errors;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Logs one failure as one key=value line ({@code code=… ref=… cause="…"}, level WARNING for REFUSED and NEEDS_PERSON,
 * ERROR for FAILED, INFO for INFO) and returns its reference, for the person's message, the IT Admin's screen and a
 * support ticket. The last {@value #KEEP} failures are kept in memory for the Support screen and the diagnostic
 * bundle (code, reference, cause, time; never more).
 */
public final class FailureLog {

    public static final int KEEP = 200;
    private static final System.Logger LOG = System.getLogger(FailureLog.class.getName());
    private static final Deque<PlatformError> RECENT = new ArrayDeque<>();
    private static volatile Clock clock = Clock.systemUTC();

    private FailureLog() {
    }

    /** Logs the failure and returns its reference. */
    public static String record(PlatformCode code, Map<String, ?> safeDetails) {
        return recordError(code, safeDetails).reference();
    }

    public static String record(PlatformCode code) {
        return record(code, Map.of());
    }

    /** Logs the failure and returns it (with its reference). */
    public static PlatformError recordError(PlatformCode code, Map<String, ?> safeDetails) {
        PlatformError e = new PlatformError(code, safeDetails, ReferenceId.next(), clock.instant());
        System.Logger.Level level = switch (code.kind()) {
            case FAILED -> System.Logger.Level.ERROR;
            case REFUSED, NEEDS_PERSON -> System.Logger.Level.WARNING;
            case INFO -> System.Logger.Level.INFO;
        };
        LOG.log(level, e.logLine());
        synchronized (RECENT) {
            RECENT.addFirst(e);
            while (RECENT.size() > KEEP) RECENT.removeLast();
        }
        return e;
    }

    /** The last failures, newest first (at most {@code limit}). */
    public static List<PlatformError> recent(int limit) {
        synchronized (RECENT) {
            return RECENT.stream().limit(Math.max(0, limit)).toList();
        }
    }

    /** For tests only. */
    static void clear() {
        synchronized (RECENT) {
            RECENT.clear();
        }
    }

    static void useClock(Clock c) {
        clock = c;
    }
}
