package com.documentplatform.identity.store;

import java.util.Optional;
import java.util.Set;

/**
 * The entity slots of the licence in force, supplied by the app from platform-licensing (identity-jdbc has an adapter
 * for {@code LicenceService}).
 */
@FunctionalInterface
public interface LicensedSlots {

    record Current(String tenantId, Set<String> slotIds) {
        public Current {
            slotIds = Set.copyOf(slotIds);
        }
    }

    /** The licence in force, or empty when no valid licence is in force. */
    Optional<Current> current();
}
