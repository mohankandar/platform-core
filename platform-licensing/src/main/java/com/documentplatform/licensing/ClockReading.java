package com.documentplatform.licensing;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One reading of the clock for licence purposes. The <b>effective time</b> is the later of the server clock and the
 * latest time this installation has seen, so turning the server clock back can never extend a licence. A server clock
 * behind the latest time seen by more than the tolerance is reported ({@link #clockBehind()}, {@link #message()}); it
 * never stops the app.
 *
 * @param lastSeen the latest time known before this reading (seen by the installation, or the licence's issue time or the
 *                 app's build time when that is later), or null on the very first reading
 */
public record ClockReading(Instant serverTime, Instant lastSeen, Instant effective, Duration tolerance) {

    public ClockReading {
        Objects.requireNonNull(serverTime, "serverTime");
        Objects.requireNonNull(effective, "effective");
        Objects.requireNonNull(tolerance, "tolerance");
    }

    /** A reading with nothing seen before: the server clock is the effective time. */
    public static ClockReading exact(Instant now) {
        return new ClockReading(now, null, now, Duration.ZERO);
    }

    /** How far the server clock is behind the latest time seen (zero when it is not). */
    public Duration behind() {
        if (lastSeen == null || !serverTime.isBefore(lastSeen)) {
            return Duration.ZERO;
        }
        return Duration.between(serverTime, lastSeen);
    }

    /** True when the server clock is behind the latest time seen by more than the tolerance: the alert flag. */
    public boolean clockBehind() {
        return behind().compareTo(tolerance) > 0;
    }

    /** The alert text while {@link #clockBehind()}; empty otherwise. */
    public Optional<String> message() {
        if (!clockBehind()) {
            return Optional.empty();
        }
        return Optional.of("The server clock is " + describe(behind()) + " behind the latest time this installation"
                + " has seen (server " + serverTime + ", latest seen " + lastSeen + "). Licence dates use " + effective
                + ", so turning the clock back does not extend the licence. Correct the server clock.");
    }

    /** "3 hours", "2 days 4 hours", "45 minutes 10 seconds": the two largest non-zero parts. */
    public static String describe(Duration d) {
        long[] values = {d.toDays(), d.toHoursPart(), d.toMinutesPart(), d.toSecondsPart()};
        String[] names = {"day", "hour", "minute", "second"};
        int first = 0;
        while (first < values.length && values[first] == 0) {
            first++;
        }
        if (first == values.length) {
            return "less than a second";
        }
        List<String> parts = new ArrayList<>();
        for (int i = first; i < Math.min(first + 2, values.length) && values[i] > 0; i++) {
            parts.add(values[i] + " " + names[i] + (values[i] == 1 ? "" : "s"));
        }
        return String.join(" ", parts);
    }
}
