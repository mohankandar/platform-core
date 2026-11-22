package com.documentplatform.identity.store;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Customers ({@code idn_customer}) and their entity slots ({@code idn_entity}). Nothing is ever deleted: a slot that a
 * newer licence no longer lists becomes {@link EntityStatus#NOT_IN_LICENCE}.
 *
 * <p>Apps activate and rename slots through {@link SlotActivation}, which checks the licence and the name first; the
 * store methods here only make the change atomically.
 */
public interface EntityStore {

    enum Change { CREATED, UPDATED, UNCHANGED }

    /** Stores the customer, or updates its name (the name comes from the licence). */
    Change saveCustomer(String tenantId, String name);

    Optional<String> customerName(String tenantId);

    /** The customer's time zone, if one is set. */
    Optional<java.time.ZoneId> customerTimeZone(String tenantId);

    /**
     * Sets the customer's time zone. Only {@link EntityTimeZones} and the setup command call this, with a checked zone.
     *
     * @return UPDATED or UNCHANGED; empty when the customer is not stored
     */
    Optional<Change> setCustomerTimeZone(String tenantId, java.time.ZoneId zone);

    /** The slot's own time zone and its customer's; empty when the slot is not stored. */
    Optional<EntityTimeZone> timeZone(String slotId);

    /** The same, by entity UUID. */
    Optional<EntityTimeZone> timeZone(UUID entityUuid);

    /**
     * Sets the slot's own time zone, or clears it with null (the slot then follows the customer's). Only
     * {@link EntityTimeZones} and the setup command call this, with a checked zone.
     *
     * @return UPDATED or UNCHANGED; empty when the slot is not stored
     */
    Optional<Change> setEntityTimeZone(String slotId, java.time.ZoneId zone);

    /**
     * Brings the customer's slots in line with a licence: missing slots are added as NOT_ACTIVATED with their derived
     * entity UUID; stored slots the licence no longer lists become NOT_IN_LICENCE; a NOT_IN_LICENCE slot listed again
     * comes back with the status it had when it left (ACTIVE or SUSPENDED; a SUSPENDED slot never comes back ACTIVE),
     * or NOT_ACTIVATED if it had never been activated. Names and activation are never touched.
     *
     * @throws IllegalStateException when the customer is not stored, or a slot ID belongs to another customer
     */
    SlotSync syncSlots(String tenantId, Collection<String> licensedSlotIds);

    Optional<EntityRecord> entity(String slotId);

    Optional<EntityRecord> entity(UUID entityUuid);

    /** The customer's slots, ordered by slot ID. */
    List<EntityRecord> entities(String tenantId);

    /**
     * The customer's activated slots (ACTIVE or SUSPENDED): slot ID → entity UUID as text. Same shape as
     * platform-licensing's {@code ActivatedSlots}, so an app can pass {@code store::activated}.
     */
    Map<String, String> activated(String tenantId);

    /**
     * Activates a NOT_ACTIVATED slot with an already checked name, atomically. Only {@link SlotActivation} calls this.
     *
     * @return ACTIVATED, SLOT_UNKNOWN, NOT_IN_LICENCE, ALREADY_ACTIVATED or NAME_TAKEN
     */
    ActivationOutcome activate(String slotId, String name, String actor, Instant at);

    /**
     * Renames an activated slot with an already checked name. Only {@link SlotActivation} calls this.
     *
     * @return RENAMED, UNCHANGED, SLOT_UNKNOWN, NOT_ACTIVATED or NAME_TAKEN
     */
    ActivationOutcome rename(String slotId, String name);

    /**
     * Suspends an ACTIVE slot: it stays activated (and counted by licensing), but nobody may sign in to it. Only
     * {@link SlotActivation} calls this.
     *
     * @return SUSPENDED, ALREADY_SUSPENDED, SLOT_UNKNOWN, NOT_ACTIVATED or NOT_IN_LICENCE
     */
    ActivationOutcome suspend(String slotId);

    /**
     * Makes a SUSPENDED slot ACTIVE again. Only {@link SlotActivation} calls this (after checking the licence).
     *
     * @return RESUMED, NOT_SUSPENDED, SLOT_UNKNOWN, NOT_ACTIVATED or NOT_IN_LICENCE
     */
    ActivationOutcome resume(String slotId);
}
