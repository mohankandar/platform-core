package com.documentplatform.identity.jdbc;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.identity.store.ActivationOutcome;
import com.documentplatform.identity.store.EntityRecord;
import com.documentplatform.identity.store.EntityStatus;
import com.documentplatform.identity.store.LicensedSlots;
import com.documentplatform.identity.store.SlotActivation;
import com.documentplatform.licensing.Allowance;
import com.documentplatform.licensing.AllowanceType;
import com.documentplatform.licensing.ClockGuard;
import com.documentplatform.licensing.EntitySlot;
import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.LicenceKind;
import com.documentplatform.licensing.LicenceService;
import com.documentplatform.licensing.LicenceVerifier;
import com.documentplatform.licensing.inmemory.InMemoryLastSeenStore;
import com.documentplatform.licensing.inmemory.InMemoryLicenceStore;
import com.documentplatform.licensing.inmemory.InMemoryUsageLedger;
import com.documentplatform.licensing.inmemory.TestLicences;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.documentplatform.identity.store.ActivationOutcome.Result.*;
import static org.junit.jupiter.api.Assertions.*;

/** The Business Admin activates and names slots: the rules, on Postgres, against a real licence. */
class SlotActivationTest {

    static final String TENANT = TestLicences.TENANT;
    static final String S1 = "ENT-TEST-0001";
    static final String S2 = "ENT-TEST-0002";
    static final String S3 = "ENT-TEST-0003";
    static final String DROPPED = "ENT-TEST-0009";
    static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final TestLicences licences = new TestLicences();
    private final InMemoryLicenceStore licenceStore = new InMemoryLicenceStore();

    private LicenceService service() {
        return new LicenceService(licenceStore, new LicenceVerifier(licences.trustedKeys()),
                new ClockGuard(CLOCK, Duration.ofHours(1), new InMemoryLastSeenStore()), new InMemoryUsageLedger(),
                TestLicences.PRODUCT, null);
    }

    /** A licence with slots S1, S2 and S3. */
    private void storeLicence() {
        Licence base = licences.subscription(LicenceKind.PRODUCTION, LocalDate.parse("2026-01-01"),
                LocalDate.parse("2027-12-31"));
        List<EntitySlot> slots = new ArrayList<>();
        for (String s : List.of(S1, S2, S3)) {
            slots.add(new EntitySlot(s, List.of(new Allowance(TestLicences.PRODUCT, AllowanceType.PAGES_PER_MONTH, 1000))));
        }
        Licence licence = new Licence(base.licenceId(), 1, base.customer(), base.tenantId(), base.products(), slots,
                base.type(), base.kind(), base.startsOn(), base.expiresOn(), null, base.deployment(), base.keyId(),
                base.issuedAt());
        service().store(licences.sign(licence));
    }

    /** idn_ rows as setup leaves them: S1 and S2 stored (S3 not yet), DROPPED stored but no longer licensed. */
    private DataSource installed(Pg db) {
        DataSource app = db.appUser(PostgresIdentityStoresTest.DML);
        PostgresEntityStore store = new PostgresEntityStore(app);
        store.saveCustomer(TENANT, "Test Customer");
        store.syncSlots(TENANT, List.of(S1, S2, DROPPED));
        store.syncSlots(TENANT, List.of(S1, S2));
        return app;
    }

    private SlotActivation activation(DataSource app) {
        return new SlotActivation(new PostgresEntityStore(app), LicenceSlots.of(service()), CLOCK);
    }

    @Test
    void aLicensedSlotIsActivatedOnceAndNamed() {
        storeLicence();
        DataSource app = installed(Pg.migrated());
        SlotActivation activation = activation(app);

        ActivationOutcome done = activation.activate(S1, "  Alpha Ltd  ", "admin-1");
        assertEquals(ACTIVATED, done.result());
        EntityRecord r = done.entity();
        assertEquals(EntityStatus.ACTIVE, r.status());
        assertEquals("Alpha Ltd", r.displayName(), "trimmed");
        assertEquals("admin-1", r.activatedBy());
        assertEquals(NOW, r.activatedAt());
        assertEquals(EntityUuid.fromSlotId(S1), r.entityUuid(), "the key of the app's own entity row");
        assertEquals("Entity slot ENT-TEST-0001 activated.", done.message());

        assertEquals(ALREADY_ACTIVATED, activation.activate(S1, "Alpha again", "admin-2").result());
        assertEquals("admin-1", new PostgresEntityStore(app).entity(S1).orElseThrow().activatedBy(), "unchanged");
    }

    @Test
    void slotsNotInTheLicenceAreRefused() {
        storeLicence();
        DataSource app = installed(Pg.migrated());
        SlotActivation activation = activation(app);
        assertEquals(NOT_IN_LICENCE, activation.activate("ENT-OTHER-0001", "Other", "admin-1").result());
        ActivationOutcome dropped = activation.activate(DROPPED, "Dropped", "admin-1");
        assertEquals(NOT_IN_LICENCE, dropped.result());
        assertEquals(EntityStatus.NOT_IN_LICENCE, dropped.entity().status());
        assertEquals(SLOT_UNKNOWN, activation.activate(S3, "Gamma", "admin-1").result(),
                "licensed, but setup has not stored it yet");
        assertEquals(NOT_A_SLOT_ID, activation.activate("TEN-TEST-0001", "x", "admin-1").result());

        // The store refuses a NOT_IN_LICENCE slot by itself too, whatever the caller checked.
        assertEquals(NOT_IN_LICENCE, new PostgresEntityStore(app).activate(DROPPED, "x", "a", NOW).result());
    }

