package com.documentplatform.identity.mapping;

import com.documentplatform.identity.admission.EntityId;

/**
 * One validated grant from a role mapping file: in this entity, members of this group get this role.
 *
 * @param entityName  the file's entity_name; null when the file leaves it empty (allowed for slot IDs only)
 * @param slotId      the slot ID when the file names the entity by slot ID ({@code ENT-…}), else null; the entity
 *                    is then the UUID derived from it
 * @param productCode the product the file was read for, or null when read without one
 * @param alias       the alias the file wrote instead of {@code role} (resolved when the file was read), or null
 */
public record MappingRow(int line, EntityId entity, String entityName, String group, String role, String slotId,
                         String productCode, String alias) {

    /** A row written with the role itself (no alias). */
    public MappingRow(int line, EntityId entity, String entityName, String group, String role, String slotId,
                      String productCode) {
        this(line, entity, entityName, group, role, slotId, productCode, null);
    }

    /** A row naming the entity by UUID, read without a product code. */
    public MappingRow(int line, EntityId entity, String entityName, String group, String role) {
        this(line, entity, entityName, group, role, null, null, null);
    }

    public Grant grant() {
        return new Grant(group, role);
    }
}
