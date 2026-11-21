package com.documentplatform.identity.store;

import java.util.Optional;
import java.util.Set;

/**
 * The entity slots of the licence in force, supplied by the app from platform-licensing (identity-jdbc has an adapter
 * for {@code LicenceService}).
 */
@FunctionalInterface
public interface LicensedSlots {

    /** Whether the licence in force allows activating and resuming entities now: the same rule as intake. */
    enum Standing {
        /** Valid, expiring, or expired within the grace period. */
        IN_FORCE,
        /** Before the licence's start date. */
        NOT_STARTED,
        /** Expired, and the grace period is over. */
        GRACE_OVER
    }

    record Current(String tenantId, Set<String> slotIds, Standing standing) {
        public Current {
            slotIds = Set.copyOf(slotIds);
            java.util.Objects.requireNonNull(standing, "standing");
        }

        /** A licence in force (valid, expiring or in grace). */
        public Current(String tenantId, Set<String> slotIds) {
            this(tenantId, slotIds, Standing.IN_FORCE);
        }
    }

    /** The licence in force whatever its standing, or empty when no valid licence is stored. */
    Optional<Current> current();
}
