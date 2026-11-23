package com.documentplatform.modelgov;

import java.util.List;
import java.util.Objects;

/**
 * The gate's answer at model start: the use and the live validation the running model matches, with any warnings
 * (for example {@code MOD_REVIEW_OVERDUE} when that is not a refusal for the use). {@link #stamp()} gives the stamp to
 * store with each result.
 */
public record LiveModel(ModelUse use, ValidationRecord validation, List<ModelGovCode> warnings) {

    public LiveModel {
        Objects.requireNonNull(use, "use");
        Objects.requireNonNull(validation, "validation");
        warnings = List.copyOf(warnings);
    }

    public ModelStamp stamp() {
        return new ModelStamp(use.useId(), validation.modelName(), validation.modelVersion(), validation.digest(),
                validation.validationId(), validation.configFingerprint());
    }
}
