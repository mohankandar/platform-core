package com.documentplatform.modelgov;

import java.util.Objects;
import java.util.UUID;

/**
 * A registered model: name, provider (the app's vendor model or the customer's own), version, its exact digest, and
 * where it runs. {@code version} is the register row's version (1 when created, raised by every change).
 */
public record Model(UUID modelId, String name, Provider provider, String modelVersion, ModelDigest digest,
                    RunsWhere runsWhere, long version) {

    /** Whose model it is. */
    public enum Provider { VENDOR, CUSTOMER }

    /** Where it runs. */
    public enum RunsWhere { CUSTOMER_ENVIRONMENT, HOSTED }

    public Model {
        Objects.requireNonNull(modelId, "modelId");
        Values.text("model.name", name, 200, true);
        Objects.requireNonNull(provider, "provider");
        Values.text("model.version", modelVersion, 100, true);
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(runsWhere, "runsWhere");
        if (version < 1) throw new IllegalArgumentException("version starts at 1");
    }

    public static Model create(String name, Provider provider, String modelVersion, ModelDigest digest,
                               RunsWhere runsWhere) {
        return new Model(UUID.randomUUID(), name, provider, modelVersion, digest, runsWhere, 1);
    }

    /** The same model at a new version and digest (a new pull or file). */
    public Model withVersion(String newModelVersion, ModelDigest newDigest) {
        return new Model(modelId, name, provider, newModelVersion, newDigest, runsWhere, version);
    }

    Model atVersion(long v) {
        return new Model(modelId, name, provider, modelVersion, digest, runsWhere, v);
    }
}
