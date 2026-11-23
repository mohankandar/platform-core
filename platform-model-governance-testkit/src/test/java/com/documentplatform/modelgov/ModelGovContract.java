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
import java.util.Optional;
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
    /** The monitoring check's code, tested with the Spring wiring. */
    private static final Set<ModelGovCode> TESTED_ELSEWHERE = EnumSet.of(MOD_DRIFT_UNHANDLED);

    protected abstract ModelGovStore newStore();

    static final BigDecimal ACC = new BigDecimal("0.90");
    static final BigDecimal FALSE_RATE = new BigDecimal("0.05");

    TestClock clock;
    ModelGovStore store;
    RecordingAudit audit;
    ModelGovernance gov;
    ModelGate gate;
    ModelMeasures measures;
    ModelReviews reviews;
    List<ModelEvent> events;
    UUID entity;
    UUID otherEntity;

    @BeforeEach
    void setUp() {
        RAN.computeIfAbsent(getClass(), k -> new AtomicInteger()).incrementAndGet();
        clock = new TestClock();
        store = newStore();
        audit = new RecordingAudit();
        gov = new ModelGovernance(store, audit, settings(), clock);
        gate = new ModelGate(store, settings(), clock);
        events = new java.util.concurrent.CopyOnWriteArrayList<>();
        ModelEvents sink = e -> {
            events.add(e);
            SEEN.computeIfAbsent(getClass(), k -> ConcurrentHashMap.newKeySet()).add(e.code());
        };
        measures = new ModelMeasures(store, sink, clock, ModelMeasures.DEFAULT_RETENTION);
        reviews = new ModelReviews(store, sink, settings(), clock);
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
        missing.removeAll(TESTED_ELSEWHERE);
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

        clock.advance(Duration.ofDays(89));
        assertEquals(List.of(), start(u, entity).warnings(), "the day before the due date");
        clock.advance(Duration.ofDays(1)); // the due date itself
        assertEquals(List.of(MOD_REVIEW_OVERDUE), start(u, entity).warnings());

        gov.changeUse(current(u).withReviewOverdueRefuses(true), current(u).version(), "customer");
        refuses(MOD_REVIEW_OVERDUE, () -> start(u, entity));

        // A new validation moves the review date on.
        ValidationRecord v2 = pass(current(u), entity);
        gov.approve(v2.validationId(), approver());
        gov.goLive(u.useId(), entity, v2.validationId(), "release-manager");
        assertEquals(List.of(), start(u, entity).warnings());
    }

    /** P036, model-governance-39: the review date is kept per use and entity, from that entity's live validation. */
    @Test
    void oneEntitysPassNeverMovesAnotherEntitysReviewDate() {
        Model m = model(vendorModel());
        ModelUse u = use(m, null, ModelUse.Materiality.HIGH); // every entity
        java.time.LocalDate day0 = java.time.LocalDate.parse("2026-11-01");
        for (UUID e : List.of(entity, otherEntity)) {
            ValidationRecord v = pass(current(u), e);
            gov.approve(v.validationId(), approver());
            gov.goLive(u.useId(), e, v.validationId(), "release-manager");
        }
        assertEquals(day0.plusDays(90), current(u).nextReviewDue());

        // Day 60: the first entity is re-validated and goes live on it; the other is not.
        clock.advance(Duration.ofDays(60));
        ValidationRecord again = pass(current(u), entity);
        gov.approve(again.validationId(), approver());
        gov.goLive(u.useId(), entity, again.validationId(), "release-manager");
        assertEquals(day0.plusDays(90), current(u).nextReviewDue(), "the use's date is its earliest entity's");

        // Day 90: the other entity is past its own date; the first is not.
        clock.advance(Duration.ofDays(30));
        assertEquals(List.of(MOD_REVIEW_OVERDUE), start(u, otherEntity).warnings());
        assertEquals(List.of(), start(u, entity).warnings());
        List<ModelReviews.ReviewDue> overdue = reviews.overdue(clock.now);
        assertEquals(List.of(u.useId()), overdue.stream().map(r -> r.use().useId()).toList());
        assertEquals(day0.plusDays(90), overdue.get(0).dueDate());
        gov.changeUse(current(u).withReviewOverdueRefuses(true), current(u).version(), "customer");
        refuses(MOD_REVIEW_OVERDUE, () -> start(u, otherEntity));
        assertEquals(List.of(), start(u, entity).warnings(), "the re-validated entity still starts");

        // The other entity's re-validation counts once it is live: then the use is next due from day 60.
        ValidationRecord other = pass(current(u), otherEntity);
        gov.approve(other.validationId(), approver());
        assertEquals(1, reviews.overdue(clock.now).size(), "passed but not live: still the live validation's date");
        gov.goLive(u.useId(), otherEntity, other.validationId(), "release-manager");
        assertEquals(List.of(), start(u, otherEntity).warnings());
        assertEquals(List.of(), reviews.overdue(clock.now));
        assertEquals(day0.plusDays(150), current(u).nextReviewDue());
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

    // ---- monitoring (P034)

    static Monitoring.MeasurePeriod over(long samples) {
        return new Monitoring.MeasurePeriod(Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"),
                samples);
    }

    ModelUse monitored(Model m) {
        ModelUse u = use(m, null, ModelUse.Materiality.MEDIUM);
        measures.setDefaultThreshold(Monitoring.MonitorThreshold.byDefault(u.useId(), "correction_rate",
                new BigDecimal("0.10"), Threshold.Direction.LOWER_IS_BETTER, 50), "register-admin");
        measures.setDefaultThreshold(Monitoring.MonitorThreshold.byDefault(u.useId(), "confidence",
                new BigDecimal("0.80"), Threshold.Direction.HIGHER_IS_BETTER, 20), "register-admin");
        return u;
    }

    @Test
    void driftPastAThresholdInBothDirectionsWithAMinimumSample() {
        ModelUse u = monitored(model(vendorModel()));
        assertTrue(measures.report(u.useId(), entity, "correction_rate", new BigDecimal("0.20"), over(10)).drift()
                .isEmpty(), "too few samples");
        assertTrue(measures.report(u.useId(), entity, "correction_rate", new BigDecimal("0.10"), over(500)).drift()
                .isEmpty(), "at the pass mark is not past it");
        assertTrue(measures.report(u.useId(), entity, "confidence", new BigDecimal("0.80"), over(500)).drift()
                .isEmpty());
        assertFalse(measures.needsReview(u.useId()));
        assertEquals(List.of(), events);

        Monitoring.Drift high = measures.report(u.useId(), entity, "correction_rate", new BigDecimal("0.20"), over(50))
                .drift().orElseThrow();
        assertEquals(new BigDecimal("0.10"), high.threshold());
        assertEquals(Threshold.Direction.LOWER_IS_BETTER, high.direction());
        Monitoring.Drift low = measures.report(u.useId(), entity, "confidence", new BigDecimal("0.70"), over(20))
                .drift().orElseThrow();
        assertEquals(Threshold.Direction.HIGHER_IS_BETTER, low.direction());
        assertTrue(measures.report(u.useId(), entity, "correction_rate", new BigDecimal("0.30"), over(80)).drift()
                .isEmpty(), "no second drift while the first is open");

        assertEquals(List.of(ModelEvent.Kind.DRIFT, ModelEvent.Kind.DRIFT), events.stream().map(ModelEvent::kind).toList());
        ModelEvent e = events.get(0);
        assertEquals(MOD_DRIFT, e.code());
        assertEquals(u.useId(), e.useId());
        assertEquals(entity, e.entity());
        assertEquals("correction_rate", e.measure());
        assertEquals(50L, e.samples());
        assertEquals(high.driftId(), e.driftId());
        assertEquals(6, store.readings(u.useId(), null, Instant.EPOCH, Instant.parse("2100-01-01T00:00:00Z")).size());
        assertEquals(Set.of(high, low), Set.copyOf(store.drifts(u.useId(), entity)));
    }

    @Test
    void needsReviewUntilAnActionIsRecorded() {
        ModelUse u = monitored(model(vendorModel()));
        Monitoring.Drift a = measures.report(u.useId(), entity, "correction_rate", new BigDecimal("0.20"), over(60))
                .drift().orElseThrow();
        Monitoring.Drift b = measures.report(u.useId(), otherEntity, "confidence", new BigDecimal("0.50"), over(60))
                .drift().orElseThrow();
        assertTrue(measures.needsReview(u.useId()));
        assertEquals(2, measures.openDrifts().size());

        refuses(MOD_REASON_REQUIRED, () -> measures.recordAction(u.useId(), a.driftId(), " ", "model-owner"));
        refuses(MOD_UNKNOWN, () -> measures.recordAction(u.useId(), UUID.randomUUID(), "note", "model-owner"));
        refuses(MOD_VALUE_INVALID, () -> measures.recordAction(u.useId(), a.driftId(), "password=hunter2", "x"));
        clock.advance(Duration.ofHours(2));
        Monitoring.DriftAction act = measures.recordAction(u.useId(), a.driftId(),
                "Re-ran the test set: passed; the drift came from a batch of poor scans", "model-owner");
        assertEquals(clock.now, act.actedAt());
        assertEquals(Optional.of(act), store.driftAction(a.driftId()));
        refuses(MOD_ACTION_ALREADY_RECORDED, () -> measures.recordAction(u.useId(), a.driftId(), "again", "x"));
        assertTrue(measures.needsReview(u.useId()), "the other entity's drift is still open");
        measures.recordAction(u.useId(), b.driftId(), "Rolled back to the earlier version", "model-owner");
        assertFalse(measures.needsReview(u.useId()));
        assertEquals(List.of(), measures.openDrifts());

        assertTrue(measures.report(u.useId(), entity, "correction_rate", new BigDecimal("0.25"), over(60)).drift()
                .isPresent(), "after the action, a reading past the mark raises a new drift");
        assertTrue(measures.needsReview(u.useId()));
    }

    @Test
    void monitoringPassMarksOnlyStricterAndRequired() {
        Model m = model(vendorModel());
        ModelUse u = monitored(m);
        refuses(MOD_THRESHOLD_LOOSER, () -> measures.setEntityThreshold(Monitoring.MonitorThreshold.forEntity(
                u.useId(), entity, "correction_rate", new BigDecimal("0.20"), Threshold.Direction.LOWER_IS_BETTER, 50),
                "customer"));
        refuses(MOD_THRESHOLD_LOOSER, () -> measures.setEntityThreshold(Monitoring.MonitorThreshold.forEntity(
                u.useId(), entity, "correction_rate", new BigDecimal("0.05"), Threshold.Direction.LOWER_IS_BETTER, 500),
                "customer"));
        refuses(MOD_THRESHOLD_MISSING, () -> measures.setEntityThreshold(Monitoring.MonitorThreshold.forEntity(
                u.useId(), entity, "refusal_rate", new BigDecimal("0.05"), Threshold.Direction.LOWER_IS_BETTER, 5),
                "customer"));
        measures.setEntityThreshold(Monitoring.MonitorThreshold.forEntity(u.useId(), entity, "correction_rate",
                new BigDecimal("0.05"), Threshold.Direction.LOWER_IS_BETTER, 20), "customer");
        assertTrue(measures.report(u.useId(), entity, "correction_rate", new BigDecimal("0.07"), over(20)).drift()
                .isPresent(), "the customer's stricter mark applies to its entity");
        assertTrue(measures.report(u.useId(), otherEntity, "correction_rate", new BigDecimal("0.07"), over(20))
                .drift().isEmpty(), "the default applies elsewhere");
        refuses(MOD_THRESHOLD_MISSING, () -> measures.report(u.useId(), entity, "refusal_rate", BigDecimal.ONE, over(9)));

        ModelUse scoped = use(m, entity, ModelUse.Materiality.LOW);
        measures.setDefaultThreshold(Monitoring.MonitorThreshold.byDefault(scoped.useId(), "confidence",
                new BigDecimal("0.8"), Threshold.Direction.HIGHER_IS_BETTER, 1), "register-admin");
        refuses(MOD_WRONG_USE, () -> measures.report(scoped.useId(), otherEntity, "confidence", BigDecimal.ONE, over(5)));
        gov.changeUse(current(scoped).retired(), current(scoped).version(), "register-admin");
        refuses(MOD_RETIRED, () -> measures.report(scoped.useId(), entity, "confidence", BigDecimal.ONE, over(5)));
    }

    @Test
    void readingsArePurgedAfterTheRetentionDriftsStay() {
        // In the database's past: its purge never removes readings younger than 30 days of its own time (V3).
        clock.now = Instant.parse("2025-01-01T09:00:00Z");
        ModelUse u = monitored(model(vendorModel()));
        measures.report(u.useId(), entity, "correction_rate", new BigDecimal("0.50"), over(60));
        clock.advance(Duration.ofDays(401));
        measures.report(u.useId(), entity, "confidence", new BigDecimal("0.95"), over(60));
        assertEquals(1, measures.purge());
        assertEquals(0, measures.purge());
        List<Monitoring.Reading> left = store.readings(u.useId(), entity, Instant.EPOCH,
                Instant.parse("2100-01-01T00:00:00Z"));
        assertEquals(List.of("confidence"), left.stream().map(Monitoring.Reading::measure).toList());
        assertEquals(1, store.drifts(u.useId(), entity).size());
        assertThrows(IllegalArgumentException.class, () -> new ModelMeasures(store, ModelEvents.NONE, clock,
                Duration.ofDays(29)));
    }

    // ---- re-validation (P034)

    @Test
    void dueDatesPerMaterialityAndNoticesOncePerStage() {
        Model m = model(vendorModel());
        ModelUse high = use(m, null, ModelUse.Materiality.HIGH);
        ModelUse medium = use(m, null, ModelUse.Materiality.MEDIUM);
        ModelUse low = use(m, null, ModelUse.Materiality.LOW);
        ModelUse own = gov.changeUse(current(use(m, null, ModelUse.Materiality.HIGH)).withReviewEveryDays(30), 1,
                "register-admin");
        for (ModelUse u : List.of(high, medium, low, own)) pass(u, entity);
        java.time.LocalDate today = java.time.LocalDate.parse("2026-11-01");
        assertEquals(today.plusDays(90), current(high).nextReviewDue());
        assertEquals(today.plusDays(180), current(medium).nextReviewDue());
        assertEquals(today.plusDays(365), current(low).nextReviewDue());
        assertEquals(today.plusDays(30), current(own).nextReviewDue());
        assertEquals(List.of(), reviews.due(clock.now).stream().filter(r -> !r.use().useId().equals(own.useId())).toList());

        clock.advance(Duration.ofDays(60));
        List<ModelReviews.ReviewDue> due = reviews.due();
        assertEquals(List.of(own.useId(), high.useId()), due.stream().map(r -> r.use().useId()).toList());
        assertEquals(Monitoring.ReviewStage.OVERDUE, due.get(0).stage());
        assertEquals(Monitoring.ReviewStage.IN_30_DAYS, due.get(1).stage());
        assertEquals(30, due.get(1).daysLeft());
        assertTrue(due.stream().allMatch(ModelReviews.ReviewDue::raisedNow));
        int raised = events.size();
        assertTrue(reviews.due().stream().noneMatch(ModelReviews.ReviewDue::raisedNow), "each stage once");
        assertEquals(raised, events.size());

        clock.advance(Duration.ofDays(23));
        assertEquals(Monitoring.ReviewStage.IN_7_DAYS, reviews.due().stream()
                .filter(r -> r.use().useId().equals(high.useId())).findFirst().orElseThrow().stage());
        clock.advance(Duration.ofDays(7));
        ModelReviews.ReviewDue overdue = reviews.due().stream().filter(r -> r.use().useId().equals(high.useId()))
                .findFirst().orElseThrow();
        assertEquals(Monitoring.ReviewStage.OVERDUE, overdue.stage());
        assertEquals(0, overdue.daysLeft());
        assertEquals(2, reviews.overdue(clock.now).size());

        ModelEvent last = events.get(events.size() - 1);
        assertEquals(ModelEvent.Kind.REVIEW_DUE, last.kind());
        assertEquals(MOD_REVIEW_OVERDUE, last.code());
        assertEquals(today.plusDays(90), last.dueDate());
        assertTrue(events.stream().anyMatch(e -> e.code() == MOD_REVIEW_DUE));
        assertEquals(5, events.size(), "own: in 30 days, overdue; high: in 30 days, in 7, overdue");

        pass(current(high), entity);
        assertTrue(reviews.overdue(clock.now).stream().noneMatch(r -> r.use().useId().equals(high.useId())));
        gov.changeUse(current(low).retired(), current(low).version(), "register-admin");
        clock.advance(Duration.ofDays(400));
        assertTrue(reviews.due().stream().noneMatch(r -> r.use().useId().equals(low.useId())), "retired uses are not due");
    }

    @Test
    void anyChangeMakesTheUseDueAtOnce() {
        Model m = model(vendorModel());
        Model other = model(customerModel());
        ModelUse u = use(m, null, ModelUse.Materiality.LOW);
        ModelUse unrelated = use(other, null, ModelUse.Materiality.LOW);
        pass(u, entity);
        pass(unrelated, entity);
        java.time.LocalDate today = java.time.LocalDate.parse("2026-11-01");
        clock.advance(Duration.ofDays(10));
        java.time.LocalDate now = today.plusDays(10);

        Model renamed = gov.changeModel(new Model(m.modelId(), "renamed-detector", m.provider(), m.modelVersion(),
                m.digest(), m.runsWhere(), 1), 1, "register-admin");
        assertEquals(today.plusDays(365), current(u).nextReviewDue(), "a new name alone changes nothing");

        Model moved = gov.changeModel(new Model(m.modelId(), renamed.name(), m.provider(), m.modelVersion(), m.digest(),
                Model.RunsWhere.HOSTED, 1), renamed.version(), "register-admin");
        assertEquals(now, current(u).nextReviewDue(), "where it runs");
        assertEquals(today.plusDays(365), current(unrelated).nextReviewDue());
        assertEquals(List.of(u.useId()), reviews.overdue(clock.now).stream().map(r -> r.use().useId()).toList());
        assertTrue(store.useHistory(u.useId()).get(store.useHistory(u.useId()).size() - 1).changedFields()
                .contains("nextReviewDue"));

        pass(current(u), entity);
        gov.changeModel(moved.withVersion("1.1", moved.digest()), moved.version(), "register-admin");
        assertEquals(now, current(u).nextReviewDue(), "version");
        pass(current(u), entity);
        Model v11 = store.model(m.modelId()).orElseThrow();
        gov.changeModel(v11.withVersion("1.1", digest("vendor-1.1-rebuilt")), v11.version(), "register-admin");
        assertEquals(now, current(u).nextReviewDue(), "digest");
        pass(current(u), entity);
        gov.changeUse(current(u).withModel(m.modelId(), ConfigFingerprint.of(CONFIG_CHANGED)), current(u).version(),
                "register-admin");
        assertEquals(now, current(u).nextReviewDue(), "configuration");
        pass(current(u), entity);
        gov.changeUse(current(u).withModel(other.modelId(), current(u).configFingerprint()), current(u).version(),
                "register-admin");
        assertEquals(now, current(u).nextReviewDue(), "another model");
        assertEquals(today.plusDays(365), current(unrelated).nextReviewDue());
    }

    // ---- the documentation pack (P034)

    static final String PLANTED = "Planted-Value-7Q4Z";

    ModelPack.Pack fullPack(Instant to) {
        Model vendor = model(vendorModel());
        Model customer = model(customerModel());
        ModelUse u = monitored(vendor);
        ModelUse cu = use(customer, entity, ModelUse.Materiality.MEDIUM);
        gov.changeUse(current(u).withModel(vendor.modelId(), ConfigFingerprint.of(
                "{\"prompt\":\"find items like " + PLANTED + "\"}")), current(u).version(), "register-admin");
        ValidationRecord v1 = pass(current(u), entity);
        gov.approve(v1.validationId(), approver());
        gov.goLive(u.useId(), entity, v1.validationId(), "release-manager");
        clock.advance(Duration.ofDays(1));
        Model v2model = gov.changeModel(vendor.withVersion("1.1", digest("vendor-1.1")), vendor.version(), "register-admin");
        ValidationRecord v2 = pass(current(u), entity);
        gov.approve(v2.validationId(), approver());
        gov.goLive(u.useId(), entity, v2.validationId(), "release-manager");
        gov.rollback(u.useId(), entity, "on-call");
        ValidationRecord failed = fail(cu, entity);
        gov.accept(failed.validationId(), complianceOfficer(), "Known weakness on small items; a reviewer checks each");
        gov.approve(failed.validationId(), approver());
        Monitoring.Drift d = measures.report(u.useId(), entity, "correction_rate", new BigDecimal("0.20"), over(60))
                .drift().orElseThrow();
        measures.recordAction(u.useId(), d.driftId(), "Re-validated; passed", "model-owner");
        measures.report(u.useId(), entity, "confidence", new BigDecimal("0.50"), over(60));
        assertNotNull(v2model);
        ModelPack pack = new ModelPack(store, measures, packTexts());
        return pack.export(PRODUCT, entity, Instant.parse("2026-10-01T00:00:00Z"), to);
    }

    @Test
    void thePackIsDeterministicCompleteAndFreeOfContent() throws Exception {
        Instant to = Instant.parse("2026-12-01T00:00:00Z");
        ModelPack.Pack p = fullPack(to);
        ModelPack again = new ModelPack(store, measures, packTexts());
        ModelPack.Pack q = again.export(PRODUCT, entity, Instant.parse("2026-10-01T00:00:00Z"), to);
        assertEquals(p.json(), q.json(), "same data, same JSON");
        assertEquals(p.fingerprint(), q.fingerprint());
        assertTrue(ConfigFingerprint.isFingerprint(p.fingerprint()));
        assertEquals(p.fingerprint(), ConfigFingerprint.of(p.json()), "the JSON is already in canonical form");

        @SuppressWarnings("unchecked")
        Map<String, Object> json = (Map<String, Object>) com.documentplatform.integration.json.Json.parse(p.json());
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) com.documentplatform.integration.json.Json.parse(
                new String(ModelPack.class.getClassLoader().getResourceAsStream(
                        "schema/platform-model-pack-1.schema.json").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        var problems = new com.documentplatform.integration.json.JsonSchema(schema).check(json, schema);
        assertEquals(List.of(), problems, "fits its schema");

        assertEquals(ModelPack.FORMAT, json.get("format"));
        assertEquals(2, list(json, "register").size(), "1: both uses in scope");
        assertTrue(list(json, "register").stream().anyMatch(e -> Boolean.TRUE.equals(((Map<?, ?>) e).get("needsReview"))));
        assertTrue(((Map<?, ?>) list(json, "uses").get(0)).get("knownLimits").toString().contains("handwriting"), "2");
        List<Object> vs = list(json, "validations");
        assertEquals(3, vs.size(), "3: every validation of the period");
        assertTrue(vs.stream().anyMatch(v -> ((Map<?, ?>) v).get("acceptance") != null), "3: the acceptance");
        assertTrue(vs.stream().allMatch(v -> ((Map<?, ?>) v).get("approval") != null), "3: approvals");
        Map<?, ?> mon = (Map<?, ?>) json.get("monitoring");
        assertEquals(2, ((List<?>) mon.get("thresholds")).size(), "4: pass marks");
        assertEquals(2, ((List<?>) mon.get("readings")).size(), "4: readings");
        List<?> drifts = (List<?>) mon.get("drifts");
        assertEquals(2, drifts.size(), "4: drifts");
        assertTrue(drifts.stream().anyMatch(x -> ((Map<?, ?>) x).get("action") != null), "4: the action");
        Map<?, ?> ch = (Map<?, ?>) json.get("changes");
        assertTrue(((List<?>) ch.get("models")).stream().anyMatch(h -> ((Map<?, ?>) ((Map<?, ?>) h).get("recorded"))
                .get("digest").equals(digest("vendor-1.1").toString())), "5: digests");
        assertTrue(((List<?>) ch.get("live")).stream().anyMatch(l -> "ROLLED_BACK".equals(((Map<?, ?>) l).get("kind"))),
                "5: the rollback");
        assertFalse(((List<?>) ch.get("uses")).isEmpty(), "5: use changes");
        Map<?, ?> tp = (Map<?, ?>) json.get("thirdParty");
        assertEquals(1, ((List<?>) tp.get("vendorModels")).size(), "6");
        assertEquals(1, ((List<?>) tp.get("customerModels")).size(), "6");
        assertEquals(1, ((List<?>) tp.get("independentReviews")).size(), "6: the independent review");
        assertTrue(json.get("dataStatement").toString().contains("trained"), "7");

        String pdf;
        try (var doc = org.apache.pdfbox.Loader.loadPDF(p.pdf())) {
            pdf = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
        }
        assertTrue(pdf.contains(p.fingerprint()), "the PDF prints the fingerprint");
        for (String s : ModelPack.SECTIONS) assertTrue(pdf.contains(s), s);
        assertTrue(pdf.contains("rolled back"));
        for (String text : List.of(p.json(), pdf, p.toString())) {
            assertFalse(text.contains(PLANTED), "a planted value never appears");
            assertFalse(text.contains("example prompt"));
        }
    }

    @Test
    void thePackCoversItsPeriodOnly() {
        ModelPack.Pack p = fullPack(Instant.parse("2026-11-01T12:00:00Z"));
        assertTrue(p.json().contains("\"validations\":[{"), "the first day's validation");
        int count = p.json().split("\"validationId\"", -1).length - 1;
        assertTrue(count >= 1);
        assertFalse(p.json().contains("ROLLED_BACK"), "the rollback is after the period");
        refuses(MOD_VALUE_INVALID, () -> new ModelPack(store, measures, packTexts()).export(PRODUCT, entity,
                Instant.parse("2026-12-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z")));
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Map<String, Object> m, String key) {
        return (List<Object>) m.get(key);
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
