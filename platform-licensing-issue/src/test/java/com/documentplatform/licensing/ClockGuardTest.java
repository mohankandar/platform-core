package com.documentplatform.licensing;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static com.documentplatform.licensing.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ClockGuardTest {

    /** Keeps whatever it is given, so the test sees exactly what the guard writes. */
    static final class Store implements LastSeenStore {
        Instant value;
        int writes;
        final Set<String> resets = new HashSet<>();

        @Override
        public Optional<Instant> lastSeen() {
            return Optional.ofNullable(value);
        }

        @Override
        public void advanceTo(Instant seen) {
            value = seen;
            writes++;
        }

        @Override
        public void resetTo(Instant to, String resetId) {
            value = to;
            resets.add(resetId);
        }

        @Override
        public boolean resetApplied(String resetId) {
            return resets.contains(resetId);
        }
    }

    private final Instant t0 = Instant.parse("2026-11-21T10:00:00Z");
    private final MutableClock clock = new MutableClock(t0);
    private final Store store = new Store();
    private final ClockGuard guard = new ClockGuard(clock, Duration.ofHours(1), store);

    @Test
    void remembersTheLatestTimeSeen() {
        ClockReading first = guard.read();
        assertEquals(t0, first.effective());
        assertNull(first.lastSeen());
        assertEquals(t0, store.value);
        clock.advance(Duration.ofDays(2));
        assertEquals(t0.plus(Duration.ofDays(2)), guard.read().effective());
        assertEquals(t0.plus(Duration.ofDays(2)), store.value);
    }

    @Test
    void theEffectiveTimeIsTheLaterOfServerClockAndLatestSeen() {
        guard.read();
        clock.set(t0.minus(Duration.ofMinutes(30)));
        ClockReading r = guard.read();
        assertEquals(t0, r.effective());
        assertFalse(r.clockBehind(), "within the tolerance: no flag");
        assertEquals(Duration.ofMinutes(30), r.behind());
        assertEquals(t0, store.value, "never moved back");
        assertEquals(1, store.writes);
    }

    @Test
    void exactlyTheToleranceBehindIsNotFlaggedOneSecondMoreIs() {
        guard.read();
        clock.set(t0.minus(Duration.ofHours(1)));
        assertFalse(guard.read().clockBehind());
        clock.set(t0.minus(Duration.ofHours(1)).minusSeconds(1));
        ClockReading r = guard.read();
        assertTrue(r.clockBehind());
        assertEquals(t0, r.effective());
    }

    @Test
    void aClockBehindIsReportedWithTheExactDifferenceAndNeverRefused() {
        guard.read();
        clock.set(t0.minus(Duration.ofHours(3)));
        ClockReading r = assertDoesNotThrow(() -> guard.read());
        assertTrue(r.clockBehind());
        assertEquals("The server clock is 3 hours behind the latest time this installation has seen (server "
                + "2026-11-21T07:00:00Z, latest seen 2026-11-21T10:00:00Z). Licence dates use 2026-11-21T10:00:00Z, "
                + "so turning the clock back does not extend the licence. Correct the server clock.",
                r.message().orElseThrow());
    }

    @Test
    void durationsAreDescribedPlainly() {
        assertEquals("3 hours", ClockReading.describe(Duration.ofHours(3)));
        assertEquals("1 day 4 hours", ClockReading.describe(Duration.ofHours(28)));
        assertEquals("2 days", ClockReading.describe(Duration.ofDays(2).plusMinutes(5)));
        assertEquals("45 minutes 10 seconds", ClockReading.describe(Duration.ofSeconds(45 * 60 + 10)));
        assertEquals("1 second", ClockReading.describe(Duration.ofSeconds(1)));
        assertEquals("less than a second", ClockReading.describe(Duration.ofMillis(300)));
    }

    @Test
    void rollingTheClockBackCannotExtendALicence() {
        Licence licence = subscription(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));
        clock.set(Instant.parse("2027-02-01T00:00:00Z"));
        assertEquals(LicenceState.EXPIRED_GRACE_OVER, LicenceStatus.at(licence, guard.read(), null).state());
        clock.set(Instant.parse("2026-06-01T00:00:00Z")); // turned back into the valid period
        LicenceStatus s = LicenceStatus.at(licence, guard.read(), null);
        assertEquals(LicenceState.EXPIRED_GRACE_OVER, s.state(), "still over: dates use the latest time seen");
        assertTrue(s.clockBehind());
        assertFalse(s.intakeAllowed());
    }

    @Test
    void aVerifiedResetSetsTheLatestTimeBackOnce() {
        Instant poisoned = Instant.parse("2031-01-01T00:00:00Z");
        store.value = poisoned;
        LastSeenReset reset = new LastSeenReset("RST-0001", "TEN-0001", t0, t0.plus(Duration.ofDays(7)), t0, KEY_ID_1);
        assertEquals(LastSeenReset.Outcome.APPLIED, guard.apply(reset));
        assertEquals(t0, store.value);
        assertEquals(LastSeenReset.Outcome.ALREADY_APPLIED, guard.apply(reset));
        assertFalse(guard.read().clockBehind());
    }

    @Test
    void aResetPastItsValidityIsRefused() {
        store.value = Instant.parse("2031-01-01T00:00:00Z");
        LastSeenReset reset = new LastSeenReset("RST-0002", "TEN-0001", t0.minus(Duration.ofDays(10)),
                t0.minus(Duration.ofDays(3)), t0.minus(Duration.ofDays(10)), KEY_ID_1);
        ResetException e = assertThrows(ResetException.class, () -> guard.apply(reset));
        assertEquals(ResetRefusal.RESET_EXPIRED, e.code());
        assertEquals(Instant.parse("2031-01-01T00:00:00Z"), store.value, "nothing changed");
    }

    @Test
    void aResetWhenNothingIsAheadChangesNothing() {
        guard.read();
        LastSeenReset reset = new LastSeenReset("RST-0003", "TEN-0001", t0.plusSeconds(60), t0.plus(Duration.ofDays(1)),
                t0, KEY_ID_1);
        assertEquals(LastSeenReset.Outcome.NOT_NEEDED, guard.apply(reset));
        assertEquals(t0, store.value);
    }
}
