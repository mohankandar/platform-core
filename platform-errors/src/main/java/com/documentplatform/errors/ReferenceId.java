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

    private static final int RANDOM_BITS = 25;
    private static long lastTenths = -1;
    private static long lastRandom;

    /**
     * A new reference, never the same twice in this process: within the same tenth of a second the random part
     * steps on by a random amount (so references stay time-ordered and hard to guess); when it would overflow, the
     * time part moves on by one tenth.
     */
    public static String next() {
        long tenths = Math.max(0, (clock.millis() - EPOCH) / 100);
        long random;
        synchronized (ReferenceId.class) {
            if (tenths <= lastTenths) { // same tenth, or the clock went back: stay after the last one
                tenths = lastTenths;
                random = lastRandom + 1 + RANDOM.nextInt(1 << 10);
                if (random >= 1L << RANDOM_BITS) {
                    tenths++;
                    random = RANDOM.nextInt(1 << (RANDOM_BITS - 1));
                }
            } else {
                random = RANDOM.nextInt(1 << (RANDOM_BITS - 1));
            }
            lastTenths = tenths;
            lastRandom = random;
        }
        StringBuilder sb = new StringBuilder("R-");
        append(sb, tenths, 7);
        append(sb, random, 5);
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
