package com.documentplatform.identity.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Integration client bindings: which machine client may deliver to which entity as which source, per customer and
 * product. One binding per customer, product and client ID; at most one of them ACTIVE. Every change writes a history
 * row; nothing is deleted. Written by the setup command (client-binding file) and later by the IT Admin's integration
 * page: one store, no second copy.
 */
public interface MachineBindingStore {

    /** Every binding of the customer for the product (ACTIVE and INACTIVE), by client ID. */
    List<MachineBinding> bindings(String tenantId, String productCode);

    /** The ACTIVE binding of this client ID for the product, if any. */
    Optional<MachineBinding> activeBinding(String productCode, String clientId);

    /**
     * Stores a new binding at version 1.
     *
     * @throws IllegalStateException if the customer or the entity slot is not stored, or the client ID is already
     *                               bound for the customer and product
     */
    MachineBinding insertBinding(MachineBinding binding, ChangeSource source, String changedBy);

    /** Stores the next version if the stored one is still {@code expectedVersion}; empty if it changed meanwhile. */
    Optional<MachineBinding> updateBinding(MachineBinding binding, long expectedVersion, ChangeSource source,
                                           String changedBy);

    /** Every version of the binding, oldest first. */
    List<MachineBindingChange> bindingHistory(UUID bindingId);
}
