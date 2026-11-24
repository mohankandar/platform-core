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

    /** The random parts already given in the current tenth of a second. */
    private static final java.util.Set<Integer> USED = new java.util.HashSet<>();
    private static final int MAX_PER_TENTH = 1 << 20;

    /**
     * A new reference, never the same twice in this process: 25 fresh random bits each time (P045, SEC-22), so the next
     * one is not near the last one. References sort by the tenth of a second they were made in.
     */
    public static String next() {
        long tenths = Math.max(0, (clock.millis() - EPOCH) / 100);
        int random;
        synchronized (ReferenceId.class) {
            // A clock that went back keeps the last time; a tenth with a million references moves on by one.
            if (tenths < lastTenths) tenths = lastTenths;
            if (tenths == lastTenths && USED.size() >= MAX_PER_TENTH) tenths++;
            if (tenths != lastTenths) {
                USED.clear();
                lastTenths = tenths;
            }
            do {
                random = RANDOM.nextInt(1 << RANDOM_BITS);
            } while (!USED.add(random));
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
