package com.documentplatform.licensing;

import com.documentplatform.licensing.inmemory.InMemoryLastSeenStore;
import com.documentplatform.licensing.inmemory.InMemoryLicenceStore;
import com.documentplatform.licensing.inmemory.InMemoryUsageLedger;
import com.documentplatform.licensing.inmemory.TestLicences;
import com.documentplatform.licensing.usage.UsageAssessment;
import com.documentplatform.licensing.usage.UsageLevel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static com.documentplatform.licensing.LicenceStore.Slot.CURRENT;
import static com.documentplatform.licensing.LicenceStore.Slot.WAITING;
import static com.documentplatform.licensing.inmemory.TestLicences.PRODUCT;
import static com.documentplatform.licensing.inmemory.TestLicences.SLOT;
import static com.documentplatform.licensing.inmemory.TestLicences.TENANT;
import static org.junit.jupiter.api.Assertions.*;

class LicenceServiceTest {

    static final class MovableClock extends Clock {
        Instant now;

        MovableClock(String at) {
            now = Instant.parse(at);
        }

        void set(String at) {
            now = Instant.parse(at);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final TestLicences licences = new TestLicences();
    private final MovableClock clock = new MovableClock("2026-11-21T10:00:00Z");
    private final InMemoryLicenceStore store = new InMemoryLicenceStore();
    private final InMemoryLastSeenStore lastSeen = new InMemoryLastSeenStore();
    private final InMemoryUsageLedger ledger = new InMemoryUsageLedger();

    private LicenceService service() {
        return service(PRODUCT);
    }

    private LicenceService service(String product) {
        return new LicenceService(store, new LicenceVerifier(licences.trustedKeys()),
                new ClockGuard(clock, Duration.ofHours(1), lastSeen), ledger, product, null);
    }

    private Licence version(long v, String starts, String expires) {
        Licence base = licences.subscription(LicenceKind.PRODUCTION, LocalDate.parse(starts), LocalDate.parse(expires));
        return new Licence("LIC-TEST-000" + v, v, base.customer(), base.tenantId(), base.products(),
                base.entitySlots(), base.type(), base.kind(), base.startsOn(), base.expiresOn(), null,
                base.deployment(), base.keyId(), base.issuedAt());
    }

    /** P045, SEC-13: the older signed file written back into the table verifies, but is refused. */
    @Test
    void anOlderSignedLicenceWrittenBackIsRefused() {
        String v1 = licences.sign(version(1, "2026-01-01", "2027-12-31"));
        service().store(v1);
        service().store(licences.sign(version(2, "2026-02-01", "2026-12-31")));
        LicenceStore.Stored now = store.get(LicenceStore.Slot.CURRENT).orElseThrow();
        store.put(LicenceStore.Slot.CURRENT, new LicenceStore.Stored(v1, "LIC-X", 1, now.tenantId(), now.startsOn(),
                now.storedAt()));
        assertEquals(LicenceRefusal.LICENCE_OLDER_VERSION,
                assertThrows(LicenceException.class, () -> service().status()).code());
        store.remove(LicenceStore.Slot.CURRENT);
        assertEquals(LicenceRefusal.LICENCE_OLDER_VERSION,
                assertThrows(LicenceException.class, () -> service().store(v1)).code(), "nor stored again");
        service().store(licences.sign(version(2, "2026-02-01", "2026-12-31")));
        assertEquals(2, service().status().licence().version(), "the newest can be stored again");
    }

    @Test
    void noLicenceStoredIsTheOnlyMissingRefusal() {
        assertEquals(LicenceRefusal.LICENCE_MISSING, assertThrows(LicenceException.class, () -> service().status()).code());
    }

    @Test
    void aRestartDuringGraceStartsAndIntakeContinues() {
        service().store(licences.sign(version(1, "2026-01-01", "2026-11-10")));
        LicenceService afterRestart = service(); // a new service on the same stores: what a restart does
        LicenceStatus s = assertDoesNotThrow(afterRestart::status);
        assertEquals(LicenceState.EXPIRED_IN_GRACE, s.state());
        assertTrue(afterRestart.intakeAllowed());
        clock.set("2026-12-11T00:00:00Z");
        LicenceStatus over = assertDoesNotThrow(() -> service().status());
        assertEquals(LicenceState.EXPIRED_GRACE_OVER, over.state(), "starts, intake stopped");
        assertFalse(service().intakeAllowed());
    }

    @Test
    void aClockRolledBackStartsWithTheFlagAndCannotExtendExpiry() {
        service().store(licences.sign(version(1, "2026-01-01", "2026-12-31")));
        clock.set("2027-02-15T00:00:00Z");
        assertEquals(LicenceState.EXPIRED_GRACE_OVER, service().status().state());
        clock.set("2026-06-01T00:00:00Z"); // turned back into the valid period
        LicenceStatus s = assertDoesNotThrow(() -> service().status());
        assertEquals(LicenceState.EXPIRED_GRACE_OVER, s.state());
        assertTrue(s.clockBehind());
        assertTrue(s.clock().message().orElseThrow().startsWith("The server clock is 259 days behind"),
                s.clock().message().orElseThrow());
        assertFalse(service().intakeAllowed());
    }

    @Test
    void usageIsDatedByTheEffectiveTime() {
        service().store(licences.sign(version(1, "2026-01-01", "2027-12-31")));
        clock.set("2027-03-10T00:00:00Z");
        service().status();
        clock.set("2026-12-01T00:00:00Z");
        service().recordUsage(SLOT, AllowanceType.PAGES_PER_MONTH, 10, false);
        assertEquals(YearMonth.of(2027, 3), ledger.records().get(0).period(), "cannot be backdated by the clock");
    }

    @Test
    void aPoisonedLastSeenRecoversWithAValidReset() {
        service().store(licences.sign(version(1, "2026-01-01", "2027-11-30")));
        lastSeen.advanceTo(Instant.parse("2031-01-01T00:00:00Z")); // a wrong future clock, seen once
        assertEquals(LicenceState.EXPIRED_GRACE_OVER, service().status().state());
        Instant now = clock.instant();
        LastSeenReset reset = new LastSeenReset("RST-TEST-0001", TENANT, now, now.plus(Duration.ofDays(7)), now,
                licences.keyId());
        String file = com.documentplatform.licensing.issue.ResetSigner.sign(reset, licences.keyPair().getPrivate());
        assertEquals(LastSeenReset.Outcome.APPLIED, service().applyReset(file.getBytes()));
        LicenceStatus s = service().status();
        assertEquals(LicenceState.VALID, s.state());
        assertFalse(s.clockBehind());
        assertEquals(LastSeenReset.Outcome.ALREADY_APPLIED, service().applyReset(file.getBytes()));
    }

    @Test
    void aResetIsUselessLaterAndForAnotherKey() {
        service().store(licences.sign(version(1, "2026-01-01", "2027-11-30")));
        Instant now = clock.instant();
        String old = com.documentplatform.licensing.issue.ResetSigner.sign(new LastSeenReset("RST-TEST-0002", TENANT, now, now.plus(Duration.ofDays(1)), now, licences.keyId()),
                licences.keyPair().getPrivate());
        clock.set("2026-11-25T00:00:00Z");
        assertEquals(ResetRefusal.RESET_EXPIRED,
                assertThrows(ResetException.class, () -> service().applyReset(old.getBytes())).code());
        String forged = com.documentplatform.licensing.issue.ResetSigner.sign(new LastSeenReset("RST-TEST-0003", TENANT, now, now.plus(Duration.ofDays(9)), now,
                licences.keyId()),com.documentplatform.licensing.issue.SigningKeys.generate().getPrivate());
        assertEquals(ResetRefusal.RESET_FORGED,
                assertThrows(ResetException.class, () -> service().applyReset(forged.getBytes())).code());
    }

    @Test
    void anEarlyRenewalWaitsAndIntakeNeverStops() {
        LicenceService s = service();
        assertEquals(LicenceService.StoreResult.STORED_FIRST,
                s.store(licences.sign(version(1, "2026-01-01", "2026-12-31"))).result());
        LicenceService.StoreOutcome renewal = s.store(licences.sign(version(2, "2027-01-01", "2027-12-31")));
        assertEquals(LicenceService.StoreResult.STORED_WAITING, renewal.result());
        LicenceStatus now = s.status();
        assertEquals("LIC-TEST-0001", now.licence().licenceId(), "the current licence stays in force");
        assertEquals("LIC-TEST-0002", now.renewalWaiting().orElseThrow().licenceId());
        assertTrue(s.intakeAllowed());

        clock.set("2026-12-31T23:59:59Z");
        assertEquals("LIC-TEST-0001", s.status().licence().licenceId());
        assertTrue(s.intakeAllowed());
        clock.set("2027-01-01T00:00:00Z");
        LicenceStatus after = s.status();
        assertEquals("LIC-TEST-0002", after.licence().licenceId(), "takes over on its start date");
        assertEquals(LicenceState.VALID, after.state());
        assertTrue(store.get(WAITING).isEmpty());
        assertTrue(s.intakeAllowed());
    }

    @Test
    void aRenewalThatStartsNowReplacesTheCurrentLicence() {
        LicenceService s = service();
        s.store(licences.sign(version(1, "2026-01-01", "2026-12-31")));
        s.store(licences.sign(version(2, "2027-01-01", "2027-12-31"))); // waiting
        assertEquals(LicenceService.StoreResult.STORED_CURRENT,
                s.store(licences.sign(version(3, "2026-11-01", "2027-12-31"))).result());
        assertEquals("LIC-TEST-0003", s.status().licence().licenceId());
        assertTrue(store.get(WAITING).isEmpty(), "the older waiting licence is dropped");
    }

    @Test
    void theFirstLicenceStoredBeforeItsStartIsCurrentAndNotStarted() {
        LicenceService s = service();
        assertEquals(LicenceService.StoreResult.STORED_FIRST,
                s.store(licences.sign(version(1, "2027-01-01", "2027-12-31"))).result());
        assertEquals(LicenceState.NOT_STARTED, s.status().state());
        assertFalse(s.intakeAllowed());
    }

    @Test
    void storingIsIdempotent() {
        LicenceService s = service();
        String file = licences.sign(version(1, "2026-01-01", "2026-12-31"));
        s.store(file);
        assertEquals(LicenceService.StoreResult.ALREADY_STORED, s.store(file).result());
    }

    @Test
    void aTamperedStoredRowIsRefusedOnRead() {
        service().store(licences.sign(version(1, "2026-01-01", "2026-12-31")));
        String file = store.get(CURRENT).orElseThrow().file();
        store.overwriteFileForTest(CURRENT, file.replace("\"amount\": 1000", "\"amount\": 9000"));
        assertEquals(LicenceRefusal.LICENCE_TAMPERED,
                assertThrows(LicenceException.class, () -> service().status()).code());
        // Storing the good file again repairs it.
        assertEquals(LicenceService.StoreResult.STORED_FIRST, service().store(file).result());
        assertDoesNotThrow(() -> service().status());
    }

    @Test
    void aTamperedWaitingRowIsRefusedToo() {
        LicenceService s = service();
        s.store(licences.sign(version(1, "2026-01-01", "2026-12-31")));
        s.store(licences.sign(version(2, "2027-01-01", "2027-12-31")));
        store.overwriteFileForTest(WAITING, store.get(WAITING).orElseThrow().file().replace("2027-12-31", "2037-12-31"));
        assertEquals(LicenceRefusal.LICENCE_TAMPERED, assertThrows(LicenceException.class, s::status).code());
    }

    @Test
    void kindsAndTheTestEnvironmentFlag() {
        for (LicenceKind kind : LicenceKind.values()) {
            InMemoryLicenceStore own = new InMemoryLicenceStore();
            LicenceService s = new LicenceService(own, new LicenceVerifier(licences.trustedKeys()),
                    new ClockGuard(clock, new InMemoryLastSeenStore()), new InMemoryUsageLedger(), PRODUCT, null);
            s.store(licences.sign(licences.subscription(kind, LocalDate.parse("2026-11-01"), LocalDate.parse("2026-12-20"))));
            assertEquals(kind, s.kind());
            assertEquals(kind == LicenceKind.NON_PRODUCTION, s.testEnvironment());
            s.recordUsage(SLOT, AllowanceType.PAGES_PER_MONTH, 5, false);
            assertEquals(kind == LicenceKind.PRODUCTION, s.ledger().records().get(0).billable());
        }
    }

    @Test
    void aLicenceWithoutThisAppsProductStopsIntakeButStarts() {
        LicenceService other = service("PRODUCT_Z");
        other.store(licences.sign(version(1, "2026-01-01", "2026-12-31")));
        LicenceStatus s = assertDoesNotThrow(other::status);
        assertFalse(other.productLicensed(s));
        assertFalse(other.intakeAllowed());
    }

    @Test
    void usageIsRecordedAndReportedNeverBlocked() {
        LicenceService s = service();
        s.store(licences.sign(version(1, "2026-01-01", "2026-12-31")));
        UsageAssessment a = s.recordUsage(SLOT, AllowanceType.PAGES_PER_MONTH, 1500, false);
        assertEquals(UsageLevel.OVER_LIMIT, a.level());
        assertEquals(List.of(1500L), ledger.records().stream().map(r -> r.amount()).toList());
    }
}
