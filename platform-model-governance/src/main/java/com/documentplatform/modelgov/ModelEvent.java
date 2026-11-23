package com.documentplatform.modelgov;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * An event for the app's notifications to the model owner: {@code DRIFT} (a reading past its pass mark; the use
 * needs review until an action is recorded) or {@code REVIEW_DUE} (the next review is 30 or 7 days away, or past).
 * IDs, names, numbers and dates only: never content or values. Unused fields are null.
 */
public record ModelEvent(Kind kind, UUID useId, String product, String feature, UUID entity, String measure,
                         BigDecimal value, BigDecimal threshold, Long samples, UUID driftId, LocalDate dueDate,
                         Monitoring.ReviewStage stage, Instant at) {

    public enum Kind { DRIFT, REVIEW_DUE }

    public ModelEvent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(useId, "useId");
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(feature, "feature");
        Objects.requireNonNull(at, "at");
    }

    static ModelEvent drift(ModelUse use, Monitoring.Drift d) {
        return new ModelEvent(Kind.DRIFT, use.useId(), use.product(), use.feature(), d.entity(), d.measure(),
                d.value(), d.threshold(), d.samples(), d.driftId(), null, null, d.raisedAt());
    }

    static ModelEvent reviewDue(ModelUse use, LocalDate due, Monitoring.ReviewStage stage, Instant at) {
        return new ModelEvent(Kind.REVIEW_DUE, use.useId(), use.product(), use.feature(), use.entity(), null, null,
                null, null, null, due, stage, at);
    }

    /** The code the app's notice is raised from. */
    public ModelGovCode code() {
        return kind == Kind.DRIFT ? ModelGovCode.MOD_DRIFT : stage == Monitoring.ReviewStage.OVERDUE
                ? ModelGovCode.MOD_REVIEW_OVERDUE : ModelGovCode.MOD_REVIEW_DUE;
    }
}
