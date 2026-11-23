package com.documentplatform.modelgov;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * One model use (the same model used for two purposes is two uses), as design section 3: product and feature, the
 * entity scope (null = every entity of the customer), purpose, materiality with its reason, the human in the loop,
 * owners at the vendor and at the customer, the model and the configuration fingerprint in force, status, the last
 * validation and the next review due, whether an overdue review refuses the model (else it is a warning), and the
 * use's own review interval in days (null = the materiality's default from {@link ModelGovSettings#reviewEvery}).
 */
public record ModelUse(UUID useId, String product, String feature, UUID entity, String purpose,
                       Materiality materiality, String materialityReason, String humanInTheLoop, String vendorOwner,
                       String customerOwner, UUID modelId, String configFingerprint, Status status,
                       Instant lastValidatedAt, LocalDate nextReviewDue, boolean reviewOverdueRefuses,
                       Integer reviewEveryDays, long version) {

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
        if (reviewEveryDays != null && (reviewEveryDays < 1 || reviewEveryDays > 3650)) {
            throw Values.invalid("use.reviewEveryDays", "1 to 3650 days");
        }
        if (version < 1) throw new IllegalArgumentException("version starts at 1");
    }

    /** A new use, PROPOSED, with the app's default materiality. */
    public static ModelUse propose(String product, String feature, UUID entity, String purpose,
                                   Materiality materiality, String materialityReason, String humanInTheLoop,
                                   String vendorOwner, String customerOwner, UUID modelId, String configFingerprint) {
        return new ModelUse(UUID.randomUUID(), product, feature, entity, purpose, materiality, materialityReason,
                humanInTheLoop, vendorOwner, customerOwner, modelId, configFingerprint, Status.PROPOSED, null, null,
                false, null, 1);
    }

    private ModelUse copy(Materiality m, String reason, UUID model, String config, Status s, Instant validatedAt,
                          LocalDate due, boolean refuses, Integer every, long v) {
        return new ModelUse(useId, product, feature, entity, purpose, m, reason, humanInTheLoop, vendorOwner,
                customerOwner, model, config, s, validatedAt, due, refuses, every, v);
    }

    public ModelUse withMateriality(Materiality m, String reason) {
        return copy(m, reason, modelId, configFingerprint, status, lastValidatedAt, nextReviewDue,
                reviewOverdueRefuses, reviewEveryDays, version);
    }

    /** The use with another model or configuration (it needs a new validation before it runs, and is due at once). */
    public ModelUse withModel(UUID newModelId, String newConfigFingerprint) {
        return copy(materiality, materialityReason, newModelId, newConfigFingerprint, status, lastValidatedAt,
                nextReviewDue, reviewOverdueRefuses, reviewEveryDays, version);
    }

    public ModelUse withReviewOverdueRefuses(boolean refuses) {
        return copy(materiality, materialityReason, modelId, configFingerprint, status, lastValidatedAt,
                nextReviewDue, refuses, reviewEveryDays, version);
    }

    public ModelUse withNextReviewDue(LocalDate due) {
        return copy(materiality, materialityReason, modelId, configFingerprint, status, lastValidatedAt, due,
                reviewOverdueRefuses, reviewEveryDays, version);
    }

    /** The use's own review interval (null = the materiality's default); takes effect at the next validation. */
    public ModelUse withReviewEveryDays(Integer days) {
        return copy(materiality, materialityReason, modelId, configFingerprint, status, lastValidatedAt,
                nextReviewDue, reviewOverdueRefuses, days, version);
    }

    public ModelUse retired() {
        return withStatus(Status.RETIRED);
    }

    ModelUse withStatus(Status s) {
        return copy(materiality, materialityReason, modelId, configFingerprint, s, lastValidatedAt, nextReviewDue,
                reviewOverdueRefuses, reviewEveryDays, version);
    }

    ModelUse validated(Instant at, LocalDate nextDue) {
        return copy(materiality, materialityReason, modelId, configFingerprint,
                status == Status.LIVE ? Status.LIVE : Status.VALIDATED, at, nextDue, reviewOverdueRefuses,
                reviewEveryDays, version);
    }

    /** The same use at another row version (stores). */
    public ModelUse atVersion(long v) {
        return copy(materiality, materialityReason, modelId, configFingerprint, status, lastValidatedAt,
                nextReviewDue, reviewOverdueRefuses, reviewEveryDays, v);
    }
}
