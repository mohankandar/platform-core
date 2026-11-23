package com.documentplatform.modelgov;

import com.documentplatform.modelgov.testkit.RecordingAudit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static com.documentplatform.modelgov.ModelGovCode.*;
import static com.documentplatform.modelgov.testkit.ModelGovFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The rules of the register, the change gate and the gate at model start, run against a store. Each store's test
 * class extends this (in memory and Postgres), so both behave the same. When every test of a class ran, each
 * {@link ModelGovCode} must have been seen as a refusal at least once.
 */
public abstract class ModelGovContract {

    /** A clock the test moves on; whole seconds, so Postgres keeps the same instants. */
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

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    private static final Map<Class<?>, Set<ModelGovCode>> SEEN = new ConcurrentHashMap<>();
    private static final Map<Class<?>, AtomicInteger> RAN = new ConcurrentHashMap<>();

    protected abstract ModelGovStore newStore();

    static final BigDecimal ACC = new BigDecimal("0.90");
    static final BigDecimal FALSE_RATE = new BigDecimal("0.05");

    TestClock clock;
    ModelGovStore store;
    RecordingAudit audit;
    ModelGovernance gov;
    ModelGate gate;
    UUID entity;
    UUID otherEntity;

    @BeforeEach
    void setUp() {
        RAN.computeIfAbsent(getClass(), k -> new AtomicInteger()).incrementAndGet();
        clock = new TestClock();
        store = newStore();
        audit = new RecordingAudit();
        gov = new ModelGovernance(store, audit, settings(), clock);
        gate = new ModelGate(store, clock);
        entity = UUID.randomUUID();
        otherEntity = UUID.randomUUID();
    }

    @AfterAll
    static void everyCodeSeen(org.junit.jupiter.api.TestInfo info) {
        Class<?> c = info.getTestClass().orElseThrow();
        long tests = Arrays.stream(allMethods(c)).filter(m -> m.isAnnotationPresent(Test.class)).count();
        AtomicInteger ran = RAN.get(c);
        if (ran == null || ran.get() < tests) return; // a single test was run on its own
        Set<ModelGovCode> missing = EnumSet.allOf(ModelGovCode.class);
        missing.removeAll(SEEN.getOrDefault(c, Set.of()));
        assertEquals(Set.of(), missing, "every model-governance code is reached by a test");
    }

    private static Method[] allMethods(Class<?> c) {
        List<Method> out = new java.util.ArrayList<>();
        for (Class<?> k = c; k != null; k = k.getSuperclass()) out.addAll(Arrays.asList(k.getDeclaredMethods()));
        return out.toArray(Method[]::new);
    }

    ModelGovException refuses(ModelGovCode code, Executable e) {
        ModelGovException ex = assertThrows(ModelGovException.class, e);
        assertEquals(code, ex.code(), ex.getMessage());
        assertEquals(code, ex.platformCode());
        assertTrue(ex.getMessage().startsWith(code.code() + ": "), ex.getMessage());
        SEEN.computeIfAbsent(getClass(), k -> ConcurrentHashMap.newKeySet()).add(code);
        return ex;
    }

    // ---- helpers

    Model model(Model m) {
        return gov.registerModel(m, "register-admin");
    }

    ModelUse use(Model m, UUID scope, ModelUse.Materiality materiality) {
        ModelUse u = gov.registerUse(use(m, scope, materiality, false), "register-admin");
        gov.setDefaultThreshold(Threshold.byDefault(u.useId(), "accuracy", ACC, Threshold.Direction.HIGHER_IS_BETTER),
                "register-admin");
        gov.setDefaultThreshold(Threshold.byDefault(u.useId(), "false_rate", FALSE_RATE,
                Threshold.Direction.LOWER_IS_BETTER), "register-admin");
        return u;
    }

    private static ModelUse use(Model m, UUID scope, ModelUse.Materiality materiality, boolean unused) {
        return com.documentplatform.modelgov.testkit.ModelGovFixtures.use(m, scope, materiality);
    }

    ValidationRecord validate(ModelUse u, UUID e, String accuracy, String falseRate) {
        return gov.recordValidation(u.useId(), e, "example-set", "3", Map.of("accuracy", new BigDecimal(accuracy),
                "false_rate", new BigDecimal(falseRate)), "validator-1");
    }

