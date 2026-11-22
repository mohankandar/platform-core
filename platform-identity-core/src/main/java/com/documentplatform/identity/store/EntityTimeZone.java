package com.documentplatform.identity.store;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.identity.admission.EntityId;

import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * An entity slot's time zone ({@code idn_entity.time_zone}) and its customer's ({@code idn_customer.time_zone}), for
 * off-hours windows, reports, retention timers and notification times. Entities can be separate companies in
 * different places.
 *
 * @param own      the entity's own zone; null when it has none (it then follows the customer's)
 * @param customer the customer's zone; null when none is set
 */
public record EntityTimeZone(String slotId, UUID entityUuid, String tenantId, ZoneId own, ZoneId customer) {

    public EntityTimeZone {
        EntityUuid.requireSlotId(slotId);
        EntityUuid.requireTenantId(tenantId);
        Objects.requireNonNull(entityUuid, "entityUuid");
    }

    /** The zone that applies: the entity's own, else the customer's; empty when neither is set. */
    public Optional<ZoneId> effective() {
        return Optional.ofNullable(own != null ? own : customer);
    }

    /** The entity's own zone, if it has one (what notifications' {@code EntityZones} wants). */
    public Optional<ZoneId> ownZone() {
        return Optional.ofNullable(own);
    }

    public EntityId entity() {
        return EntityId.of(entityUuid);
    }
}
