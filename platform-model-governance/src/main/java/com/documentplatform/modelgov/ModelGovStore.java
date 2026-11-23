package com.documentplatform.modelgov;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Where the register and the gate's records are kept: Postgres ({@code jdbc.PostgresModelGovStore}) or in memory
 * (the testkit). Plain persistence: every rule is in {@link ModelGovernance} and {@link ModelGate}, so both stores
 * behave the same. Validations, approvals, acceptances and live switches are insert only.
 */
public interface ModelGovStore {

    /** Who changed what, for the history rows. */
    record Change(String changedBy, Instant changedAt, List<String> changedFields) {
        public Change {
            Objects.requireNonNull(changedBy, "changedBy");
            Objects.requireNonNull(changedAt, "changedAt");
            changedFields = List.copyOf(changedFields);
        }
    }

    /**
     * One version of a model or use in its history, with the values recorded then (models: name, provider,
     * modelVersion, digest, runsWhere; uses: modelId, configFingerprint, materiality, status, nextReviewDue,
     * reviewEveryDays).
     */
    record HistoryRow(long version, String changedBy, Instant changedAt, List<String> changedFields,
                      Map<String, String> recorded) {
        public HistoryRow {
            changedFields = List.copyOf(changedFields);
            recorded = Map.copyOf(recorded);
        }
    }

    /** The values a model's history row records. */
    static Map<String, String> recorded(Model m) {
        return Map.of("name", m.name(), "provider", m.provider().name(), "modelVersion", m.modelVersion(),
                "digest", m.digest().toString(), "runsWhere", m.runsWhere().name());
    }

    /** The values a use's history row records. */
    static Map<String, String> recorded(ModelUse u) {
        Map<String, String> rec = new java.util.LinkedHashMap<>();
        rec.put("modelId", u.modelId().toString());
        rec.put("configFingerprint", u.configFingerprint());
        rec.put("materiality", u.materiality().name());
        rec.put("status", u.status().name());
        if (u.nextReviewDue() != null) rec.put("nextReviewDue", u.nextReviewDue().toString());
        if (u.reviewEveryDays() != null) rec.put("reviewEveryDays", String.valueOf(u.reviewEveryDays()));
        return rec;
    }

    Model insertModel(Model model, Change change);

    /** The next version if the stored one is still {@code expectedVersion}; empty when it changed meanwhile. */
    Optional<Model> updateModel(Model model, long expectedVersion, Change change);

    Optional<Model> model(UUID modelId);

    List<HistoryRow> modelHistory(UUID modelId);

    ModelUse insertUse(ModelUse use, Change change);

    Optional<ModelUse> updateUse(ModelUse use, long expectedVersion, Change change);

    Optional<ModelUse> use(UUID useId);

    List<HistoryRow> useHistory(UUID useId);

    /** Sets (or replaces) the threshold for its use, entity (null = default) and measure. */
    void putThreshold(Threshold threshold, Change change);

    /** The use's thresholds: the defaults and every entity's. */
    List<Threshold> thresholds(UUID useId);

    void insertValidation(ValidationRecord record);

    Optional<ValidationRecord> validation(UUID validationId);

    /** The use's validations for the entity, oldest first. */
    List<ValidationRecord> validations(UUID useId, UUID entity);

    /** The entities the use has a validation for (passed or not), in UUID order. */
    List<UUID> validationEntities(UUID useId);

    void insertApproval(Decisions.Approval approval);

    Optional<Decisions.Approval> approval(UUID validationId);

    void insertAcceptance(Decisions.Acceptance acceptance);

    Optional<Decisions.Acceptance> acceptance(UUID validationId);

    Optional<Decisions.Live> live(UUID useId, UUID entity);

    /**
     * Switches what is live for the use and entity, with its history row, in one transaction, if the stored live
     * row is still at {@code expectedVersion} (0 when there is none yet). @return false when it changed meanwhile
     */
    boolean switchLive(Decisions.Live next, long expectedVersion, Decisions.LiveChange change);

    List<Decisions.LiveChange> liveHistory(UUID useId, UUID entity);

    // ---- the register as a list (P034)

    /** The uses of a product (every product when null), ordered by product, feature and ID. */
    List<ModelUse> uses(String product);

    /** The uses of a model. */
    List<ModelUse> usesOfModel(UUID modelId);

    // ---- monitoring (P034)

    /** Sets (or replaces) the monitoring pass mark for its use, entity (null = default) and measure. */
    void putMonitorThreshold(Monitoring.MonitorThreshold threshold, Change change);

    List<Monitoring.MonitorThreshold> monitorThresholds(UUID useId);

    void insertReading(Monitoring.Reading reading);

    /** The use's readings reported in [from, to), for one entity or every entity (null), oldest first. */
    List<Monitoring.Reading> readings(UUID useId, UUID entity, Instant from, Instant to);

    /** Removes the readings reported before {@code before} (the retention purge). @return how many */
    int purgeReadings(Instant before);

    void insertDrift(Monitoring.Drift drift);

    /** The use's drifts, for one entity or every entity (null), oldest first. */
    List<Monitoring.Drift> drifts(UUID useId, UUID entity);

    void insertDriftAction(Monitoring.DriftAction action);

    Optional<Monitoring.DriftAction> driftAction(UUID driftId);

    /** Records that a review notice was raised; false when it was already (one per use, due date and stage). */
    boolean insertReviewNotice(UUID useId, java.time.LocalDate due, Monitoring.ReviewStage stage, Instant at);
}
