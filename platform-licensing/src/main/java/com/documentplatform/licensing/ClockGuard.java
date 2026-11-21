package com.documentplatform.licensing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Detects a clock moved backwards. Remembers the latest time seen; a clock more than {@code tolerance} behind it is
 * refused with {@link LicenceRefusal#LICENCE_CLOCK_ROLLBACK}. A clock behind by up to the tolerance (small corrections,
 * a slightly different clock on another node) is accepted, and the remembered time is not moved back.
 */
public final class ClockGuard {

    /** Default tolerance: one hour. */
    public static final Duration DEFAULT_TOLERANCE = Duration.ofHours(1);

    private final Clock clock;
    private final Duration tolerance;
    private final LastSeenStore store;

    public ClockGuard(Clock clock, Duration tolerance, LastSeenStore store) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tolerance = Objects.requireNonNull(tolerance, "tolerance");
        if (tolerance.isNegative()) {
            throw new IllegalArgumentException("tolerance must not be negative");
        }
        this.store = Objects.requireNonNull(store, "store");
    }

    public ClockGuard(Clock clock, LastSeenStore store) {
        this(clock, DEFAULT_TOLERANCE, store);
    }

    /**
     * @return the current time, once it has passed the check
     * @throws LicenceException {@code LICENCE_CLOCK_ROLLBACK}
     */
    public Instant check() {
        Instant now = clock.instant();
        Optional<Instant> last = store.lastSeen();
        if (last.isPresent() && now.isBefore(last.get().minus(tolerance))) {
            Duration behind = Duration.between(now, last.get());
            throw new LicenceException(LicenceRefusal.LICENCE_CLOCK_ROLLBACK, "It reads " + now + ", "
                    + describe(behind) + " behind the latest time seen (" + last.get() + "); the tolerance is "
                    + describe(tolerance) + ". Correct the system clock; the licence is accepted again once it is.");
        }
        if (last.isEmpty() || now.isAfter(last.get())) {
            store.advanceTo(now);
        }
        return now;
    }

    private static String describe(Duration d) {
        if (d.toDays() >= 1) {
            return d.toDays() + " day(s) " + d.toHoursPart() + " hour(s)";
        }
        if (d.toHours() >= 1) {
            return d.toHours() + " hour(s) " + d.toMinutesPart() + " minute(s)";
        }
        return d.toMinutes() + " minute(s) " + d.toSecondsPart() + " second(s)";
    }
}
