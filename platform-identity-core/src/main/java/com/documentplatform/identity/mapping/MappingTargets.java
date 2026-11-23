package com.documentplatform.identity.mapping;

import com.documentplatform.identity.admission.EntityId;

import java.util.Optional;

/**
 * What is known about an entity that a mapping file names by UUID (the file form without slot IDs). Package-private:
 * nothing outside this package reads that form; the setup command reads slot IDs only
 * ({@link RoleMappingFile#forSlots}, {@link SlotTargets}).
 */
@FunctionalInterface
interface MappingTargets {

    /**
     * @param name                  the entity's name in the product, compared exactly with the file's {@code entity_name}
     * @param active                the entity is ACTIVE in the product
     * @param activeLoginConnection the entity has an ACTIVE login-system connection, so its grants can apply
     */
    record Target(String name, boolean active, boolean activeLoginConnection) {
    }

    /** The entity, or empty when the product has no entity with this id. */
    Optional<Target> describe(EntityId entity);
}
