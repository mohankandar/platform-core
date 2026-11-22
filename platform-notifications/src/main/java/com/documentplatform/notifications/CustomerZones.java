package com.documentplatform.notifications;

import java.time.ZoneId;
import java.util.Optional;

/**
 * Supplied by the app (or by {@code platform-notifications-spring} from identity): the customer's time zone as
 * identity holds it ({@code idn_customer.time_zone}). Identity is the one source of the customer's zone: when this
 * gives a zone, it is used in place of the tenant zone of the notification settings ({@link TenantSettings#zone()})
 * for every event of the tenant (with or without an entity) and every summary. An entity's own zone
 * ({@link EntityZones}) still wins over it.
 *
 * <p>Empty means identity has no zone for the customer: the tenant zone of the notification settings is used, as
 * before. If the call throws, the same, with a warning naming the tenant.
 *
 * <p>An app on platform-identity passes {@code EntityTimeZones.customerZone(tenantId)}.
 */
@FunctionalInterface
public interface CustomerZones {

    /** Identity holds no customer zone: the tenant zone of the notification settings everywhere (the default). */
    CustomerZones NONE = tenant -> Optional.empty();

    Optional<ZoneId> zoneOf(TenantId tenant);
}