    ValidationRecord pass(ModelUse u, UUID e) {
        return validate(u, e, "0.95", "0.01");
    }

    ValidationRecord fail(ModelUse u, UUID e) {
        return validate(u, e, "0.80", "0.01");
    }

    LiveModel start(ModelUse u, UUID e) {
        ValidationRecord v = store.validation(store.live(u.useId(), e).orElseThrow().validationId()).orElseThrow();
        return gate.requireLive(u.useId(), e, v.digest(), v.configFingerprint());
    }

    ModelUse current(ModelUse u) {
        return store.use(u.useId()).orElseThrow();
    }

    // ---- the whole path

    @Test
    void registerValidateApproveGoLiveGateStamp() {
        Model m = model(vendorModel());
        ModelUse u = use(m, null, ModelUse.Materiality.MEDIUM);
        assertEquals(ModelUse.Status.PROPOSED, current(u).status());

        ValidationRecord v = pass(u, entity);
        assertTrue(v.passed());
        assertEquals(m.digest(), v.digest());
        assertEquals(ConfigFingerprint.of(CONFIG), v.configFingerprint());
        assertEquals(List.of("accuracy", "false_rate"), v.measures().stream().map(ValidationRecord.MeasureResult::measure).toList());
        assertEquals(ACC, v.measures().get(0).threshold());
        assertEquals(v, store.validation(v.validationId()).orElseThrow());
        assertEquals(List.of(v), store.validations(u.useId(), entity));
        ModelUse validated = current(u);
        assertEquals(ModelUse.Status.VALIDATED, validated.status());
        assertEquals(clock.now, validated.lastValidatedAt());
        assertEquals(java.time.LocalDate.parse("2026-11-01").plusDays(180), validated.nextReviewDue());

        Decisions.Approval a = gov.approve(v.validationId(), approver());
        assertEquals(Set.of(APPROVER_ROLE), store.approval(v.validationId()).orElseThrow().roles());
        assertEquals(a, store.approval(v.validationId()).orElseThrow());

        Decisions.Live live = gov.goLive(u.useId(), entity, v.validationId(), "release-manager");
        assertEquals(v.validationId(), live.validationId());
        assertNull(live.previousValidationId());
        assertEquals(live, store.live(u.useId(), entity).orElseThrow());
        assertEquals(ModelUse.Status.LIVE, current(u).status());

        LiveModel lm = gate.requireLive(u.useId(), entity, m.digest(), ConfigFingerprint.of(CONFIG));
        assertEquals(List.of(), lm.warnings());
        ModelStamp s = lm.stamp();
        assertEquals(new ModelStamp(u.useId(), m.name(), m.modelVersion(), m.digest(), v.validationId(),
                ConfigFingerprint.of(CONFIG)), s);
        assertTrue(s.toString().contains(m.digest().toString()));
        assertFalse(s.toString().contains("example prompt"));
    }

    @Test
    void sameConfigurationInAnotherOrderSameFingerprint() {
        assertEquals(ConfigFingerprint.of(CONFIG), ConfigFingerprint.of(
                "{ \"maxTokens\": 512.0, \"temperature\": 0, \"prompt\": \"example prompt v1\" }"));
        assertNotEquals(ConfigFingerprint.of(CONFIG), ConfigFingerprint.of(CONFIG_CHANGED));
    }

    // ---- history and audit

