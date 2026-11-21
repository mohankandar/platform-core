package com.documentplatform.identity.store;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.identity.admission.EntityId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One entity slot as stored ({@code idn_entity}).
 *
 * @param displayName the name the Business Admin gave it; null until activated
 * @param activatedBy the actor ID of who activated it; null until activated
 * @param activatedAt when it was activated; null until activated (kept when the slot later leaves the licence)
 */
public record EntityRecord(String slotId, UUID entityUuid, String tenantId, EntityStatus status, String displayName,
                           String activatedBy, Instant activatedAt) {

    public EntityRecord {
        EntityUuid.requireSlotId(slotId);
        EntityUuid.requireTenantId(tenantId);
        Objects.requireNonNull(status, "status");
        if (!EntityUuid.fromSlotId(slotId).equals(entityUuid)) {
            throw new IllegalArgumentException("entity UUID is not the one derived from the slot ID");
        }
    }

    public EntityId entity() {
        return EntityId.of(entityUuid);
    }
}
