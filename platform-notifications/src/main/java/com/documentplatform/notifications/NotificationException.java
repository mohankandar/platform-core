package com.documentplatform.notifications;

import java.util.Objects;

/**
 * A catalogue, setting or event was refused. The message names the rule, never the refused value, so it is safe for
 * logs.
 */
public final class NotificationException extends RuntimeException implements com.documentplatform.errors.HasPlatformCode {

    /** Why it was refused. Stable: apps may map each reason to their own screen message. */
    public enum Reason implements com.documentplatform.errors.PlatformCode {
        /** A value does not have the allowed shape (id, identifier, link, label, address). */
        INVALID_VALUE,
        /** The event type is not in the catalogue. */
        UNKNOWN_EVENT_TYPE,
        /** Two event types in one catalogue share an id. */
        DUPLICATE_EVENT_TYPE,
        /** A critical event set to anything other than Immediately or Every hour. */
        CRITICAL_FREQUENCY,
        /** A critical event switched off. */
        CRITICAL_MUST_BE_ON,
        /** A critical event whose recipients leave out the IT Admin role. */
        CRITICAL_NEEDS_IT_ADMIN,
        /** No recipient role. */
        NO_RECIPIENT_ROLES,
        /** A condition threshold or repeat interval outside the allowed range. */
        TIMING_OUT_OF_RANGE,
        /** A numeric threshold outside the range the event type declares. */
        THRESHOLD_OUT_OF_RANGE,
        /** A threshold the event type does not declare. */
        UNKNOWN_THRESHOLD,
        /** A count or time the event type does not declare, or of the wrong kind. */
        UNKNOWN_FIELD,
        /** Technical settings given per entity, or business settings given at tenant level. */
        WRONG_SETTINGS_LEVEL,
        /** A business event without an entity. */
        ENTITY_REQUIRED,
        /** Condition calls for an event type that is not continuous, or condition timing on one. */
        NOT_A_CONDITION,
        /** A continuous event type published as a one-off event. */
        IS_A_CONDITION,
        /** Settings name a destination the tenant has not connected. */
        UNKNOWN_DESTINATION
    }

    private final Reason reason;

    public NotificationException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }

    /** The code, for monitoring and the app's error handler ({@code HasPlatformCode}). */
    @Override
    public com.documentplatform.errors.PlatformCode platformCode() {
        return reason;
    }
}