    @Test
    void historyAndAuditOfEveryChange() {
        Model m = model(vendorModel());
        Model m2 = gov.changeModel(m.withVersion("1.1", digest("vendor-1.1")), m.version(), "register-admin");
        assertEquals(2, m2.version());
        List<ModelGovStore.HistoryRow> mh = store.modelHistory(m.modelId());
        assertEquals(List.of(1L, 2L), mh.stream().map(ModelGovStore.HistoryRow::version).toList());
        assertEquals(List.of("created"), mh.get(0).changedFields());
        assertEquals(List.of("modelVersion", "digest"), mh.get(1).changedFields());
        assertEquals("register-admin", mh.get(1).changedBy());
        assertEquals(clock.now, mh.get(1).changedAt());

        ModelUse u = use(m2, null, ModelUse.Materiality.LOW);
        clock.advance(Duration.ofMinutes(1));
        ModelUse u2 = gov.changeUse(current(u).withMateriality(ModelUse.Materiality.MEDIUM, "Used more widely now"),
                current(u).version(), "register-admin");
        assertEquals(List.of("materiality", "materialityReason"),
                store.useHistory(u.useId()).get(store.useHistory(u.useId()).size() - 1).changedFields());
        assertEquals(2, u2.version());

        ValidationRecord v = pass(u2, entity);
        gov.approve(v.validationId(), approver());
        gov.goLive(u.useId(), entity, v.validationId(), "release-manager");

        assertEquals(2, audit.count("model"));
        assertEquals(2, audit.count("threshold"));
        assertEquals(1, audit.count("validation"));
        assertEquals(1, audit.count("approved"));
        assertEquals(1, audit.count("live"));
        // created, changed, validated, went live
        assertEquals(4, audit.count("use"));
        assertEquals(List.of(1L, 2L, 3L, 4L),
                store.useHistory(u.useId()).stream().map(ModelGovStore.HistoryRow::version).toList());
        assertTrue(audit.lines().stream().anyMatch(l -> l.startsWith("live WENT_LIVE") && l.endsWith("release-manager")));
    }

    @Test
    void staleVersionRefused() {
        Model m = model(vendorModel());
        gov.changeModel(m.withVersion("1.1", digest("vendor-1.1")), 1, "register-admin");
        refuses(MOD_VERSION_CONFLICT, () -> gov.changeModel(m.withVersion("1.2", digest("vendor-1.2")), 1, "x"));
        ModelUse u = use(m, null, ModelUse.Materiality.LOW);
        gov.changeUse(current(u).withReviewOverdueRefuses(true), 1, "x");
        refuses(MOD_VERSION_CONFLICT, () -> gov.changeUse(u.withReviewOverdueRefuses(false), 1, "x"));
    }

    @Test
    void unknownAndInvalidRefused() {
        refuses(MOD_UNKNOWN, () -> gov.registerUse(use(vendorModel(), null, ModelUse.Materiality.LOW, false), "x"));
        refuses(MOD_UNKNOWN, () -> gov.approve(UUID.randomUUID(), approver()));
        refuses(MOD_UNKNOWN, () -> gate.requireLive(UUID.randomUUID(), entity, digest("a"), ConfigFingerprint.of("{}")));
        refuses(MOD_VALUE_INVALID, () -> gov.registerModel(vendorModel(), " "));
        refuses(MOD_VALUE_INVALID, () -> Model.create("x", Model.Provider.VENDOR, "1", new ModelDigest("SHA256", "ab"),
                Model.RunsWhere.HOSTED));
        refuses(MOD_VALUE_INVALID, () -> ModelUse.propose("product_a", "f", null, "p", ModelUse.Materiality.LOW, "r",
                "h", null, null, UUID.randomUUID(), ConfigFingerprint.of("{}")));
        refuses(MOD_VALUE_INVALID, () -> ModelUse.propose(PRODUCT, "f", null, "p", ModelUse.Materiality.LOW, "r", "h",
                null, null, UUID.randomUUID(), "not-a-fingerprint"));
    }

    // ---- thresholds

