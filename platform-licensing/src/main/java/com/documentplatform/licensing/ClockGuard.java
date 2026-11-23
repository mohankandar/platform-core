package com.documentplatform.licensing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads the effective time for licence purposes: the later of the server clock and the latest time seen. Remembers
 * the latest time seen (it only moves forward). A server clock behind is reported through {@link ClockReading}, never
 * refused. If a wrong future clock ever poisoned the latest time seen, our side issues a signed
 * {@link LastSeenReset}, applied with {@link #apply}.
 *
 * <p><b>Not for apps to call.</b> It stays public because the {@link LicenceService} constructor takes it: the licensing
 * auto-configuration, the setup command and the licence command build one. Apps read the effective time through
 * {@link LicenceService#status()} ({@link LicenceStatus#clock()}), never from a {@code ClockGuard} of their own.
 */
public final class ClockGuard {

    /** Default tolerance before a server clock behind is flagged: one hour. */
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

    public Duration tolerance() {
        return tolerance;
    }

    /** Reads the clock and moves the latest time seen forward to the server clock when it is later. */
    public ClockReading read() {
        Instant now = clock.instant();
        Optional<Instant> last = store.lastSeen();
        if (last.isEmpty() || now.isAfter(last.get())) {
            store.advanceTo(now);
        }
        Instant effective = last.isPresent() && last.get().isAfter(now) ? last.get() : now;
        return new ClockReading(now, last.orElse(null), effective, tolerance);
    }

    /** The server clock itself, without reading or writing the store. */
    public Instant serverTime() {
        return clock.instant();
    }

    /**
     * Applies a verified reset: sets the latest time seen back to {@code reset.resetTo()}, once.
     *
     * @param reset already verified with {@link LastSeenReset#verify}
     */
    public LastSeenReset.Outcome apply(LastSeenReset reset) {
        Instant now = clock.instant();
        if (now.isAfter(reset.notAfter())) {
            throw new ResetException(ResetRefusal.RESET_EXPIRED, "It was valid until " + reset.notAfter()
                    + "; the server clock reads " + now + ". Ask for a new reset.");
        }
        if (store.resetApplied(reset.resetId())) {
            return LastSeenReset.Outcome.ALREADY_APPLIED;
        }
        Optional<Instant> last = store.lastSeen();
        if (last.isEmpty() || !last.get().isAfter(reset.resetTo())) {
            store.resetTo(last.orElse(reset.resetTo()), reset.resetId()); // records the ID; the time is unchanged
            return LastSeenReset.Outcome.NOT_NEEDED;
        }
        store.resetTo(reset.resetTo(), reset.resetId());
        return LastSeenReset.Outcome.APPLIED;
    }
}
