package com.documentplatform.errors;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;

/**
 * A reference for one failure, shown to the person ("… (reference R-…)"), on the IT Admin's screen, in the log line
 * and in a support ticket: {@code R-} plus 12 characters of Crockford base32 (no I, L, O, U). The first 7 come from
 * the time (tenths of a second since 2024, so references sort by time and stay unique per instance for decades), the
 * last 5 are random (25 bits from {@link SecureRandom}), so a reference cannot be guessed from another one.
 */
public final class ReferenceId {

    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final long EPOCH = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static volatile Clock clock = Clock.systemUTC();

    private ReferenceId() {
    }

    /** A new reference. */
    public static String next() {
        long tenths = Math.max(0, (clock.millis() - EPOCH) / 100);
        StringBuilder sb = new StringBuilder("R-");
        append(sb, tenths, 7);
        append(sb, RANDOM.nextInt(1 << 25), 5);
        return sb.toString();
    }

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    /**
     * The reference of the request this thread is handling (set by the app's request filter, for example
     * platform-monitoring-spring's), or null. {@link FailureLog} gives a failure this reference when there is one.
     */
    public static String current() {
        return CURRENT.get();
    }

    /** Sets (or with null, clears) the current request's reference on this thread. */
    public static void setCurrent(String reference) {
        if (reference == null) CURRENT.remove();
        else CURRENT.set(reference);
    }

    /** Whether the text has the form of a reference. */
    public static boolean isReference(String s) {
        return s != null && s.matches("R-[0-9A-HJKMNP-TV-Z]{12}");
    }

    /** For tests only. */
    static void useClock(Clock c) {
        clock = c;
    }

    private static void append(StringBuilder sb, long value, int chars) {
        char[] out = new char[chars];
        for (int i = chars - 1; i >= 0; i--) {
            out[i] = ALPHABET.charAt((int) (value & 31));
            value >>>= 5;
        }
        sb.append(out);
    }
}