    @Test
    void customerThresholdOnlyStricter() {
        Model m = model(vendorModel());
        ModelUse u = use(m, null, ModelUse.Materiality.MEDIUM);
        ModelGovException looser = refuses(MOD_THRESHOLD_LOOSER, () -> gov.setEntityThreshold(Threshold.forEntity(
                u.useId(), entity, "accuracy", new BigDecimal("0.85"), Threshold.Direction.HIGHER_IS_BETTER), "customer"));
        assertEquals("0.85", looser.details().get("value"));
        refuses(MOD_THRESHOLD_LOOSER, () -> gov.setEntityThreshold(Threshold.forEntity(u.useId(), entity,
                "false_rate", new BigDecimal("0.10"), Threshold.Direction.LOWER_IS_BETTER), "customer"));
        refuses(MOD_THRESHOLD_LOOSER, () -> gov.setEntityThreshold(Threshold.forEntity(u.useId(), entity,
                "accuracy", new BigDecimal("0.95"), Threshold.Direction.LOWER_IS_BETTER), "customer"));
        refuses(MOD_THRESHOLD_MISSING, () -> gov.setEntityThreshold(Threshold.forEntity(u.useId(), entity,
                "recall", new BigDecimal("0.95"), Threshold.Direction.HIGHER_IS_BETTER), "customer"));

        gov.setEntityThreshold(Threshold.forEntity(u.useId(), entity, "accuracy", new BigDecimal("0.97"),
                Threshold.Direction.HIGHER_IS_BETTER), "customer");
        assertEquals(new BigDecimal("0.97"), gov.thresholdsInForce(u.useId(), entity).get("accuracy").value());
        assertEquals(ACC, gov.thresholdsInForce(u.useId(), otherEntity).get("accuracy").value());
        assertEquals(3, store.thresholds(u.useId()).size());

        ValidationRecord v = pass(u, entity); // 0.95 passes the default but not the customer's 0.97
        assertFalse(v.passed());
        assertEquals(new BigDecimal("0.97"), v.measures().get(0).threshold());
        assertEquals("accuracy 0.95 (>= 0.97)", v.failedSummary());
        assertTrue(pass(u, otherEntity).passed());

        refuses(MOD_THRESHOLD_MISSING, () -> gov.recordValidation(u.useId(), entity, "example-set", "3",
                Map.of("accuracy", BigDecimal.ONE, "false_rate", BigDecimal.ZERO, "recall", BigDecimal.ONE), "v"));
        refuses(MOD_VALUE_INVALID, () -> gov.recordValidation(u.useId(), entity, "example-set", "3",
                Map.of("accuracy", BigDecimal.ONE), "v"));
    }

    // ---- separation of duties

    @Test
    void approverRolesAndSeparationOfDuties() {
        Model m = model(vendorModel());
        ModelUse u = use(m, null, ModelUse.Materiality.HIGH);
        ValidationRecord v = pass(u, entity);
        refuses(MOD_APPROVER_ROLE, () -> gov.approve(v.validationId(), noRole()));
        ModelGovException both = refuses(MOD_APPROVER_CONFLICT, () -> gov.approve(v.validationId(), bothRoles()));
        assertEquals(FORBIDDEN_ROLE, both.details().get("role"));
        refuses(MOD_APPROVER_CONFLICT, () -> gov.approve(v.validationId(), businessAdmin()));
        assertTrue(store.approval(v.validationId()).isEmpty());
        assertEquals(0, audit.count("approved"));

        gov.approve(v.validationId(), complianceOfficer());
        refuses(MOD_ALREADY_APPROVED, () -> gov.approve(v.validationId(), approver()));
        assertEquals("compliance-1", store.approval(v.validationId()).orElseThrow().approvedBy());
    }

    // ---- failing validations, customer models and acceptance

    @Test
    void failedVendorValidationNeverApproved() {
        Model m = model(vendorModel());
        ModelUse u = use(m, null, ModelUse.Materiality.MEDIUM);
        ValidationRecord v = fail(u, entity);
        assertEquals(ModelUse.Status.PROPOSED, current(u).status());
        refuses(MOD_VALIDATION_FAILED, () -> gov.approve(v.validationId(), approver()));
        refuses(MOD_ACCEPTANCE_NOT_APPLICABLE, () -> gov.accept(v.validationId(), approver(), "We accept it"));
        refuses(MOD_NOT_APPROVED, () -> gov.goLive(u.useId(), entity, v.validationId(), "x"));
    }

    @Test
    void failedCustomerModelBlockedForHigh() {
        Model m = model(customerModel());
        ModelUse u = use(m, null, ModelUse.Materiality.HIGH);
        ValidationRecord v = fail(u, entity);
        refuses(MOD_CUSTOMER_MODEL_BLOCKED, () -> gov.approve(v.validationId(), approver()));
        refuses(MOD_CUSTOMER_MODEL_BLOCKED, () -> gov.accept(v.validationId(), approver(), "We accept it"));
        assertTrue(store.acceptance(v.validationId()).isEmpty());
    }

