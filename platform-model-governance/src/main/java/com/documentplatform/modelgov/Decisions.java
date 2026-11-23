package com.documentplatform.modelgov;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** The decisions kept with a validation (insert only): its approval, a documented acceptance, and live switches. */
public final class Decisions {

    private Decisions() {
    }

    /** The Model approver's approval, with the roles they held then. */
    public record Approval(UUID validationId, String approvedBy, Set<String> roles, Instant approvedAt) {
        public Approval {
            Objects.requireNonNull(validationId, "validationId");
            Objects.requireNonNull(approvedBy, "approvedBy");
            roles = Set.copyOf(roles);
            Objects.requireNonNull(approvedAt, "approvedAt");
        }
    }

    /**
     * The customer's documented acceptance of a failed customer-model validation for a medium-materiality use: who,
     * when, which results (the failed measures, numbers only) and why (free text, no content).
     */
    public record Acceptance(UUID validationId, String acceptedBy, Instant acceptedAt, String results, String reason) {
        public Acceptance {
            Objects.requireNonNull(validationId, "validationId");
            Objects.requireNonNull(acceptedBy, "acceptedBy");
            Objects.requireNonNull(acceptedAt, "acceptedAt");
            Objects.requireNonNull(results, "results");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** What is live for a use and entity, and the rollback target. */
    public record Live(UUID useId, UUID entity, UUID validationId, UUID previousValidationId, long version,
                       String changedBy, Instant changedAt) {
        public Live {
            Objects.requireNonNull(useId, "useId");
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(validationId, "validationId");
            Objects.requireNonNull(changedBy, "changedBy");
            Objects.requireNonNull(changedAt, "changedAt");
        }
    }

    /** One live switch (insert only): went live or rolled back. */
    public record LiveChange(UUID useId, UUID entity, Kind kind, UUID fromValidationId, UUID toValidationId,
                             String changedBy, Instant changedAt) {
        public enum Kind { WENT_LIVE, ROLLED_BACK }
    }
}
