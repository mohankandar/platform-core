package com.documentplatform.identity.store;

import com.documentplatform.identity.EntityUuid;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One integration client binding ({@code idn_machine_binding}): under the customer's login connection for a product,
 * the machine client with this client ID may deliver to this entity, as this sending system (source). Holds client IDs
 * only, never secrets. A binding is switched off (INACTIVE), never deleted.
 *
 * @param clientId    the verified token's client principal, exactly as the login system issues it (1–255 characters,
 *                    no spaces, commas, quotes or control characters; compared as given)
 * @param slotId      the entity slot (ENT-…)
 * @param entityUuid  derived from the slot ID ({@link EntityUuid#fromSlotId})
 * @param sourceKey   the product's sending-system key, {@code [a-z0-9][a-z0-9._-]{0,63}}
 * @param description optional text for people (at most 200 characters), never shown to callers; null when none
 * @param version     1 when created, raised by every change
 */
public record MachineBinding(UUID bindingId, String tenantId, String productCode, String clientId, String slotId,
                             UUID entityUuid, String sourceKey, String description, MachineBindingStatus status,
                             long version) {

    public static final Pattern CLIENT_ID = Pattern.compile("[^\\s,\"'\\p{Cntrl}]{1,255}");
    public static final Pattern SOURCE_KEY = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    public static final int DESCRIPTION_MAX = 200;

    public MachineBinding {
        Objects.requireNonNull(bindingId, "bindingId");
        EntityUuid.requireTenantId(tenantId);
        LoginConnection.requireProductCode(productCode);
        if (clientId == null || !CLIENT_ID.matcher(clientId).matches()) {
            throw new IllegalArgumentException("client ID must be 1 to 255 characters without spaces, commas, quotes "
                    + "or control characters");
        }
        EntityUuid.requireSlotId(slotId);
        if (!EntityUuid.fromSlotId(slotId).equals(entityUuid)) {
            throw new IllegalArgumentException("entity UUID must be the one derived from the slot ID");
        }
        if (sourceKey == null || !SOURCE_KEY.matcher(sourceKey).matches()) {
            throw new IllegalArgumentException("source must match [a-z0-9][a-z0-9._-]{0,63}");
        }
        if (description != null && (description.isEmpty() || description.length() > DESCRIPTION_MAX
                || description.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("description must be 1 to 200 characters without control characters");
        }
        Objects.requireNonNull(status, "status");
        if (version < 1) throw new IllegalArgumentException("version starts at 1");
    }

    /** A new, ACTIVE binding at version 1. */
    public static MachineBinding create(String tenantId, String productCode, String clientId, String slotId,
                                        String sourceKey, String description) {
        return new MachineBinding(UUID.randomUUID(), tenantId, productCode, clientId, slotId,
                EntityUuid.fromSlotId(slotId), sourceKey, description, MachineBindingStatus.ACTIVE, 1);
    }

    /** The same binding pointing at this entity and source, with this description, ACTIVE. */
    public MachineBinding activeFor(String newSlotId, String newSourceKey, String newDescription) {
        return new MachineBinding(bindingId, tenantId, productCode, clientId, newSlotId,
                EntityUuid.fromSlotId(newSlotId), newSourceKey, newDescription, MachineBindingStatus.ACTIVE, version);
    }

    public MachineBinding switchedOff() {
        return new MachineBinding(bindingId, tenantId, productCode, clientId, slotId, entityUuid, sourceKey,
                description, MachineBindingStatus.INACTIVE, version);
    }

    public MachineBinding withVersion(long v) {
        return new MachineBinding(bindingId, tenantId, productCode, clientId, slotId, entityUuid, sourceKey,
                description, status, v);
    }
}