    @Test
    void failedCustomerModelForMediumOnlyWithDocumentedAcceptance() {
        Model m = model(customerModel());
        ModelUse u = use(m, null, ModelUse.Materiality.MEDIUM);
        ValidationRecord v = fail(u, entity);
        refuses(MOD_VALIDATION_FAILED, () -> gov.approve(v.validationId(), approver()));
        refuses(MOD_REASON_REQUIRED, () -> gov.accept(v.validationId(), approver(), " "));
        refuses(MOD_APPROVER_CONFLICT, () -> gov.accept(v.validationId(), bothRoles(), "Known weakness"));
        refuses(MOD_VALUE_INVALID, () -> gov.accept(v.validationId(), approver(),
                "token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJlc2lnbmF0dXJl"));

        Decisions.Acceptance a = gov.accept(v.validationId(), complianceOfficer(),
                "Known weakness on small items; a reviewer checks each one");
        assertEquals("accuracy 0.80 (>= 0.90)", a.results());
        assertEquals(a, store.acceptance(v.validationId()).orElseThrow());
        assertEquals(1, audit.count("accepted"));

        gov.approve(v.validationId(), approver());
        gov.goLive(u.useId(), entity, v.validationId(), "release-manager");
        assertEquals(v.validationId(), start(u, entity).validation().validationId());
    }

    @Test
    void acceptanceNotForLowOrPassed() {
        Model m = model(customerModel());
        ModelUse low = use(m, null, ModelUse.Materiality.LOW);
        ValidationRecord failed = fail(low, entity);
        refuses(MOD_ACCEPTANCE_NOT_APPLICABLE, () -> gov.accept(failed.validationId(), approver(), "ok"));
        refuses(MOD_VALIDATION_FAILED, () -> gov.approve(failed.validationId(), approver()));
        ValidationRecord passed = pass(low, entity);
        refuses(MOD_ACCEPTANCE_NOT_APPLICABLE, () -> gov.accept(passed.validationId(), approver(), "ok"));
    }

    // ---- the gate at model start

    @Test
    void gateRefusesUntilLive() {
        Model m = model(vendorModel());
        ModelUse u = use(m, null, ModelUse.Materiality.MEDIUM);
        String fp = ConfigFingerprint.of(CONFIG);
        refuses(MOD_NOT_VALIDATED, () -> gate.requireLive(u.useId(), entity, m.digest(), fp));
        ValidationRecord v = pass(u, entity);
        refuses(MOD_NOT_APPROVED, () -> gate.requireLive(u.useId(), entity, m.digest(), fp));
        gov.approve(v.validationId(), approver());
        refuses(MOD_NOT_LIVE, () -> gate.requireLive(u.useId(), entity, m.digest(), fp));
        gov.goLive(u.useId(), entity, v.validationId(), "release-manager");
        gate.requireLive(u.useId(), entity, m.digest(), fp);
        refuses(MOD_NOT_VALIDATED, () -> gate.requireLive(u.useId(), otherEntity, m.digest(), fp));
    }

    @Test
    void digestChangeRefusedAtTheGate() {
        Model m = model(vendorModel());
        ModelUse u = use(m, null, ModelUse.Materiality.HIGH);
        ValidationRecord v = pass(u, entity);
        gov.approve(v.validationId(), approver());
        gov.goLive(u.useId(), entity, v.validationId(), "release-manager");
        ModelGovException e = refuses(MOD_DIGEST_MISMATCH, () -> gate.requireLive(u.useId(), entity,
                digest("someone-swapped-the-file"), ConfigFingerprint.of(CONFIG)));
        assertEquals(m.digest().toString(), e.details().get("expected"));

        // The register moves to the new pull: still refused until it is validated, approved and live.
        Model m2 = gov.changeModel(m.withVersion("1.1", digest("vendor-1.1")), m.version(), "register-admin");
        refuses(MOD_DIGEST_MISMATCH, () -> gate.requireLive(u.useId(), entity, m2.digest(), ConfigFingerprint.of(CONFIG)));
        ValidationRecord v2 = pass(current(u), entity);
        assertEquals(m2.digest(), v2.digest());
        gov.approve(v2.validationId(), approver());
        gov.goLive(u.useId(), entity, v2.validationId(), "release-manager");
        assertEquals("1.1", gate.requireLive(u.useId(), entity, m2.digest(), ConfigFingerprint.of(CONFIG)).stamp()
                .modelVersion());
    }

