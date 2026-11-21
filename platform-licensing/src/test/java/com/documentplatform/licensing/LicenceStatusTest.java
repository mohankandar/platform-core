package com.documentplatform.licensing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.OptionalInt;

import static com.documentplatform.licensing.Fixtures.*;
import static com.documentplatform.licensing.LicenceState.*;
import static org.junit.jupiter.api.Assertions.*;

class LicenceStatusTest {

    private static final LocalDate START = LocalDate.parse("2026-01-01");
    private static final LocalDate EXPIRES = LocalDate.parse("2026-12-31");
    private final Licence licence = subscription(START, EXPIRES);

    private LicenceStatus on(LocalDate day) {
        return LicenceStatus.at(licence, at(day.toString()));
    }

    /** days = expiresOn - today. */
    @ParameterizedTest(name = "{0} days before expiry -> {1}, band {2}, warning day {3}")
    @CsvSource({
            "61, VALID, 0, false",
            "60, EXPIRING, 60, true",
            "59, EXPIRING, 60, false",
            "31, EXPIRING, 60, false",
            "30, EXPIRING, 30, true",
            "29, EXPIRING, 30, false",
            "8, EXPIRING, 30, false",
            "7, EXPIRING, 7, true",
            "6, EXPIRING, 7, false",
            "2, EXPIRING, 7, false",
            "1, EXPIRING, 1, true",
            "0, EXPIRING, 1, false"})
    void beforeExpiry(long days, LicenceState state, int band, boolean warningDay) {
        LicenceStatus s = on(EXPIRES.minusDays(days));
        assertEquals(state, s.state());
        assertEquals(band == 0 ? OptionalInt.empty() : OptionalInt.of(band), s.warningThreshold());
        assertEquals(warningDay, s.warningDay());
        assertEquals(days, s.daysUntilExpiry().getAsLong());
        assertTrue(s.intakeAllowed());
    }

    @ParameterizedTest(name = "{0} days after expiry -> {1}")
    @CsvSource({"1, EXPIRED_IN_GRACE, 29, true", "15, EXPIRED_IN_GRACE, 15, true", "30, EXPIRED_IN_GRACE, 0, true",
            "31, EXPIRED_GRACE_OVER, -1, false", "400, EXPIRED_GRACE_OVER, -1, false"})
    void afterExpiry(long days, LicenceState state, long graceLeft, boolean intake) {
        LicenceStatus s = on(EXPIRES.plusDays(days));
        assertEquals(state, s.state());
        assertEquals(intake, s.intakeAllowed());
        assertEquals(EXPIRES.plusDays(30), s.graceEndsOn().orElseThrow());
        if (graceLeft >= 0) {
            assertEquals(graceLeft, s.graceDaysLeft().getAsLong());
            assertTrue(s.warningDay(), "daily critical warning during grace");
        } else {
            assertTrue(s.graceDaysLeft().isEmpty());
            assertFalse(s.warningDay());
        }
    }

    @Test
    void theExpiryDayLastsUntilMidnightUtc() {
        assertEquals(EXPIRING, LicenceStatus.at(licence, Instant.parse("2026-12-31T23:59:59Z")).state());
        assertEquals(EXPIRED_IN_GRACE, LicenceStatus.at(licence, Instant.parse("2027-01-01T00:00:00Z")).state());
        assertEquals(EXPIRED_IN_GRACE, LicenceStatus.at(licence, Instant.parse("2027-01-30T23:59:59Z")).state());
        assertEquals(EXPIRED_GRACE_OVER, LicenceStatus.at(licence, Instant.parse("2027-01-31T00:00:00Z")).state());
    }

    @Test
    void beforeTheStartDate() {
        LicenceStatus s = on(START.minusDays(1));
        assertEquals(NOT_STARTED, s.state());
        assertFalse(s.intakeAllowed());
        assertEquals(VALID, on(START).state());
    }

    @Test
    void messagesSayWhatHappens() {
        assertTrue(on(EXPIRES.minusDays(30)).message().contains("expires in 30 days"));
        assertTrue(on(EXPIRES.minusDays(1)).message().contains("expires in 1 day "));
        assertTrue(on(EXPIRES).message().contains("end of today"));
        assertTrue(on(EXPIRES.plusDays(1)).message().contains("grace period, which ends on 2027-01-30"));
        assertTrue(on(EXPIRES.plusDays(31)).message().contains("Intake is stopped; viewing and export still work"));
    }

    @Test
    void aPerpetualLicenceNeverExpiresAndNeverEntersGrace() {
        Licence p = perpetual(START, LocalDate.parse("2027-06-30"));
        for (String day : new String[]{"2026-01-01", "2027-06-30", "2027-07-01", "2040-01-01", "2199-12-31"}) {
            LicenceStatus s = LicenceStatus.at(p, at(day));
            assertEquals(VALID, s.state(), day);
            assertTrue(s.intakeAllowed());
            assertFalse(s.warningDay());
            assertTrue(s.daysUntilExpiry().isEmpty());
            assertTrue(s.graceEndsOn().isEmpty());
        }
    }

    @Test
    void perpetualUpdatesUntilCoversReleasesUpToThatDate() {
        Licence p = perpetual(START, LocalDate.parse("2027-06-30"));
        assertTrue(p.coversRelease(LocalDate.parse("2026-03-01")));
        assertTrue(p.coversRelease(LocalDate.parse("2027-06-30")));
        assertFalse(p.coversRelease(LocalDate.parse("2027-07-01")));
        assertEquals("This version is not covered by your licence's update period (updates until 2027-06-30)."
                + " The version you had before keeps working.", p.releaseNotCoveredMessage());
        // Not covered does not change the state: the installed version keeps working.
        assertEquals(VALID, LicenceStatus.at(p, at("2027-07-02")).state());
    }

    @Test
    void aSubscriptionCoversEveryRelease() {
        assertTrue(licence.coversRelease(LocalDate.parse("2099-01-01")));
    }
}
