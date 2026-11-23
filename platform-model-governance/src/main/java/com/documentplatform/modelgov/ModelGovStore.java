package com.documentplatform.modelgov;

import java.time.Instant;
import java.util.List;
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

    /** One version of a model or use in its history. */
    record HistoryRow(long version, String changedBy, Instant changedAt, List<String> changedFields) {
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
}