    @Test
    void configurationChangeRefusedAtTheGate() {
        Model m = model(vendorModel());
        ModelUse u = use(m, null, ModelUse.Materiality.MEDIUM);
        ValidationRecord v = pass(u, entity);
        gov.approve(v.validationId(), approver());
        gov.goLive(u.useId(), entity, v.validationId(), "release-manager");
        String changed = ConfigFingerprint.of(CONFIG_CHANGED);
        refuses(MOD_CONFIG_CHANGED, () -> gate.requireLive(u.useId(), entity, m.digest(), changed));
        refuses(MOD_CONFIG_CHANGED, () -> gate.requireLive(u.useId(), entity, m.digest(), null));

        ModelUse u2 = gov.changeUse(current(u).withModel(m.modelId(), changed), current(u).version(), "register-admin");
        assertTrue(store.useHistory(u.useId()).get(store.useHistory(u.useId()).size() - 1).changedFields()
                .contains("configFingerprint"));
        refuses(MOD_CONFIG_CHANGED, () -> gate.requireLive(u.useId(), entity, m.digest(), changed));
        ValidationRecord v2 = pass(u2, entity);
        assertEquals(changed, v2.configFingerprint());
        gov.approve(v2.validationId(), approver());
        gov.goLive(u.useId(), entity, v2.validationId(), "release-manager");
        assertEquals(changed, gate.requireLive(u.useId(), entity, m.digest(), changed).stamp().configFingerprint());
    }

    @Test
    void retiredUseRefused() {
        Model m = model(vendorModel());
        ModelUse u = use(m, null, ModelUse.Materiality.LOW);
        ValidationRecord v = pass(u, entity);
        gov.approve(v.validationId(), approver());
        gov.goLive(u.useId(), entity, v.validationId(), "release-manager");
        gov.changeUse(current(u).retired(), current(u).version(), "register-admin");
        refuses(MOD_RETIRED, () -> gate.requireLive(u.useId(), entity, m.digest(), ConfigFingerprint.of(CONFIG)));
        refuses(MOD_RETIRED, () -> pass(u, entity));
        refuses(MOD_RETIRED, () -> gov.goLive(u.useId(), entity, v.validationId(), "x"));
    }

    @Test
    void reviewOverdueWarnsOrRefuses() {
        Model m = model(vendorModel());
        ModelUse u = use(m, null, ModelUse.Materiality.HIGH);
        ValidationRecord v = pass(u, entity);
        gov.approve(v.validationId(), approver());
        gov.goLive(u.useId(), entity, v.validationId(), "release-manager");
        assertEquals(List.of(), start(u, entity).warnings());

        clock.advance(Duration.ofDays(90));
        assertEquals(List.of(), start(u, entity).warnings(), "due today is not overdue");
        clock.advance(Duration.ofDays(1));
        assertEquals(List.of(MOD_REVIEW_OVERDUE), start(u, entity).warnings());

        gov.changeUse(current(u).withReviewOverdueRefuses(true), current(u).version(), "customer");
        refuses(MOD_REVIEW_OVERDUE, () -> start(u, entity));

        // A new validation moves the review date on.
        ValidationRecord v2 = pass(current(u), entity);
        gov.approve(v2.validationId(), approver());
        gov.goLive(u.useId(), entity, v2.validationId(), "release-manager");
        assertEquals(List.of(), start(u, entity).warnings());
    }

    // ---- live, rollback, scope

