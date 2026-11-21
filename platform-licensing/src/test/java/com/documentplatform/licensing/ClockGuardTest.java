package com.documentplatform.licensing;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static com.documentplatform.licensing.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ClockGuardTest {

    /** The store under test keeps whatever it is given, so the test sees exactly what the guard writes. */
    private static final class Store implements LastSeenStore {
        Instant value;
        int writes;

        @Override
        public Optional<Instant> lastSeen() {
            return Optional.ofNullable(value);
        }

        @Override
        public void advanceTo(Instant seen) {
            value = seen;
            writes++;
        }
    }

    private final Instant t0 = Instant.parse("2026-11-21T10:00:00Z");
    private final MutableClock clock = new MutableClock(t0);
    private final Store store = new Store();
    private final ClockGuard guard = new ClockGuard(clock, Duration.ofHours(1), store);

    @Test
    void remembersTheLatestTimeSeen() {
        assertEquals(t0, guard.check());
        assertEquals(t0, store.value);
        clock.advance(Duration.ofDays(2));
        guard.check();
        assertEquals(t0.plus(Duration.ofDays(2)), store.value);
    }

    @Test
    void aClockBehindWithinTheToleranceIsAcceptedAndTheLatestTimeIsKept() {
        guard.check();
        clock.set(t0.minus(Duration.ofHours(1))); // exactly the tolerance
        assertDoesNotThrow(guard::check);
        clock.set(t0.minus(Duration.ofMinutes(5)));
        assertDoesNotThrow(guard::check);
        assertEquals(t0, store.value, "never moved back");
        assertEquals(1, store.writes);
    }

    @Test
    void aClockBehindBeyondTheToleranceIsRefused() {
        guard.check();
        clock.set(t0.minus(Duration.ofHours(1)).minusSeconds(1));
        LicenceException e = assertThrows(LicenceException.class, guard::check);
        assertEquals(LicenceRefusal.LICENCE_CLOCK_ROLLBACK, e.code());
        assertTrue(e.getMessage().contains("2026-11-21T10:00:00Z"), e.getMessage());
        assertTrue(e.getMessage().contains("1 hour(s) 0 minute(s)"), e.getMessage());
        assertEquals(t0, store.value);
    }

    @Test
    void aDayRolledBackIsRefusedAndAcceptedAgainOnceCorrected() {
        guard.check();
        clock.set(t0.minus(Duration.ofDays(30)));
        assertEquals(LicenceRefusal.LICENCE_CLOCK_ROLLBACK, assertThrows(LicenceException.class, guard::check).code());
        clock.set(t0.plusSeconds(60));
        assertDoesNotThrow(guard::check);
    }

    @Test
    void checkerCombinesVerificationClockAndState() {
        Licence licence = subscription(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));
        byte[] file = signed(licence).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        LicenceChecker checker = new LicenceChecker(new LicenceVerifier(trustKey1()), guard);
        assertEquals(LicenceState.EXPIRING, checker.check(file, licence.tenantId()).state());
        clock.set(Instant.parse("2027-02-01T00:00:00Z"));
        assertEquals(LicenceState.EXPIRED_GRACE_OVER, checker.check(file, licence.tenantId()).state());
        // Rolling the clock back to get out of "grace over" is refused.
        clock.set(Instant.parse("2026-12-01T00:00:00Z"));
        assertEquals(LicenceRefusal.LICENCE_CLOCK_ROLLBACK,
                assertThrows(LicenceException.class, () -> checker.check(file, licence.tenantId())).code());
    }
}
