package com.documentplatform.modelgov.jdbc;

import com.documentplatform.modelgov.ConfigFingerprint;
import com.documentplatform.modelgov.Decisions;
import com.documentplatform.modelgov.Model;
import com.documentplatform.modelgov.ModelGovStore;
import com.documentplatform.modelgov.ModelUse;
import com.documentplatform.modelgov.Threshold;
import com.documentplatform.modelgov.ValidationRecord;
import com.documentplatform.modelgov.testkit.InMemoryModelGovStore;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.documentplatform.modelgov.testkit.ModelGovFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The schema step, the startup check, the DML-only app user, insert-only records (even for the schema owner) and the
 * two stores giving back the same rows.
 */
class ModelGovSchemaTest {

    private static final Instant AT = Instant.parse("2026-11-01T09:00:00Z");
    /** Older than the database's 30-day floor, so the purge may remove it. */
    private static final Instant OLD = Instant.parse("2026-01-01T09:00:00Z");

    @Test
    void verifyPassesForTheAppUserAndCreatesNothing() {
        Pg db = Pg.migrated();
        List<String> before = db.tables();
        ModelGovSchema.verify(db.appUser("select, insert, update, delete"));
        assertEquals(before, db.tables());
        assertTrue(before.containsAll(ModelGovSchema.TABLES));
        assertTrue(before.contains(ModelGovSchema.VERSION_TABLE));
    }

    @Test
    void verifyNamesWhatIsWrong() {
        assertEquals(ModelGovSchemaException.Problem.MG_TABLES_MISSING, problem(Pg.empty().owner));

        Pg older = Pg.migrated();
        older.exec("delete from mg_schema_version");
        assertEquals(ModelGovSchemaException.Problem.MG_VERSION_UNKNOWN, problem(older.owner));

        Pg old = Pg.migrated();
        old.exec("update mg_schema_version set version = 1");
        assertEquals(ModelGovSchemaException.Problem.MG_VERSION_OLDER, problem(old.owner));

        Pg newer = Pg.migrated();
        newer.exec("insert into mg_schema_version (version) values (" + (ModelGovSchema.VERSION + 1) + ")");
        assertEquals(ModelGovSchemaException.Problem.MG_VERSION_NEWER, problem(newer.owner));

        Pg incomplete = Pg.migrated();
        incomplete.exec("drop table mg_live_history");
        assertEquals(ModelGovSchemaException.Problem.MG_TABLES_INCOMPLETE, problem(incomplete.owner));

        Pg readOnly = Pg.migrated();
        ModelGovSchemaException e = assertThrows(ModelGovSchemaException.class,
                () -> ModelGovSchema.verify(readOnly.appUser("select")));
        assertEquals(ModelGovSchemaException.Problem.MG_PRIVILEGES_MISSING, e.problem());
        assertEquals(e.problem(), e.platformCode());
        assertTrue(e.getMessage().contains("INSERT"), e.getMessage());
    }

    @Test
    void theAppUserCannotChangeTheTables() {
        Pg db = Pg.migrated();
        DataSource app = db.appUser("select, insert, update, delete");
        assertThrows(IllegalStateException.class, () -> Pg.exec(app, "create table mg_extra (x int)"));
        assertThrows(IllegalStateException.class, () -> Pg.exec(app, "alter table mg_validation add column note text"));
        assertThrows(IllegalStateException.class, () -> Pg.exec(app, "drop trigger mg_validation_insert_only on mg_validation"));
        assertThrows(IllegalStateException.class, () -> Pg.exec(app, "insert into mg_schema_version (version) values (9)"));
    }

