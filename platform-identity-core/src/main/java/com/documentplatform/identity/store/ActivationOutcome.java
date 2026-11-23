package com.documentplatform.identity.store;

import java.util.Objects;

/**
 * The result of activating, renaming, suspending or resuming a slot. The caller audits it (who, which slot, the result code). The message is
 * neutral: it names the slot ID, never the name the person typed.
 *
 * @param entity the slot after the call; null when the slot is unknown or the request was refused before the lookup
 */
public record ActivationOutcome(Result result, String slotId, EntityRecord entity) {

    public enum Result implements com.documentplatform.errors.PlatformCode {
        /** The slot is now ACTIVE with the name. The app creates its own entity row with the entity UUID. */
        ACTIVATED(false),
        RENAMED(false),
        /** The name was already this one. */
        UNCHANGED(false),
        /** The slot is now SUSPENDED: still activated, but nobody may sign in to it. */
        SUSPENDED(false),
        /** The slot is ACTIVE again. */
        RESUMED(false),
        /** Suspend of a slot that is already SUSPENDED; nothing changed. */
        ALREADY_SUSPENDED(false),
        /** Resume of a slot that is already ACTIVE; nothing changed. */
        NOT_SUSPENDED(false),
        NOT_A_SLOT_ID(true),
        NAME_INVALID(true),
        /** No valid licence is in force, so nothing can be activated. */
        NO_LICENCE(true),
        /** The licence in force expired and its grace period is over (the same rule as intake). */
        LICENCE_EXPIRED(true),
        /** The licence in force has not started yet (the same rule as intake). */
        LICENCE_NOT_STARTED(true),
        /** The licence in force does not list the slot. */
        NOT_IN_LICENCE(true),
        /** The setup command has not stored the slot yet. */
        SLOT_UNKNOWN(true),
        ALREADY_ACTIVATED(true),
        /** Rename, suspend or resume of a slot that is not activated. */
        NOT_ACTIVATED(true),
        /** Another entity of the customer has this name (names are compared ignoring case). */
        NAME_TAKEN(true);

        private final boolean refused;

        Result(boolean refused) {
            this.refused = refused;
        }

        public boolean refused() {
            return refused;
        }
    }

    public ActivationOutcome {
        Objects.requireNonNull(result, "result");
    }

    public boolean refused() {
        return result.refused();
    }

    /** A neutral message for the person and the log. */
    public String message() {
        String slot = slotId == null ? "the slot" : "slot " + slotId;
        return switch (result) {
            case ACTIVATED -> "Entity " + slot + " activated.";
            case RENAMED -> "Entity " + slot + " renamed.";
            case UNCHANGED -> "Entity " + slot + " already has this name.";
            case SUSPENDED -> "Entity " + slot + " suspended; nobody can sign in to it until it is resumed.";
            case RESUMED -> "Entity " + slot + " resumed.";
            case ALREADY_SUSPENDED -> "Entity " + slot + " is already suspended.";
            case NOT_SUSPENDED -> "Entity " + slot + " is not suspended.";
            case NOT_A_SLOT_ID -> "This is not an entity slot ID (ENT-...).";
            case NAME_INVALID -> "The name must be 1 to " + SlotActivation.MAX_NAME_LENGTH
                    + " characters, without control characters.";
            case NO_LICENCE -> "No valid licence is in force; store the licence first.";
            case LICENCE_EXPIRED -> "The licence expired and its grace period is over; install a renewed licence first.";
            case LICENCE_NOT_STARTED -> "The licence has not started yet; this can be done from its start date.";
            case NOT_IN_LICENCE -> "The licence in force does not include " + slot + ".";
            case SLOT_UNKNOWN -> "Entity " + slot + " is not set up in this installation; run setup with the licence.";
            case ALREADY_ACTIVATED -> "Entity " + slot + " is already activated.";
            case NOT_ACTIVATED -> "Entity " + slot + " is not activated.";
            case NAME_TAKEN -> "Another entity of this customer already has this name.";
        };
    }
}
