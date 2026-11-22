package com.documentplatform.identity.store;

import com.documentplatform.identity.EntityUuid;

import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.documentplatform.identity.store.TimeZoneOutcome.Result.*;

/**
 * The app's API for time zones: an entity's effective zone (its own, else the customer's), and setting the entity's
 * or the customer's zone (the app's settings screen; the setup command gives defaults).
 *
 * <ul>
 *   <li>Zone names are IANA names as Java knows them ({@code America/New_York}, {@code Europe/London}, {@code UTC});
 *       anything else is refused with {@code TIME_ZONE_INVALID}, including offsets such as {@code +05:00} (they do
 *       not follow daylight saving).</li>
 *   <li>A blank or null name clears the entity's own zone: it follows the customer's again.</li>
 *   <li>The caller audits every call, refused or not, from the returned outcome.</li>
 * </ul>
 */
public final class EntityTimeZones {

    private final EntityStore store;

    public EntityTimeZones(EntityStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** Whether {@code name} is an IANA time zone name this Java knows (exact spelling). */
    public static boolean isZoneName(String name) {
        return name != null && ZoneId.getAvailableZoneIds().contains(name);
    }

    /** The zone named, if it is an IANA name. */
    public static Optional<ZoneId> parse(String name) {
        return isZoneName(name) ? Optional.of(ZoneId.of(name)) : Optional.empty();
    }

    /** The slot's zones; empty when the slot is not stored. */
    public Optional<EntityTimeZone> zone(String slotId) {
        return EntityUuid.isSlotId(slotId) ? store.timeZone(slotId) : Optional.empty();
    }

    /** The zones of the entity with this UUID; empty when no slot has it. */
    public Optional<EntityTimeZone> zone(UUID entityUuid) {
        return store.timeZone(Objects.requireNonNull(entityUuid, "entityUuid"));
    }

    /**
     * The customer's zone ({@code idn_customer.time_zone}); empty when none is set, the customer is not stored, or the
     * text is not a tenant ID. Identity is the one source of the customer's zone: notifications reads it from here.
     */
    public Optional<ZoneId> customerZone(String tenantId) {
        return EntityUuid.isTenantId(tenantId) ? store.customerTimeZone(tenantId) : Optional.empty();
    }

    /** The zone that applies to the entity: its own, else the customer's; empty when neither is set or unknown. */
    public Optional<ZoneId> effective(UUID entityUuid) {
        return zone(entityUuid).flatMap(EntityTimeZone::effective);
    }

    /**
     * Sets the entity's own zone, or clears it with a blank name (it then follows the customer's).
     *
     * @param actor the actor ID of who made the change, for the caller's audit entry (required)
     */
    public TimeZoneOutcome setEntityZone(String slotId, String zoneName, String actor) {
        requireActor(actor);
        if (!EntityUuid.isSlotId(slotId)) return new TimeZoneOutcome(NOT_AN_ID, null, null);
        boolean clear = zoneName == null || zoneName.isBlank();
        if (!clear && !isZoneName(zoneName)) return new TimeZoneOutcome(TIME_ZONE_INVALID, slotId, null);
        ZoneId zone = clear ? null : ZoneId.of(zoneName);
        Optional<EntityStore.Change> change = store.setEntityTimeZone(slotId, zone);
        if (change.isEmpty()) return new TimeZoneOutcome(SLOT_UNKNOWN, slotId, null);
        EntityTimeZone after = store.timeZone(slotId).orElseThrow();
        if (change.get() == EntityStore.Change.UNCHANGED) return new TimeZoneOutcome(UNCHANGED, slotId, after);
        return new TimeZoneOutcome(clear ? CLEARED : SET, slotId, after);
    }

    /** Sets the customer's zone, which every entity without its own follows. A customer's zone cannot be cleared. */
    public TimeZoneOutcome setCustomerZone(String tenantId, String zoneName, String actor) {
        requireActor(actor);
        if (!EntityUuid.isTenantId(tenantId)) return new TimeZoneOutcome(NOT_AN_ID, null, null);
        if (!isZoneName(zoneName)) return new TimeZoneOutcome(TIME_ZONE_INVALID, tenantId, null);
        Optional<EntityStore.Change> change = store.setCustomerTimeZone(tenantId, ZoneId.of(zoneName));
        if (change.isEmpty()) return new TimeZoneOutcome(CUSTOMER_UNKNOWN, tenantId, null);
        return new TimeZoneOutcome(change.get() == EntityStore.Change.UNCHANGED ? UNCHANGED : SET, tenantId, null);
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor is required");
        }
    }
}
