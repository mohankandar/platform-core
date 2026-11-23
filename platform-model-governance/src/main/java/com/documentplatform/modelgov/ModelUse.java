package com.documentplatform.modelgov;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * One model use (the same model used for two purposes is two uses), as design section 3: product and feature, the
 * entity scope (null = every entity of the customer), purpose, materiality with its reason, the human in the loop,
 * owners at the vendor and at the customer, the model and the configuration fingerprint in force, status, the last
 * validation and the next review due, and whether an overdue review refuses the model (else it is a warning).
 */
public record ModelUse(UUID useId, String product, String feature, UUID entity, String purpose,
                       Materiality materiality, String materialityReason, String humanInTheLoop, String vendorOwner,
                       String customerOwner, UUID modelId, String configFingerprint, Status status,
                       Instant lastValidatedAt, LocalDate nextReviewDue, boolean reviewOverdueRefuses, long version) {

    /** How much the use matters (what happens if it is wrong, who relies on it, whether a person checks it). */
    public enum Materiality { HIGH, MEDIUM, LOW }

    public enum Status { PROPOSED, VALIDATED, LIVE, RETIRED }

    public ModelUse {
        Objects.requireNonNull(useId, "useId");
        Values.product(product);
        Values.text("use.feature", feature, 200, true);
        Values.text("use.purpose", purpose, 2000, true);
        Objects.requireNonNull(materiality, "materiality");
        Values.text("use.materialityReason", materialityReason, 2000, true);
        Values.text("use.humanInTheLoop", humanInTheLoop, 2000, true);
        Values.text("use.vendorOwner", vendorOwner, 200, false);
        Values.text("use.customerOwner", customerOwner, 200, false);
        Objects.requireNonNull(modelId, "modelId");
        if (!ConfigFingerprint.isFingerprint(configFingerprint)) {
            throw Values.invalid("use.configFingerprint", "sha256: followed by 64 lowercase hex (ConfigFingerprint.of)");
        }
        Objects.requireNonNull(status, "status");
        if (version < 1) throw new IllegalArgumentException("version starts at 1");
    }

    /** A new use, PROPOSED, with the app's default materiality. */
    public static ModelUse propose(String product, String feature, UUID entity, String purpose,
                                   Materiality materiality, String materialityReason, String humanInTheLoop,
                                   String vendorOwner, String customerOwner, UUID modelId, String configFingerprint) {
        return new ModelUse(UUID.randomUUID(), product, feature, entity, purpose, materiality, materialityReason,
                humanInTheLoop, vendorOwner, customerOwner, modelId, configFingerprint, Status.PROPOSED, null, null,
                false, 1);
    }

    public ModelUse withMateriality(Materiality m, String reason) {
        return new ModelUse(useId, product, feature, entity, purpose, m, reason, humanInTheLoop, vendorOwner,
                customerOwner, modelId, configFingerprint, status, lastValidatedAt, nextReviewDue,
                reviewOverdueRefuses, version);
    }

    /** The use with another model or configuration (it needs a new validation before it runs). */
    public ModelUse withModel(UUID newModelId, String newConfigFingerprint) {
        return new ModelUse(useId, product, feature, entity, purpose, materiality, materialityReason, humanInTheLoop,
                vendorOwner, customerOwner, newModelId, newConfigFingerprint, status, lastValidatedAt, nextReviewDue,
                reviewOverdueRefuses, version);
    }

    public ModelUse withReviewOverdueRefuses(boolean refuses) {
        return new ModelUse(useId, product, feature, entity, purpose, materiality, materialityReason, humanInTheLoop,
                vendorOwner, customerOwner, modelId, configFingerprint, status, lastValidatedAt, nextReviewDue,
                refuses, version);
    }

    public ModelUse withNextReviewDue(LocalDate due) {
        return new ModelUse(useId, product, feature, entity, purpose, materiality, materialityReason, humanInTheLoop,
                vendorOwner, customerOwner, modelId, configFingerprint, status, lastValidatedAt, due,
                reviewOverdueRefuses, version);
    }

    public ModelUse retired() {
        return withStatus(Status.RETIRED);
    }

    ModelUse withStatus(Status s) {
        return new ModelUse(useId, product, feature, entity, purpose, materiality, materialityReason, humanInTheLoop,
                vendorOwner, customerOwner, modelId, configFingerprint, s, lastValidatedAt, nextReviewDue,
                reviewOverdueRefuses, version);
    }

    ModelUse validated(Instant at, LocalDate nextDue) {
        return new ModelUse(useId, product, feature, entity, purpose, materiality, materialityReason, humanInTheLoop,
                vendorOwner, customerOwner, modelId, configFingerprint,
                status == Status.LIVE ? Status.LIVE : Status.VALIDATED, at, nextDue, reviewOverdueRefuses, version);
    }

    ModelUse atVersion(long v) {
        return new ModelUse(useId, product, feature, entity, purpose, materiality, materialityReason, humanInTheLoop,
                vendorOwner, customerOwner, modelId, configFingerprint, status, lastValidatedAt, nextReviewDue,
                reviewOverdueRefuses, v);
    }
}
