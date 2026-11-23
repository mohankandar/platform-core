package com.documentplatform.modelgov;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.documentplatform.modelgov.ModelGovCode.*;

/**
 * The model register and the change gate (design sections 3 and 4). Every rule is here, over a {@link ModelGovStore}
 * (Postgres or in memory): versioned register changes with history and the app's audit; thresholds (a customer's may
 * only be stricter than the app's default: {@code MOD_THRESHOLD_LOOSER}); validation records with the thresholds in
 * force copied in; approval by a Model approver who holds none of the forbidden roles; a customer model that fails
 * the test set blocked for high materiality and allowed for medium only with a documented acceptance; go live and
 * rollback per entity. The gate at model start is {@link ModelGate}.
 */
public final class ModelGovernance {

    private final ModelGovStore store;
    private final ModelGovAudit audit;
    private final ModelGovSettings settings;
    private final Clock clock;
    private final ReviewDates dates;

    public ModelGovernance(ModelGovStore store, ModelGovAudit audit, ModelGovSettings settings, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.dates = new ReviewDates(store, settings.reviewEvery());
    }

    public ModelGovStore store() {
        return store;
    }

    // ---- the register

    public Model registerModel(Model model, String by) {
        Values.text("changedBy", by, 200, true);
        Model stored = store.insertModel(model.atVersion(1), change(by, List.of("created")));
        audit.modelChanged(stored, by, List.of("created"));
        return stored;
    }

    public Model changeModel(Model model, long expectedVersion, String by) {
        Values.text("changedBy", by, 200, true);
        Model before = store.model(model.modelId()).orElseThrow(() -> unknown("model", model.modelId()));
        List<String> fields = new ArrayList<>();
        if (!before.name().equals(model.name())) fields.add("name");
        if (before.provider() != model.provider()) fields.add("provider");
        if (!before.modelVersion().equals(model.modelVersion())) fields.add("modelVersion");
        if (!before.digest().equals(model.digest())) fields.add("digest");
        if (before.runsWhere() != model.runsWhere()) fields.add("runsWhere");
        Model stored = store.updateModel(model, expectedVersion, change(by, fields))
                .orElseThrow(() -> new ModelGovException(MOD_VERSION_CONFLICT, Map.of("what", "model")));
        audit.modelChanged(stored, by, fields);
        if (fields.contains("modelVersion") || fields.contains("digest") || fields.contains("runsWhere")) {
            for (ModelUse u : store.usesOfModel(stored.modelId())) dueAtOnce(u, by);
        }
        return stored;
    }

    public ModelUse registerUse(ModelUse use, String by) {
        Values.text("changedBy", by, 200, true);
        store.model(use.modelId()).orElseThrow(() -> unknown("model", use.modelId()));
        ModelUse stored = store.insertUse(use.atVersion(1).withStatus(ModelUse.Status.PROPOSED),
                change(by, List.of("created")));
        audit.useChanged(stored, by, List.of("created"));
        return stored;
    }

    /** Changes a use. A new model or configuration fingerprint needs a new validation before it runs (the gate). */
    public ModelUse changeUse(ModelUse use, long expectedVersion, String by) {
        Values.text("changedBy", by, 200, true);
        ModelUse before = store.use(use.useId()).orElseThrow(() -> unknown("use", use.useId()));
        if (!before.modelId().equals(use.modelId())) store.model(use.modelId()).orElseThrow(() -> unknown("model",
                use.modelId()));
        ModelUse next = use;
        if (!before.modelId().equals(use.modelId()) || !before.configFingerprint().equals(use.configFingerprint())) {
            next = dueToday(use);
        }
        List<String> fields = changedFields(before, next);
        ModelUse stored = store.updateUse(next, expectedVersion, change(by, fields))
                .orElseThrow(() -> new ModelGovException(MOD_VERSION_CONFLICT, Map.of("what", "use")));
        audit.useChanged(stored, by, fields);
        return stored;
    }

    // ---- thresholds

    /** The app's default pass mark for a measure of the use. */
    public void setDefaultThreshold(Threshold t, String by) {
        if (t.entity() != null) throw Values.invalid("threshold.entity", "a default has no entity");
        store.use(t.useId()).orElseThrow(() -> unknown("use", t.useId()));
        store.putThreshold(t, change(by, List.of("threshold " + t.measure())));
        audit.thresholdSet(t, by);
    }

