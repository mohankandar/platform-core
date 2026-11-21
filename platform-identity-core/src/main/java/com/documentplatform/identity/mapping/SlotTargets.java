package com.documentplatform.identity.mapping;

import java.util.Optional;

/**
 * Supplied by the caller (the setup command, or the app's mapping screen): what is known about an entity slot that a
 * mapping file names by slot ID ({@code ENT-…}).
 *
 * <p>Unlike {@link MappingTargets}, a slot does not have to be activated or have an active login system yet: setup
 * loads the grants at install time, before the Business Admin activates and names the slots, and in the same run that
 * stores the login connection. Grants of a slot that is not ACTIVE never admit anybody.
 */
@FunctionalInterface
public interface SlotTargets {

    /**
     * @param name      the entity's name when the slot is activated and named, else null
     * @param inLicence the licence in force lists the slot
     */
    record Slot(String name, boolean inLicence) {
    }

    /** The slot, or empty when neither the licence nor the installation knows it. */
    Optional<Slot> describe(String slotId);
}
