package com.documentplatform.modelgov.spring;

import com.documentplatform.modelgov.ConfigFingerprint;
import com.documentplatform.modelgov.Model;
import com.documentplatform.modelgov.ModelEvent;
import com.documentplatform.modelgov.ModelEvents;
import com.documentplatform.modelgov.ModelGate;
import com.documentplatform.modelgov.ModelGovAudit;
import com.documentplatform.modelgov.ModelGovCode;
import com.documentplatform.modelgov.ModelGovException;
import com.documentplatform.modelgov.ModelGovStore;
import com.documentplatform.modelgov.ModelGovernance;
import com.documentplatform.modelgov.ModelMeasures;
import com.documentplatform.modelgov.ModelPack;
import com.documentplatform.modelgov.ModelReviews;
import com.documentplatform.modelgov.ModelUse;
import com.documentplatform.modelgov.Monitoring;
import com.documentplatform.modelgov.PackTexts;
import com.documentplatform.modelgov.Threshold;
import com.documentplatform.modelgov.ValidationRecord;
import com.documentplatform.modelgov.jdbc.ModelGovSchemaException;
import com.documentplatform.modelgov.jdbc.PostgresModelGovStore;
import com.documentplatform.modelgov.testkit.InMemoryModelGovStore;
import com.documentplatform.modelgov.testkit.ModelGovFixtures;
import com.documentplatform.modelgov.testkit.RecordingAudit;
import com.documentplatform.monitoring.CheckState;
import com.documentplatform.monitoring.DependencyCheck;
import com.documentplatform.monitoring.MonitoringCode;
import com.documentplatform.monitoring.Verdict;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.documentplatform.modelgov.testkit.ModelGovFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelGovWiringTest {

    static final class TestClock extends Clock {
        volatile Instant now = Instant.parse("2026-11-01T09:00:00Z");

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

    static final String[] ROLES = {"platform.model-governance.approver-roles=" + APPROVER_ROLE + "," + COMPLIANCE_ROLE,
            "platform.model-governance.forbidden-roles=" + FORBIDDEN_ROLE};

    private static ApplicationContextRunner app(String... more) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ModelGovAutoConfiguration.class))
                .withPropertyValues(ROLES)
                .withPropertyValues(more);
    }

    @Test
    void withTheAppsBeans() {
        TestClock clock = new TestClock();
        RecordingAudit audit = new RecordingAudit();
        List<ModelEvent> seen = new CopyOnWriteArrayList<>();
        UUID entity = UUID.randomUUID();
        app("platform.model-governance.review-every.high=60d", "platform.model-governance.worker.enabled=false")
                .withBean(ModelGovStore.class, InMemoryModelGovStore::new)
                .withBean(ModelGovAudit.class, () -> audit)
                .withBean("listener", ModelEvents.class, () -> seen::add)
                .withBean(PackTexts.class, ModelGovFixtures::packTexts)
                .withBean(Clock.class, () -> clock)
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure(), () -> String.valueOf(ctx.getStartupFailure()));
                    assertInstanceOf(InMemoryModelGovStore.class, ctx.getBean(ModelGovStore.class));
                    assertFalse(ctx.containsBean("modelGovStoreDatabase"));
                    assertTrue(ctx.getBeansOfType(ModelGovWorker.class).isEmpty(), "worker switched off");
                    ModelGovernance gov = ctx.getBean(ModelGovernance.class);
                    ModelMeasures measures = ctx.getBean(ModelMeasures.class);
                    ModelGovCheck check = ctx.getBean(ModelGovCheck.class);
                    assertEquals("model-governance", check.name());
                    assertFalse(check.requiredAtStartup());

                    Model m = gov.registerModel(vendorModel(), "admin");
                    ModelUse u = gov.registerUse(ModelGovFixtures.use(m, null, ModelUse.Materiality.HIGH), "admin");
                    gov.setDefaultThreshold(Threshold.byDefault(u.useId(), "accuracy", new BigDecimal("0.9"),
                            Threshold.Direction.HIGHER_IS_BETTER), "admin");
                    ValidationRecord v = gov.recordValidation(u.useId(), entity, "example-set", "1",
                            Map.of("accuracy", new BigDecimal("0.95")), "validator");
                    assertEquals(LocalDate.parse("2026-11-01").plusDays(60),
                            ctx.getBean(ModelGovStore.class).use(u.useId()).orElseThrow().nextReviewDue(),
                            "review interval from the properties");
                    ModelGovException e = assertThrows(ModelGovException.class,
                            () -> gov.approve(v.validationId(), bothRoles()));
                    assertEquals(ModelGovCode.MOD_APPROVER_CONFLICT, e.code(), "roles from the properties");
                    gov.approve(v.validationId(), approver());
                    gov.goLive(u.useId(), entity, v.validationId(), "release");
                    ctx.getBean(ModelGate.class).requireLive(u.useId(), entity, m.digest(), ConfigFingerprint.of(CONFIG));
                    assertTrue(audit.count("approved") == 1, "the app's audit");
                    assertEquals(CheckState.WORKING, check.check().state());

                    measures.setDefaultThreshold(Monitoring.MonitorThreshold.byDefault(u.useId(), "correction_rate",
                            new BigDecimal("0.1"), Threshold.Direction.LOWER_IS_BETTER, 10), "admin");
                    Monitoring.Drift d = measures.report(u.useId(), entity, "correction_rate", new BigDecimal("0.3"),
                            new Monitoring.MeasurePeriod(clock.now, clock.now, 50)).drift().orElseThrow();
                    assertEquals(1, seen.size(), "the app's listener gets the drift");
                    Verdict drift = check.check();
                    assertEquals(CheckState.HAVING_PROBLEMS, drift.state());
                    assertEquals(ModelGovCode.MOD_DRIFT_UNHANDLED, drift.code());
                    assertEquals(1, drift.details().get("openDrifts"));
                    measures.recordAction(u.useId(), d.driftId(), "Re-validated", "owner");
                    assertEquals(CheckState.WORKING, check.check().state());

                    clock.now = clock.now.plus(Duration.ofDays(60));
                    Verdict overdue = check.check();
                    assertEquals(CheckState.HAVING_PROBLEMS, overdue.state());
                    assertEquals(ModelGovCode.MOD_REVIEW_OVERDUE, overdue.code());
                    assertEquals(u.useId(), overdue.details().get("use"));
                    assertEquals(1, ctx.getBean(ModelReviews.class).due().size());
                    assertEquals(2, seen.size(), "the review notice reaches the app too");

                    ModelPack.Pack pack = ctx.getBean(ModelPack.class).export(PRODUCT, entity,
                            Instant.parse("2026-10-01T00:00:00Z"), clock.now);
                    assertTrue(pack.json().contains("handwriting"), "the app's pack texts");
                });
    }

    @Test
    void withoutAppBeansOnPostgres() {
        Pg db = Pg.migrated();
        DataSource app = db.appUser("select, insert, update, delete");
        app().withBean(DataSource.class, () -> app).run(ctx -> {
            assertNull(ctx.getStartupFailure(), () -> String.valueOf(ctx.getStartupFailure()));
            assertInstanceOf(PostgresModelGovStore.class, ctx.getBean(ModelGovStore.class));
            ModelGovWorker worker = ctx.getBean(ModelGovWorker.class);
            assertTrue(worker.isRunning());
            worker.runOnce();
            ModelGovernance gov = ctx.getBean(ModelGovernance.class);
            Model m = gov.registerModel(vendorModel(), "admin");
            ModelUse u = gov.registerUse(ModelGovFixtures.use(m, null, ModelUse.Materiality.LOW), "admin");
            String json = ctx.getBean(ModelPack.class).export(PRODUCT, UUID.randomUUID(),
                    Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2100-01-01T00:00:00Z")).json();
            assertTrue(json.contains("Not supplied by the app."), "placeholder texts");
            assertTrue(json.contains(u.useId().toString()));
            ModelGovCheck check = ctx.getBean(ModelGovCheck.class);
            assertEquals(CheckState.WORKING, check.check().state());
            assertEquals(MonitoringCode.MON_WORKING, check.check().code());
            db.exec("drop table mg_review_notice");
            Verdict down = check.check();
            assertEquals(CheckState.DOWN, down.state());
            assertEquals(ModelGovSchemaException.Problem.MG_TABLES_INCOMPLETE, down.code());
        });
    }

    @Test
    void startupRefusesWithoutADatabaseOrWithAWrongSchema() {
        app().run(ctx -> {
            assertNotNull(ctx.getStartupFailure());
            assertTrue(rootMessage(ctx.getStartupFailure()).contains("needs the app's DataSource"),
                    () -> rootMessage(ctx.getStartupFailure()));
        });
        Pg empty = Pg.empty();
        app().withBean(DataSource.class, () -> empty.owner).run(ctx -> {
            Throwable root = root(ctx.getStartupFailure());
            assertInstanceOf(ModelGovSchemaException.class, root);
            assertEquals(ModelGovSchemaException.Problem.MG_TABLES_MISSING, ((ModelGovSchemaException) root).problem());
        });
        Pg readOnly = Pg.migrated();
        app().withBean(DataSource.class, () -> readOnly.appUser("select")).run(ctx -> assertEquals(
                ModelGovSchemaException.Problem.MG_PRIVILEGES_MISSING,
                ((ModelGovSchemaException) root(ctx.getStartupFailure())).problem()));
    }

    @Test
    void wrongSettingsAreAllListed() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ModelGovAutoConfiguration.class))
                .withBean(ModelGovStore.class, InMemoryModelGovStore::new)
                .withPropertyValues("platform.model-governance.forbidden-roles=X",
                        "platform.model-governance.review-every.high=0d",
                        "platform.model-governance.review-every.low=36h",
                        "platform.model-governance.measure-retention=10d")
                .run(ctx -> {
                    Throwable root = root(ctx.getStartupFailure());
                    assertInstanceOf(ModelGovConfig.Invalid.class, root);
                    List<String> p = ((ModelGovConfig.Invalid) root).problems();
                    assertEquals(4, p.size(), p::toString);
                    assertTrue(p.get(0).contains("approver-roles"));
                    assertTrue(root.getMessage().contains("review-every.high"));
                    assertTrue(root.getMessage().contains("review-every.low"));
                    assertTrue(root.getMessage().contains("measure-retention"));
                });
        app("platform.model-governance.forbidden-roles=" + APPROVER_ROLE)
                .withBean(ModelGovStore.class, InMemoryModelGovStore::new)
                .run(ctx -> assertTrue(root(ctx.getStartupFailure()).getMessage().contains("cannot be both")));
    }

    @Test
    void switchedOffOrWithoutMonitoring() {
        app("platform.model-governance.enabled=false").run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertTrue(ctx.getBeansOfType(ModelGovernance.class).isEmpty());
        });
        app().withBean(ModelGovStore.class, InMemoryModelGovStore::new)
                .withClassLoader(new FilteredClassLoader(DependencyCheck.class))
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure(), () -> String.valueOf(ctx.getStartupFailure()));
                    assertNotNull(ctx.getBean(ModelGovernance.class));
                    assertTrue(ctx.getBeansOfType(ModelGovCheck.class).isEmpty());
                });
    }

    private static Throwable root(Throwable t) {
        assertNotNull(t, "the context should not start");
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t;
    }

    private static String rootMessage(Throwable t) {
        return String.valueOf(root(t).getMessage());
    }
}