    /** The customer's pass mark for an entity: at least as strict as the default ({@code MOD_THRESHOLD_LOOSER}). */
    public void setEntityThreshold(Threshold t, String by) {
        if (t.entity() == null) throw Values.invalid("threshold.entity", "an entity threshold names its entity");
        store.use(t.useId()).orElseThrow(() -> unknown("use", t.useId()));
        Threshold def = store.thresholds(t.useId()).stream()
                .filter(x -> x.entity() == null && x.measure().equals(t.measure())).findFirst()
                .orElseThrow(() -> new ModelGovException(MOD_THRESHOLD_MISSING, Map.of("measure", t.measure())));
        if (def.direction() != t.direction() || !def.direction().atLeastAsStrict(t.value(), def.value())) {
            throw new ModelGovException(MOD_THRESHOLD_LOOSER, Map.of("measure", t.measure(),
                    "value", t.value().toPlainString(), "default", def.value().toPlainString()));
        }
        store.putThreshold(t, change(by, List.of("threshold " + t.measure())));
        audit.thresholdSet(t, by);
    }

    /** The thresholds in force for the use and entity: the entity's where set, else the default; by measure. */
    public Map<String, Threshold> thresholdsInForce(UUID useId, UUID entity) {
        Map<String, Threshold> out = new LinkedHashMap<>();
        List<Threshold> all = store.thresholds(useId);
        for (Threshold t : all) if (t.entity() == null) out.put(t.measure(), t);
        for (Threshold t : all) if (entity != null && entity.equals(t.entity())) out.put(t.measure(), t);
        return out;
    }

    // ---- validations