    @Test
    void recordsAreInsertOnlyEvenForTheOwner() {
        Pg db = Pg.migrated();
        DataSource app = db.appUser("select, insert, update, delete");
        Scenario s = Scenario.write(new PostgresModelGovStore(app));
        String v = "'" + s.validation.validationId() + "'";
        for (DataSource who : List.of(app, db.owner)) {
            for (String sql : List.of(
                    "update mg_validation set passed = true where validation_id = " + v,
                    "update mg_validation set config_fingerprint = 'sha256:" + "0".repeat(64) + "' where validation_id = " + v,
                    "delete from mg_validation where validation_id = " + v,
                    "update mg_validation_measure set result = 1 where validation_id = " + v,
                    "delete from mg_validation_measure where validation_id = " + v,
                    "update mg_approval set approved_by = 'someone-else' where validation_id = " + v,
                    "delete from mg_approval where validation_id = " + v,
                    "update mg_acceptance set reason = 'changed' where validation_id = " + v,
                    "update mg_live_history set changed_by = 'someone-else'",
                    "delete from mg_live_history")) {
                IllegalStateException e = assertThrows(IllegalStateException.class, () -> Pg.exec(who, sql), sql);
                assertTrue(String.valueOf(e.getCause().getMessage()).contains("insert-only"), sql);
            }
        }
        IllegalStateException t = assertThrows(IllegalStateException.class, () -> db.exec("truncate mg_validation cascade"));
        assertTrue(String.valueOf(t.getCause().getMessage()).contains("insert-only"));
        assertEquals(1, db.count("select count(*) from mg_validation"));
        assertEquals(2, db.count("select count(*) from mg_validation_measure"));
        assertFalse(s.store.validation(s.validation.validationId()).orElseThrow().passed());
    }

    @Test
    void readingsLeaveOnlyThroughThePurgeAndMonitoringRecordsAreInsertOnly() {
        Pg db = Pg.migrated();
        DataSource app = db.appUser("select, insert, update, delete");
        PostgresModelGovStore st = new PostgresModelGovStore(app);
        Scenario s = Scenario.write(st);
        UUID reading = UUID.randomUUID();
        st.insertReading(new com.documentplatform.modelgov.Monitoring.Reading(reading, s.use.useId(), s.entity,
                "correction_rate", new BigDecimal("0.2"), new com.documentplatform.modelgov.Monitoring.MeasurePeriod(
                AT, AT, 60), "app", OLD));
        UUID drift = UUID.randomUUID();
        st.insertDrift(new com.documentplatform.modelgov.Monitoring.Drift(drift, s.use.useId(), s.entity,
                "correction_rate", new BigDecimal("0.2"), new BigDecimal("0.1"), Threshold.Direction.LOWER_IS_BETTER,
                60, reading, AT));
        st.insertDriftAction(new com.documentplatform.modelgov.Monitoring.DriftAction(drift, "Re-validated", "owner", AT));
        assertTrue(st.insertReviewNotice(s.use.useId(), java.time.LocalDate.parse("2026-12-01"),
                com.documentplatform.modelgov.Monitoring.ReviewStage.IN_30_DAYS, AT));
        assertFalse(st.insertReviewNotice(s.use.useId(), java.time.LocalDate.parse("2026-12-01"),
                com.documentplatform.modelgov.Monitoring.ReviewStage.IN_30_DAYS, AT), "once per stage");

        for (DataSource who : List.of(app, db.owner)) {
            for (String sql : List.of("update mg_measure set value = 0", "delete from mg_measure",
                    "update mg_drift set value = 0", "delete from mg_drift_action", "update mg_drift_action set note = 'x'",
                    "delete from mg_review_notice")) {
                IllegalStateException e = assertThrows(IllegalStateException.class, () -> Pg.exec(who, sql), sql);
                assertTrue(String.valueOf(e.getCause().getMessage()).contains("insert-only"), sql);
            }
        }
        assertEquals(0, st.purgeReadings(OLD), "nothing older than the cut-off");
        assertEquals(1, st.purgeReadings(OLD.plusSeconds(1)));
        assertEquals(0, db.count("select count(*) from mg_measure"));
        assertEquals(1, db.count("select count(*) from mg_drift"));
    }

