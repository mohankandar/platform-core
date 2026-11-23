package com.documentplatform.identity.admission;

import java.util.Objects;
import java.util.Optional;

/**
 * Beside {@link MachineEntityBindings} (which keeps its signature): the one entity and the sending system (source)
 * a machine client is bound to on this connection. Built in over the client bindings:
 * {@code push.DirectoryMachineSourceBindings} (Postgres and in-memory directories).
 *
 * <p>Empty unless exactly one ACTIVE binding of an ACTIVE entity applies under the customer's ACTIVE connection that
 * matches the key exactly (provider ID, issuer, audience); empty for a blank client principal. A store that cannot be
 * read throws.
 */
@FunctionalInterface
public interface MachineSourceBindings {

    /** The bound entity and source. */
    record MachineSource(EntityId entity, String slotId, String sourceKey) {
        public MachineSource {
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(slotId, "slotId");
            Objects.requireNonNull(sourceKey, "sourceKey");
        }
    }

    Optional<MachineSource> find(ConnectionKey key, String clientPrincipal);
}