    /**
     * Records a validation of the use's current model and configuration for an entity: each pass mark in force needs
     * a result, each result a pass mark. Numbers only. The use's last validation and next review date move on.
     */
    public ValidationRecord recordValidation(UUID useId, UUID entity, String testSetName, String testSetVersion,
                                             Map<String, BigDecimal> results, String ranBy) {
        Objects.requireNonNull(entity, "entity");
        ModelUse use = store.use(useId).orElseThrow(() -> unknown("use", useId));
        if (use.entity() != null && !use.entity().equals(entity)) {
            throw new ModelGovException(MOD_WRONG_USE, Map.of("validation", "-", "use", useId, "entity", entity));
        }
        if (use.status() == ModelUse.Status.RETIRED) throw new ModelGovException(MOD_RETIRED, Map.of("use", useId));
        Model model = store.model(use.modelId()).orElseThrow(() -> unknown("model", use.modelId()));
        Map<String, Threshold> inForce = thresholdsInForce(useId, entity);
        List<ValidationRecord.MeasureResult> measures = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> r : new java.util.TreeMap<>(results).entrySet()) {
            Values.name("measure", r.getKey());
            Threshold t = inForce.get(r.getKey());
            if (t == null) throw new ModelGovException(MOD_THRESHOLD_MISSING, Map.of("measure", r.getKey()));
            measures.add(new ValidationRecord.MeasureResult(r.getKey(), r.getValue(), t.value(), t.direction(),
                    t.direction().passes(r.getValue(), t.value())));
        }
        for (String m : inForce.keySet()) {
            if (!results.containsKey(m)) throw Values.invalid("results", "a result for every pass mark (" + m + ")");
        }
        if (measures.isEmpty()) throw Values.invalid("results", "at least one measure");
        boolean passed = measures.stream().allMatch(ValidationRecord.MeasureResult::passed);
        Instant now = clock.instant();
        ValidationRecord rec = new ValidationRecord(UUID.randomUUID(), useId, entity, model.modelId(), model.name(),
                model.modelVersion(), model.provider(), model.digest(), use.configFingerprint(), testSetName,
                testSetVersion, measures, passed, ranBy, now);
        store.insertValidation(rec);
        audit.validationRecorded(rec);
        if (passed) {
            // The use's date is the earliest over its entities: this pass never moves another entity's (P036).
            LocalDate next = dates.earliest(use)
                    .orElse(LocalDate.ofInstant(now, ZoneOffset.UTC).plus(reviewEvery(use)));
            ModelUse updated = use.validated(now, next);
            store.updateUse(updated, use.version(), change(ranBy, List.of("lastValidatedAt", "nextReviewDue",
                    "status"))).ifPresent(u -> audit.useChanged(u, ranBy, List.of("lastValidatedAt", "nextReviewDue",
                    "status")));
        }
        return rec;
    }

    /**
     * The customer's documented acceptance of a customer-supplied model that failed the test set of a
     * medium-materiality use (who, when, which results, why). High materiality: {@code MOD_CUSTOMER_MODEL_BLOCKED};
     * anything else: {@code MOD_ACCEPTANCE_NOT_APPLICABLE}.
     */
    public Decisions.Acceptance accept(UUID validationId, Approver approver, String reason) {
        ValidationRecord v = store.validation(validationId).orElseThrow(() -> unknown("validation", validationId));
        checkApprover(approver);
        ModelUse use = store.use(v.useId()).orElseThrow(() -> unknown("use", v.useId()));
        if (v.passed()) throw notApplicable("the validation passed");
        if (v.provider() != Model.Provider.CUSTOMER) throw notApplicable("the model is the vendor's");
        if (use.materiality() == ModelUse.Materiality.HIGH) {
            throw new ModelGovException(MOD_CUSTOMER_MODEL_BLOCKED, Map.of("use", use.useId()));
        }
        if (use.materiality() != ModelUse.Materiality.MEDIUM) throw notApplicable("the use is not medium materiality");
        if (reason == null || reason.isBlank()) throw new ModelGovException(MOD_REASON_REQUIRED, Map.of());
        Values.text("reason", reason, 1000, true);
        if (store.acceptance(validationId).isPresent()) return store.acceptance(validationId).get();
        Decisions.Acceptance a = new Decisions.Acceptance(validationId, approver.id(), clock.instant(),
                v.failedSummary(), reason);
        store.insertAcceptance(a);
        audit.accepted(a);
        return a;
    }

    /**
     * Approval by a Model approver: {@code MOD_APPROVER_ROLE} without an approver role, {@code MOD_APPROVER_CONFLICT}
     * while also holding a forbidden role, {@code MOD_VALIDATION_FAILED} for a failed validation (unless it is a
     * medium-materiality customer model with a documented acceptance), {@code MOD_CUSTOMER_MODEL_BLOCKED} for a
     * failed customer model of a high-materiality use.
     */
    public Decisions.Approval approve(UUID validationId, Approver approver) {
        ValidationRecord v = store.validation(validationId).orElseThrow(() -> unknown("validation", validationId));
        checkApprover(approver);
        if (store.approval(validationId).isPresent()) {
            throw new ModelGovException(MOD_ALREADY_APPROVED, Map.of("validation", validationId));
        }
        if (!v.passed()) {
            ModelUse use = store.use(v.useId()).orElseThrow(() -> unknown("use", v.useId()));
            if (v.provider() == Model.Provider.CUSTOMER && use.materiality() == ModelUse.Materiality.HIGH) {
                throw new ModelGovException(MOD_CUSTOMER_MODEL_BLOCKED, Map.of("use", use.useId()));
            }
            boolean accepted = v.provider() == Model.Provider.CUSTOMER
                    && use.materiality() == ModelUse.Materiality.MEDIUM && store.acceptance(validationId).isPresent();
            if (!accepted) {
                throw new ModelGovException(MOD_VALIDATION_FAILED, Map.of("validation", validationId,
                        "failed", v.failedSummary()));
            }
        }
        Decisions.Approval a = new Decisions.Approval(validationId, approver.id(), approver.roles(), clock.instant());
        store.insertApproval(a);
        audit.approved(a);
        return a;
    }

    // ---- live and rollback

    /** Makes an approved validation live for the entity; what was live stays as the rollback target. */
    public Decisions.Live goLive(UUID useId, UUID entity, UUID validationId, String by) {
        Values.text("changedBy", by, 200, true);
        ModelUse use = store.use(useId).orElseThrow(() -> unknown("use", useId));
        if (use.status() == ModelUse.Status.RETIRED) throw new ModelGovException(MOD_RETIRED, Map.of("use", useId));
        ValidationRecord v = store.validation(validationId).orElseThrow(() -> unknown("validation", validationId));
        if (!v.useId().equals(useId) || !v.entity().equals(entity)) {
            throw new ModelGovException(MOD_WRONG_USE, Map.of("validation", validationId, "use", useId,
                    "entity", entity));
        }
        if (store.approval(validationId).isEmpty()) {
            throw new ModelGovException(MOD_NOT_APPROVED, Map.of("use", useId, "entity", entity));
        }
        Optional<Decisions.Live> now = store.live(useId, entity);
        if (now.isPresent() && now.get().validationId().equals(validationId)) return now.get();
        Instant at = clock.instant();
        UUID previous = now.map(Decisions.Live::validationId).orElse(null);
        long expected = now.map(Decisions.Live::version).orElse(0L);
        Decisions.Live next = new Decisions.Live(useId, entity, validationId, previous, expected + 1, by, at);
        Decisions.LiveChange change = new Decisions.LiveChange(useId, entity, Decisions.LiveChange.Kind.WENT_LIVE,
                previous, validationId, by, at);
        if (!store.switchLive(next, expected, change)) {
            throw new ModelGovException(MOD_VERSION_CONFLICT, Map.of("what", "live model"));
        }
        audit.liveSwitched(change);
        liveChanged(useId, true, by);
        return next;
    }

    /** Switches back at once to the previous live validation ({@code MOD_NO_ROLLBACK_TARGET} when there is none). */
    public Decisions.Live rollback(UUID useId, UUID entity, String by) {
        Values.text("changedBy", by, 200, true);
        Decisions.Live now = store.live(useId, entity).filter(l -> l.previousValidationId() != null)
                .orElseThrow(() -> new ModelGovException(MOD_NO_ROLLBACK_TARGET, Map.of("use", useId,
                        "entity", entity)));
        Instant at = clock.instant();
        Decisions.Live next = new Decisions.Live(useId, entity, now.previousValidationId(), now.validationId(),
                now.version() + 1, by, at);
        Decisions.LiveChange change = new Decisions.LiveChange(useId, entity, Decisions.LiveChange.Kind.ROLLED_BACK,
                now.validationId(), now.previousValidationId(), by, at);
        if (!store.switchLive(next, now.version(), change)) {
            throw new ModelGovException(MOD_VERSION_CONFLICT, Map.of("what", "live model"));
        }
        audit.liveSwitched(change);
        liveChanged(useId, false, by);
        return next;
    }

    // ---- helpers

    /**
     * After a live switch: the use is LIVE (when {@code nowLive}) and its next review date is the earliest over its
     * entities again, since the switched entity's reference validation changed.
     */
    private void liveChanged(UUID useId, boolean nowLive, String by) {
        ModelUse use = store.use(useId).orElseThrow(() -> unknown("use", useId));
        ModelUse next = nowLive && use.status() != ModelUse.Status.LIVE ? use.withStatus(ModelUse.Status.LIVE) : use;
        next = next.withNextReviewDue(dates.earliest(next).orElse(next.nextReviewDue()));
        List<String> fields = changedFields(use, next);
        if (fields.isEmpty()) return;
        store.updateUse(next, use.version(), change(by, fields)).ifPresent(u -> audit.useChanged(u, by, fields));
    }

    /** How long a validation of the use lasts: its own interval, else its materiality's. */
    public java.time.Period reviewEvery(ModelUse use) {
        return dates.every(use);
    }

    private ModelUse dueToday(ModelUse u) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        return u.nextReviewDue() != null && !u.nextReviewDue().isAfter(today) ? u : u.withNextReviewDue(today);
    }

    /** A change of the model's version, digest or where it runs makes each of its uses due for review at once. */
    private void dueAtOnce(ModelUse u, String by) {
        if (u.status() == ModelUse.Status.RETIRED) return;
        ModelUse due = dueToday(u);
        if (due == u) return;
        store.updateUse(due, u.version(), change(by, List.of("nextReviewDue")))
                .ifPresent(x -> audit.useChanged(x, by, List.of("nextReviewDue")));
    }

    private void checkApprover(Approver a) {
        Objects.requireNonNull(a, "approver");
        for (String r : a.roles()) {
            if (settings.forbiddenRoles().contains(r)) {
                throw new ModelGovException(MOD_APPROVER_CONFLICT, Map.of("approver", a.id(), "role", r));
            }
        }
        if (a.roles().stream().noneMatch(settings.approverRoles()::contains)) {
            throw new ModelGovException(MOD_APPROVER_ROLE, Map.of("approver", a.id()));
        }
    }

    private ModelGovStore.Change change(String by, List<String> fields) {
        return new ModelGovStore.Change(by, clock.instant(), fields);
    }

    private static ModelGovException unknown(String what, UUID id) {
        return new ModelGovException(MOD_UNKNOWN, Map.of("what", what, "id", String.valueOf(id)));
    }

    private static ModelGovException notApplicable(String reason) {
        return new ModelGovException(MOD_ACCEPTANCE_NOT_APPLICABLE, Map.of("reason", reason));
    }

    private static List<String> changedFields(ModelUse a, ModelUse b) {
        List<String> f = new ArrayList<>();
        if (!a.feature().equals(b.feature())) f.add("feature");
        if (!Objects.equals(a.entity(), b.entity())) f.add("entity");
        if (!a.purpose().equals(b.purpose())) f.add("purpose");
        if (a.materiality() != b.materiality()) f.add("materiality");
        if (!a.materialityReason().equals(b.materialityReason())) f.add("materialityReason");
        if (!a.humanInTheLoop().equals(b.humanInTheLoop())) f.add("humanInTheLoop");
        if (!Objects.equals(a.vendorOwner(), b.vendorOwner())) f.add("vendorOwner");
        if (!Objects.equals(a.customerOwner(), b.customerOwner())) f.add("customerOwner");
        if (!a.modelId().equals(b.modelId())) f.add("modelId");
        if (!a.configFingerprint().equals(b.configFingerprint())) f.add("configFingerprint");
        if (a.status() != b.status()) f.add("status");
        if (!Objects.equals(a.nextReviewDue(), b.nextReviewDue())) f.add("nextReviewDue");
        if (a.reviewOverdueRefuses() != b.reviewOverdueRefuses()) f.add("reviewOverdueRefuses");
        if (!Objects.equals(a.reviewEveryDays(), b.reviewEveryDays())) f.add("reviewEveryDays");
        return f;
    }
}
