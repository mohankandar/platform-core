package com.documentplatform.modelgov;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** The records of ongoing monitoring (design section 5): pass marks, readings, drift and what was done. Numbers only. */
public final class Monitoring {

    private Monitoring() {
    }

    /**
     * A monitoring pass mark for a measure of a use (and an entity, or null for the app's default): the direction
     * says which side is bad ({@code HIGHER_IS_BETTER}: below is bad), and readings over fewer samples than
     * {@code minSample} never count as drift.
     */
    public record MonitorThreshold(UUID useId, UUID entity, String measure, BigDecimal value,
                                   Threshold.Direction direction, long minSample) {
        public MonitorThreshold {
            Objects.requireNonNull(useId, "useId");
            Values.name("measure", measure);
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(direction, "direction");
            if (minSample < 1) throw Values.invalid("minSample", "at least 1");
        }

        public static MonitorThreshold byDefault(UUID useId, String measure, BigDecimal value,
                                                 Threshold.Direction direction, long minSample) {
            return new MonitorThreshold(useId, null, measure, value, direction, minSample);
        }

        public static MonitorThreshold forEntity(UUID useId, UUID entity, String measure, BigDecimal value,
                                                 Threshold.Direction direction, long minSample) {
            return new MonitorThreshold(useId, Objects.requireNonNull(entity, "entity"), measure, value, direction,
                    minSample);
        }

        /** Whether a reading over {@code samples} is past this pass mark. */
        public boolean drifted(BigDecimal reading, long samples) {
            return samples >= minSample && !direction.passes(reading, value);
        }
    }

    /** The period a reading covers and how many items it was measured over. */
    public record MeasurePeriod(Instant from, Instant to, long samples) {
        public MeasurePeriod {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            if (to.isBefore(from)) throw Values.invalid("period", "to is not before from");
            if (samples < 0) throw Values.invalid("period.samples", "zero or more");
        }
    }

    /** One reading (insert only). */
    public record Reading(UUID readingId, UUID useId, UUID entity, String measure, BigDecimal value,
                          MeasurePeriod period, String reportedBy, Instant reportedAt) {
        public Reading {
            Objects.requireNonNull(readingId, "readingId");
            Objects.requireNonNull(useId, "useId");
            Objects.requireNonNull(entity, "entity");
            Values.name("measure", measure);
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(period, "period");
            Values.text("reportedBy", reportedBy, 200, true);
            Objects.requireNonNull(reportedAt, "reportedAt");
        }
    }

    /** A reading past its pass mark (insert only), with the pass mark in force copied in. */
    public record Drift(UUID driftId, UUID useId, UUID entity, String measure, BigDecimal value, BigDecimal threshold,
                        Threshold.Direction direction, long samples, UUID readingId, Instant raisedAt) {
        public Drift {
            Objects.requireNonNull(driftId, "driftId");
            Objects.requireNonNull(useId, "useId");
            Objects.requireNonNull(entity, "entity");
            Values.name("measure", measure);
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(threshold, "threshold");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(readingId, "readingId");
            Objects.requireNonNull(raisedAt, "raisedAt");
        }
    }

    /** What was done about a drift (insert only): a note with no content, who, when. The use needs review until then. */
    public record DriftAction(UUID driftId, String note, String actedBy, Instant actedAt) {
        public DriftAction {
            Objects.requireNonNull(driftId, "driftId");
            Values.text("note", note, 2000, true);
            Values.text("actedBy", actedBy, 200, true);
            Objects.requireNonNull(actedAt, "actedAt");
        }
    }

    /** Which review notice was raised for a use's due date. */
    public enum ReviewStage { IN_30_DAYS, IN_7_DAYS, OVERDUE }
}