    /**
     * model-governance-84 (P037, schema V3): only the schema owner's purge function removes readings, never one younger
     * than 30 days; the app's user cannot delete them itself, even with DELETE and mg.purge_before set.
     */
    @Test
    void onlyTheOwnersPurgeRemovesReadingsAndNeverOneYoungerThan30Days() throws Exception {
        Pg db = Pg.migrated();
        DataSource app = db.appUser("select, insert, update, delete");
        PostgresModelGovStore st = new PostgresModelGovStore(app);
        Scenario s = Scenario.write(st);
        Instant now = Instant.now();
        for (Instant at : List.of(OLD, now.minus(java.time.Duration.ofDays(29)), now)) {
            st.insertReading(new com.documentplatform.modelgov.Monitoring.Reading(UUID.randomUUID(), s.use.useId(),
                    s.entity, "correction_rate", new BigDecimal("0.2"),
                    new com.documentplatform.modelgov.Monitoring.MeasurePeriod(at, at, 60), "app", at));
        }
        // The app's user sets the purge setting itself and deletes: refused, as it is not the owner.
        try (java.sql.Connection c = app.getConnection()) {
            c.setAutoCommit(false);
            try (java.sql.Statement x = c.createStatement()) {
                x.execute("select set_config('mg.purge_before', '" + now.plusSeconds(60) + "', true)");
                java.sql.SQLException e = assertThrows(java.sql.SQLException.class,
                        () -> x.executeUpdate("delete from mg_measure"));
                assertTrue(e.getMessage().contains("insert-only"), e.getMessage());
            }
            c.rollback();
        }
        assertEquals(3, db.count("select count(*) from mg_measure"));
        // Asked to purge everything: only the reading older than 30 days goes.
        assertEquals(1, st.purgeReadings(now.plusSeconds(60)));
        assertEquals(2, db.count("select count(*) from mg_measure"));
        // PUBLIC cannot run the function: only the user it is granted to.
        assertFalse(db.count("select count(*) from information_schema.routine_privileges where routine_name = "
                + "'mg_purge_measures' and grantee = 'PUBLIC'") > 0, "revoked from PUBLIC");
        DataSource noExecute = Pg.migrated().appUserWithoutPurge();
        ModelGovSchemaException e = assertThrows(ModelGovSchemaException.class, () -> ModelGovSchema.verify(noExecute));
        assertEquals(ModelGovSchemaException.Problem.MG_PRIVILEGES_MISSING, e.problem());
        assertTrue(e.getMessage().contains("mg_purge_measures"), e.getMessage());
    }

    @Test
    void bothStoresGiveBackTheSameRows() {
        Pg db = Pg.migrated();
        Scenario pg = Scenario.write(new PostgresModelGovStore(db.appUser("select, insert, update, delete")));
        Scenario mem = Scenario.write(new InMemoryModelGovStore(), pg);
        for (Scenario s : List.of(pg, mem)) {
            assertEquals(pg.model.modelId(), s.store.model(pg.model.modelId()).orElseThrow().modelId());
        }
        assertEquals(mem.store.model(pg.model.modelId()), pg.store.model(pg.model.modelId()));
        assertEquals(mem.store.use(pg.use.useId()), pg.store.use(pg.use.useId()));
        assertEquals(mem.store.modelHistory(pg.model.modelId()), pg.store.modelHistory(pg.model.modelId()));
        assertEquals(mem.store.useHistory(pg.use.useId()), pg.store.useHistory(pg.use.useId()));
        assertEquals(mem.store.thresholds(pg.use.useId()), pg.store.thresholds(pg.use.useId()));
        assertEquals(mem.store.validation(pg.validation.validationId()), pg.store.validation(pg.validation.validationId()));
        assertEquals(mem.store.validations(pg.use.useId(), pg.entity), pg.store.validations(pg.use.useId(), pg.entity));
        assertEquals(mem.store.approval(pg.validation.validationId()), pg.store.approval(pg.validation.validationId()));
        assertEquals(mem.store.acceptance(pg.validation.validationId()), pg.store.acceptance(pg.validation.validationId()));
        assertEquals(mem.store.live(pg.use.useId(), pg.entity), pg.store.live(pg.use.useId(), pg.entity));
        assertEquals(mem.store.liveHistory(pg.use.useId(), pg.entity), pg.store.liveHistory(pg.use.useId(), pg.entity));

        for (ModelGovStore st : List.of(pg.store, mem.store)) {
            assertTrue(st.updateModel(pg.model, 99, change()).isEmpty(), "stale version");
            assertFalse(st.switchLive(pg.live, 99, pg.liveChange), "stale live version");
            assertThrows(RuntimeException.class, () -> st.insertApproval(pg.approval), "one approval per validation");
            assertThrows(RuntimeException.class, () -> st.insertValidation(pg.validation), "validation IDs unique");
        }
        assertEquals(mem.store.liveHistory(pg.use.useId(), pg.entity), pg.store.liveHistory(pg.use.useId(), pg.entity));
    }

