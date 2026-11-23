package com.documentplatform.modelgov;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One validation (insert only): the use and entity, the model (name, version, provider, exact digest), the
 * configuration fingerprint, the test set's name and version, each measure with its result and the threshold in
 * force, pass or fail, who ran it and when. Never document content or values: measures are numbers.
 */
public record ValidationRecord(UUID validationId, UUID useId, UUID entity, UUID modelId, String modelName,
                               String modelVersion, Model.Provider provider, ModelDigest digest,
                               String configFingerprint, String testSetName, String testSetVersion,
                               List<MeasureResult> measures, boolean passed, String ranBy, Instant ranAt) {

    /** One measure: its result, the threshold in force (copied in), the direction and whether it passed. */
    public record MeasureResult(String measure, BigDecimal result, BigDecimal threshold, Threshold.Direction direction,
                                boolean passed) {
        public MeasureResult {
            Values.name("measure", measure);
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(threshold, "threshold");
            Objects.requireNonNull(direction, "direction");
        }
    }

    public ValidationRecord {
        Objects.requireNonNull(validationId, "validationId");
        Objects.requireNonNull(useId, "useId");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(digest, "digest");
        Values.name("testSet.name", testSetName);
        Values.text("testSet.version", testSetVersion, 100, true);
        measures = List.copyOf(measures);
        Values.text("ranBy", ranBy, 200, true);
        Objects.requireNonNull(ranAt, "ranAt");
    }

    /** The measures that failed, as {@code measure result (threshold)} for messages; numbers only. */
    public String failedSummary() {
        return String.join(", ", measures.stream().filter(m -> !m.passed())
                .map(m -> m.measure() + " " + m.result().toPlainString() + " ("
                        + (m.direction() == Threshold.Direction.HIGHER_IS_BETTER ? ">= " : "<= ")
                        + m.threshold().toPlainString() + ")").toList());
    }
}
