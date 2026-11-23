package com.documentplatform.modelgov;

import java.util.Objects;
import java.util.UUID;

/**
 * What the app stores with each AI result, so any past output traces to a validated model: the use, the model's
 * name, version and exact digest, the validation and the configuration fingerprint. Plain data, safe to log.
 */
public record ModelStamp(UUID useId, String modelName, String modelVersion, ModelDigest digest, UUID validationId,
                         String configFingerprint) {
    public ModelStamp {
        Objects.requireNonNull(useId, "useId");
        Objects.requireNonNull(modelName, "modelName");
        Objects.requireNonNull(modelVersion, "modelVersion");
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(validationId, "validationId");
        Objects.requireNonNull(configFingerprint, "configFingerprint");
    }

    @Override
    public String toString() {
        return "ModelStamp[use=" + useId + " model=" + modelName + " " + modelVersion + " digest=" + digest
                + " validation=" + validationId + " config=" + configFingerprint + "]";
    }
}
