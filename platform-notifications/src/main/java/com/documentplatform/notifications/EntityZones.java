package com.documentplatform.notifications;

import java.time.ZoneId;
import java.util.Optional;

/**
 * Supplied by the app: an entity's own time zone. Entities can be separate companies in different places, so an
 * event that belongs to an entity uses the entity's zone for its daily summary time, its summary period and the times
 * shown in its messages. Empty means the entity has no zone of its own: the tenant's zone
 * ({@link TenantSettings#zone()}) is used, as it always is for tenant-level events (no entity).
 *
 * <p>An app on platform-identity passes the entity's own zone from {@code idn_entity.time_zone}
 * ({@code EntityTimeZones.zone(entityUuid)}, {@code own()}). If the call throws, the tenant's zone is used and a
 * warning with the entity id is logged.
 */
@FunctionalInterface
public interface EntityZones {

    /** No entity has a zone of its own: the tenant's zone everywhere (the default). */
    EntityZones NONE = (tenant, entity) -> Optional.empty();

    Optional<ZoneId> zoneOf(TenantId tenant, EntityId entity);
}
