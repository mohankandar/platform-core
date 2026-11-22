package com.documentplatform.identity.store;

import java.time.Instant;

/** One row of a client binding's history ({@code idn_machine_binding_history}): the binding as it was at a version. */
public record MachineBindingChange(long version, String clientId, String slotId, String sourceKey, String description,
                                   MachineBindingStatus status, ChangeSource changeSource, String changedBy,
                                   Instant changedAt) {
}