    @Test
    void rollbackSwitchesBackAtOnce() {
        Model m = model(vendorModel());
        ModelUse u = use(m, null, ModelUse.Materiality.MEDIUM);
        ValidationRecord v1 = pass(u, entity);
        gov.approve(v1.validationId(), approver());
        refuses(MOD_NO_ROLLBACK_TARGET, () -> gov.rollback(u.useId(), entity, "x"));
        gov.goLive(u.useId(), entity, v1.validationId(), "release-manager");
        refuses(MOD_NO_ROLLBACK_TARGET, () -> gov.rollback(u.useId(), entity, "x"));
        assertEquals(gov.goLive(u.useId(), entity, v1.validationId(), "again"), store.live(u.useId(), entity)
                .orElseThrow(), "the same validation again changes nothing");

        clock.advance(Duration.ofMinutes(5));
        Model m2 = gov.changeModel(m.withVersion("1.1", digest("vendor-1.1")), m.version(), "register-admin");
        ValidationRecord v2 = pass(current(u), entity);
        gov.approve(v2.validationId(), approver());
        Decisions.Live l2 = gov.goLive(u.useId(), entity, v2.validationId(), "release-manager");
        assertEquals(v1.validationId(), l2.previousValidationId());
        gate.requireLive(u.useId(), entity, m2.digest(), ConfigFingerprint.of(CONFIG));

        clock.advance(Duration.ofMinutes(5));
        Decisions.Live back = gov.rollback(u.useId(), entity, "on-call");
        assertEquals(v1.validationId(), back.validationId());
        assertEquals(v2.validationId(), back.previousValidationId());
        assertEquals(v1.validationId(), gate.requireLive(u.useId(), entity, m.digest(), ConfigFingerprint.of(CONFIG))
                .validation().validationId());
        refuses(MOD_DIGEST_MISMATCH, () -> gate.requireLive(u.useId(), entity, m2.digest(), ConfigFingerprint.of(CONFIG)));

        List<Decisions.LiveChange> h = store.liveHistory(u.useId(), entity);
        assertEquals(List.of(Decisions.LiveChange.Kind.WENT_LIVE, Decisions.LiveChange.Kind.WENT_LIVE,
                Decisions.LiveChange.Kind.ROLLED_BACK), h.stream().map(Decisions.LiveChange::kind).toList());
        assertEquals("on-call", h.get(2).changedBy());
        assertEquals(clock.now, h.get(2).changedAt());
        assertEquals(1, audit.lines().stream().filter(l -> l.startsWith("live ROLLED_BACK")).count());
        assertEquals(v2.validationId(), gov.rollback(u.useId(), entity, "on-call").validationId());
    }

    @Test
    void validationsBelongToTheirUseAndEntity() {
        Model m = model(vendorModel());
        ModelUse scoped = use(m, entity, ModelUse.Materiality.MEDIUM);
        refuses(MOD_WRONG_USE, () -> pass(scoped, otherEntity));
        ModelUse every = use(m, null, ModelUse.Materiality.MEDIUM);
        ValidationRecord v = pass(every, entity);
        gov.approve(v.validationId(), approver());
        refuses(MOD_WRONG_USE, () -> gov.goLive(every.useId(), otherEntity, v.validationId(), "x"));
        refuses(MOD_WRONG_USE, () -> gov.goLive(scoped.useId(), entity, v.validationId(), "x"));
        gov.goLive(every.useId(), entity, v.validationId(), "x");
        refuses(MOD_NOT_VALIDATED, () -> gate.requireLive(every.useId(), otherEntity, m.digest(),
                ConfigFingerprint.of(CONFIG)));
    }

    // ---- no content

    @Test
    void recordsAndRefusalsHoldNoContent() {
        Model m = model(vendorModel());
        ModelUse u = use(m, null, ModelUse.Materiality.MEDIUM);
        ValidationRecord v = pass(u, entity);
        gov.approve(v.validationId(), approver());
        gov.goLive(u.useId(), entity, v.validationId(), "release-manager");
        ModelGovException e = refuses(MOD_CONFIG_CHANGED, () -> gate.requireLive(u.useId(), entity, m.digest(),
                ConfigFingerprint.of(CONFIG_CHANGED)));
        String everything = String.join("\n", v.toString(), store.validation(v.validationId()).orElseThrow().toString(),
                current(u).toString(), e.getMessage(), e.details().toString(), String.join("\n", audit.lines()),
                start(u, entity).stamp().toString());
        assertFalse(everything.contains("example prompt"), "configuration text never stored or shown");
        assertTrue(everything.contains(ConfigFingerprint.of(CONFIG)));
    }
}
