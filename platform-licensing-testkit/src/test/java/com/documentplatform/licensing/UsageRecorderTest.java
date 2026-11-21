package com.documentplatform.licensing;

import com.documentplatform.licensing.inmemory.InMemoryUsageLedger;
import com.documentplatform.licensing.inmemory.TestLicences;
import com.documentplatform.licensing.usage.UsageAssessment;
import com.documentplatform.licensing.usage.UsageError;
import com.documentplatform.licensing.usage.UsageException;
import com.documentplatform.licensing.usage.UsageLevel;
import com.documentplatform.licensing.usage.UsageRecord;
import com.documentplatform.licensing.usage.UsageRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

import static com.documentplatform.licensing.inmemory.TestLicences.PRODUCT;
import static com.documentplatform.licensing.inmemory.TestLicences.SLOT;
import static org.junit.jupiter.api.Assertions.*;

class UsageRecorderTest {

    private final TestLicences licences = new TestLicences();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-21T10:00:00Z"), ZoneOffset.UTC);
    private final InMemoryUsageLedger ledger = new InMemoryUsageLedger();

    private UsageRecorder recorder(LicenceKind kind) {
        Licence l = licences.subscription(kind, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));
        return new UsageRecorder(l, ledger, clock);
    }

    @ParameterizedTest(name = "{0}, free={1} -> billable={2}")
    @CsvSource({"PRODUCTION, false, true", "PRODUCTION, true, false", "INSTALL, false, false", "INSTALL, true, false",
            "NON_PRODUCTION, false, false", "NON_PRODUCTION, true, false"})
    void billableOnlyForProductionUsageNotMarkedFree(LicenceKind kind, boolean free, boolean billable) {
        recorder(kind).record(SLOT, PRODUCT, AllowanceType.PAGES_PER_MONTH, 10, free);
        UsageRecord r = ledger.records().get(0);
        assertEquals(billable, r.billable());
        assertEquals(10, r.amount(), "usage is recorded under every kind");
        assertEquals(YearMonth.of(2026, 11), r.period());
    }

    @Test
    void levelsAreReportedAndNothingIsEverBlocked() {
        UsageRecorder rec = recorder(LicenceKind.PRODUCTION); // 1000 pages a month
        assertEquals(UsageLevel.WITHIN, rec.record(SLOT, PRODUCT, AllowanceType.PAGES_PER_MONTH, 799, false).level());
        assertEquals(UsageLevel.NEAR_LIMIT, rec.record(SLOT, PRODUCT, AllowanceType.PAGES_PER_MONTH, 1, false).level());
        assertEquals(UsageLevel.AT_LIMIT, rec.record(SLOT, PRODUCT, AllowanceType.PAGES_PER_MONTH, 200, false).level());
        UsageAssessment over = rec.record(SLOT, PRODUCT, AllowanceType.PAGES_PER_MONTH, 5000, false);
        assertEquals(UsageLevel.OVER_LIMIT, over.level());
        assertEquals(6000, over.used());
        assertEquals(1000, over.limit());
        assertEquals(600, over.percentUsed());
        assertEquals(4, ledger.records().size(), "overage is recorded");
        assertTrue(ledger.records().get(3).billable());
    }

    @Test
    void useWithoutAnAllowanceIsRecordedAndReportedOverLimit() {
        UsageAssessment a = recorder(LicenceKind.PRODUCTION).record(SLOT, PRODUCT, AllowanceType.STORAGE, 3, false);
        assertEquals(UsageLevel.OVER_LIMIT, a.level());
        assertFalse(a.hasAllowance());
        assertEquals(1, ledger.records().size());
    }

    @Test
    void assessAllAllowancesOfASlot() {
        UsageRecorder rec = recorder(LicenceKind.PRODUCTION);
        rec.record(SLOT, PRODUCT, AllowanceType.NAMED_SEATS, 8, false);
        rec.record(SLOT, PRODUCT, AllowanceType.NAMED_SEATS, 10, false);
        List<UsageAssessment> all = rec.assess(SLOT, YearMonth.of(2026, 11));
        assertEquals(2, all.size());
        assertEquals(UsageLevel.WITHIN, all.get(0).level()); // pages: 0 of 1000
        assertEquals(UsageLevel.AT_LIMIT, all.get(1).level()); // named seats: peak 10 of 10
        assertEquals(UsageLevel.WITHIN, rec.assess(SLOT, PRODUCT, AllowanceType.NAMED_SEATS, YearMonth.of(2026, 12)).level());
    }

    @Test
    void unknownSlotOrProductHasItsOwnCode() {
        UsageRecorder rec = recorder(LicenceKind.PRODUCTION);
        assertEquals(UsageError.USAGE_UNKNOWN_SLOT, assertThrows(UsageException.class,
                () -> rec.record("ENT-NOT-MINE", PRODUCT, AllowanceType.PAGES_PER_MONTH, 1, false)).code());
        assertEquals(UsageError.USAGE_UNKNOWN_PRODUCT, assertThrows(UsageException.class,
                () -> rec.record(SLOT, "PRODUCT_Z", AllowanceType.PAGES_PER_MONTH, 1, false)).code());
        assertTrue(ledger.records().isEmpty());
    }

    @Test
    void testkitLicencesVerifyLikeRealOnes() {
        Licence l = licences.subscription(LicenceKind.NON_PRODUCTION, LocalDate.parse("2026-01-01"),
                LocalDate.parse("2026-12-31"));
        Licence read = new LicenceVerifier(licences.trustedKeys()).verify(licences.sign(l), TestLicences.TENANT);
        assertEquals(l, read);
        assertTrue(read.testEnvironment());
    }
}