    @Test
    void withoutAValidLicenceNothingIsActivated() {
        DataSource app = installed(Pg.migrated());
        ActivationOutcome o = activation(app).activate(S1, "Alpha", "admin-1");
        assertEquals(NO_LICENCE, o.result());
        assertTrue(o.refused());
        assertEquals(EntityStatus.NOT_ACTIVATED, new PostgresEntityStore(app).entity(S1).orElseThrow().status());

        LicensedSlots otherCustomer = () -> Optional.of(new LicensedSlots.Current("TEN-OTHER-0001", Set.of(S1)));
        assertEquals(NOT_IN_LICENCE, new SlotActivation(new PostgresEntityStore(app), otherCustomer, CLOCK)
                .activate(S1, "Alpha", "admin-1").result(), "a licence of another customer does not count");
    }

    @Test
    void namesAreCheckedAndUniquePerCustomer() {
        storeLicence();
        DataSource app = installed(Pg.migrated());
        SlotActivation activation = activation(app);
        for (String bad : new String[]{null, "", "   ", "a".repeat(201), "Bad\u0007Name", "Zero​width"}) {
            assertEquals(NAME_INVALID, activation.activate(S1, bad, "admin-1").result(), String.valueOf(bad));
        }
        assertEquals(ACTIVATED, activation.activate(S1, "Alpha", "admin-1").result());
        ActivationOutcome taken = activation.activate(S2, "ALPHA", "admin-1");
        assertEquals(NAME_TAKEN, taken.result());
        assertFalse(taken.message().contains("ALPHA"), "messages never repeat what was typed");
        assertThrows(IllegalArgumentException.class, () -> activation.activate(S2, "Beta", " "));
    }

    @Test
    void renameRules() {
        storeLicence();
        DataSource app = installed(Pg.migrated());
        SlotActivation activation = activation(app);
        activation.activate(S1, "Alpha", "admin-1");
        assertEquals(NOT_ACTIVATED, activation.rename(S2, "Beta", "admin-1").result());
        assertEquals(UNCHANGED, activation.rename(S1, "Alpha", "admin-1").result());
        ActivationOutcome renamed = activation.rename(S1, "Alpha Group", "admin-2");
        assertEquals(RENAMED, renamed.result());
        assertEquals("Alpha Group", renamed.entity().displayName());
        assertEquals("admin-1", renamed.entity().activatedBy(), "activation is not rewritten");
        activation.activate(S2, "Beta", "admin-1");
        assertEquals(NAME_TAKEN, activation.rename(S2, "alpha group", "admin-1").result());
        assertEquals(RENAMED, activation.rename(S1, "ALPHA GROUP", "admin-1").result(), "its own name in other case");
        assertEquals(SLOT_UNKNOWN, activation.rename("ENT-NOT-STORED", "x", "admin-1").result());
    }

    @Test
    void twoAdminsAtOnceNeverGetTheSameName() throws Exception {
        storeLicence();
        DataSource app = installed(Pg.migrated());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<ActivationOutcome>> results = new ArrayList<>();
        for (String slot : List.of(S1, S2)) {
            results.add(pool.submit(() -> activation(app).activate(slot, "Same Name", "admin-" + slot)));
        }
        List<ActivationOutcome.Result> got = new ArrayList<>();
        for (Future<ActivationOutcome> f : results) got.add(f.get().result());
        pool.shutdown();
        assertEquals(1, got.stream().filter(r -> r == ACTIVATED).count(), got.toString());
        assertEquals(1, got.stream().filter(r -> r == NAME_TAKEN).count(), got.toString());
    }

    @Test
    void theAppActivatesAndCreatesItsEntityRowInOneTransaction() throws Exception {
        storeLicence();
        Pg db = Pg.migrated();
        DataSource app = installed(db);
        db.exec("create table app_entity (id uuid primary key, name text not null)",
                "grant select, insert on app_entity to " + db.schema + "_app");

        try (Connection c = app.getConnection()) {
            c.setAutoCommit(false);
            ActivationOutcome o = new SlotActivation(PostgresEntityStore.onConnection(c), LicenceSlots.of(service()), CLOCK)
                    .activate(S1, "Alpha", "admin-1");
            assertEquals(ACTIVATED, o.result());
            c.rollback(); // the app's own insert failed, say
        }
        assertEquals(EntityStatus.NOT_ACTIVATED, new PostgresEntityStore(app).entity(S1).orElseThrow().status(),
                "rolled back with the app's transaction");

        try (Connection c = app.getConnection()) {
            c.setAutoCommit(false);
            ActivationOutcome o = new SlotActivation(PostgresEntityStore.onConnection(c), LicenceSlots.of(service()), CLOCK)
                    .activate(S1, "Alpha", "admin-1");
            try (PreparedStatement ps = c.prepareStatement("insert into app_entity (id, name) values (?, ?)")) {
                ps.setObject(1, o.entity().entityUuid());
                ps.setString(2, o.entity().displayName());
                ps.executeUpdate();
            }
            c.commit();
            assertFalse(c.isClosed(), "the store never closes the caller's connection");
        }
        assertEquals(EntityStatus.ACTIVE, new PostgresEntityStore(app).entity(S1).orElseThrow().status());
        assertEquals(1, db.count("select count(*) from app_entity where id = '" + EntityUuid.fromSlotId(S1) + "'"));
    }
}