    private static ModelGovSchemaException.Problem problem(DataSource ds) {
        return assertThrows(ModelGovSchemaException.class, () -> ModelGovSchema.verify(ds)).problem();
    }

    private static ModelGovStore.Change change() {
        return new ModelGovStore.Change("tester", AT, List.of("created"));
    }

    /** The same rows written straight to a store (no rules), for comparing the stores. */
    private record Scenario(ModelGovStore store, UUID entity, Model model, ModelUse use, ValidationRecord validation,
                            Decisions.Approval approval, Decisions.Live live, Decisions.LiveChange liveChange) {

        static Scenario write(ModelGovStore store) {
            return write(store, null);
        }

        static Scenario write(ModelGovStore store, Scenario like) {
            Model m = like != null ? like.model : customerModel();
            UUID entity = like != null ? like.entity : UUID.randomUUID();
            ModelUse u = like != null ? like.use : com.documentplatform.modelgov.testkit.ModelGovFixtures.use(m, null, ModelUse.Materiality.MEDIUM);
            store.insertModel(m, change());
            store.updateModel(m.withVersion("8", digest("customer-8")), 1, new ModelGovStore.Change("tester", AT,
                    List.of("modelVersion", "digest")));
            store.insertUse(u, change());
            store.putThreshold(Threshold.byDefault(u.useId(), "accuracy", new BigDecimal("0.90"),
                    Threshold.Direction.HIGHER_IS_BETTER), change());
            store.putThreshold(Threshold.forEntity(u.useId(), entity, "accuracy", new BigDecimal("0.950"),
                    Threshold.Direction.HIGHER_IS_BETTER), change());
            store.putThreshold(Threshold.byDefault(u.useId(), "false_rate", new BigDecimal("0.05"),
                    Threshold.Direction.LOWER_IS_BETTER), change());
            ValidationRecord v = like != null ? like.validation : new ValidationRecord(UUID.randomUUID(), u.useId(),
                    entity, m.modelId(), m.name(), "8", m.provider(), digest("customer-8"), u.configFingerprint(),
                    "example-set", "3", List.of(
                    new ValidationRecord.MeasureResult("false_rate", new BigDecimal("0.01"), new BigDecimal("0.05"),
                            Threshold.Direction.LOWER_IS_BETTER, true),
                    new ValidationRecord.MeasureResult("accuracy", new BigDecimal("0.80"), new BigDecimal("0.950"),
                            Threshold.Direction.HIGHER_IS_BETTER, false)), false, "validator-1", AT);
            store.insertValidation(v);
            Decisions.Acceptance acc = new Decisions.Acceptance(v.validationId(), "compliance-1", AT,
                    v.failedSummary(), "Known weakness; a reviewer checks each item");
            store.insertAcceptance(acc);
            Decisions.Approval a = new Decisions.Approval(v.validationId(), "approver-1",
                    Set.of(APPROVER_ROLE, COMPLIANCE_ROLE), AT);
            store.insertApproval(a);
            Decisions.Live live = new Decisions.Live(u.useId(), entity, v.validationId(), null, 1, "release-manager", AT);
            Decisions.LiveChange ch = new Decisions.LiveChange(u.useId(), entity, Decisions.LiveChange.Kind.WENT_LIVE,
                    null, v.validationId(), "release-manager", AT);
            assertTrue(store.switchLive(live, 0, ch));
            assertFalse(store.switchLive(live, 0, ch), "a second first switch loses");
            ModelUse lived = new ModelUse(u.useId(), u.product(), u.feature(), u.entity(), u.purpose(),
                    u.materiality(), u.materialityReason(), u.humanInTheLoop(), u.vendorOwner(), u.customerOwner(),
                    u.modelId(), ConfigFingerprint.of(CONFIG_CHANGED), ModelUse.Status.LIVE, AT,
                    java.time.LocalDate.parse("2027-05-01"), true, 120, 1);
            store.updateUse(lived, 1, new ModelGovStore.Change("tester", AT, List.of("configFingerprint", "status")));
            return new Scenario(store, entity, m, u, v, a, live, ch);
        }
    }
}
