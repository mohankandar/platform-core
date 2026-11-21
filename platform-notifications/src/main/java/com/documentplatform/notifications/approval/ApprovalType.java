package com.documentplatform.notifications.approval;

import com.documentplatform.notifications.EventTypeId;
import com.documentplatform.notifications.RoleId;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * A kind of request an app lets approvers decide through links, for example a print request. Registered by the app;
 * this module has none of its own.
 *
 * @param id            the request type's id
 * @param requestEvent  the app's event type for "approval waiting": its name and settings make the message with the
 *                      links (if it declares a time field {@code expiresAt}, the expiry is shown)
 * @param approvedEvent the app's event type telling the requester "approved"
 * @param declinedEvent the app's event type telling the requester "declined"
 * @param approverRole  who may decide: the message goes to the holders of this role, and the app is asked again at
 *                      decision time whether the person still holds it
 * @param validFor      how long the request and its links stay open (default 24 hours; 5 minutes to 30 days)
 * @param expiredEvent  optional: the app's event type telling the requester "expired, nobody decided". Raised by the
 *                      notifications tick ({@link Approvals#tellExpired}) once per request. Null: the requester is not
 *                      told.
 */
public record ApprovalType(String id, EventTypeId requestEvent, EventTypeId approvedEvent, EventTypeId declinedEvent,
                           RoleId approverRole, Duration validFor, EventTypeId expiredEvent) {

    public static final Duration DEFAULT_VALID_FOR = Duration.ofHours(24);
    static final Duration MIN_VALID_FOR = Duration.ofMinutes(5);
    static final Duration MAX_VALID_FOR = Duration.ofDays(30);

    public ApprovalType {
        Ids.id(id, "Request type id");
        Objects.requireNonNull(requestEvent, "requestEvent");
        Objects.requireNonNull(approvedEvent, "approvedEvent");
        Objects.requireNonNull(declinedEvent, "declinedEvent");
        Objects.requireNonNull(approverRole, "approverRole");
        Objects.requireNonNull(validFor, "validFor");
        if (validFor.compareTo(MIN_VALID_FOR) < 0 || validFor.compareTo(MAX_VALID_FOR) > 0) {
            throw new IllegalArgumentException("A request stays open between 5 minutes and 30 days");
        }
    }

    /** Without an "expired" event: the requester is not told when nobody decides. */
    public ApprovalType(String id, EventTypeId requestEvent, EventTypeId approvedEvent, EventTypeId declinedEvent,
                        RoleId approverRole, Duration validFor) {
        this(id, requestEvent, approvedEvent, declinedEvent, approverRole, validFor, null);
    }

    /** With the default of 24 hours. */
    public static ApprovalType of(String id, EventTypeId requestEvent, EventTypeId approvedEvent,
                                  EventTypeId declinedEvent, RoleId approverRole) {
        return new ApprovalType(id, requestEvent, approvedEvent, declinedEvent, approverRole, DEFAULT_VALID_FOR);
    }

    /** The same type, open for another duration. */
    public ApprovalType validFor(Duration duration) {
        return new ApprovalType(id, requestEvent, approvedEvent, declinedEvent, approverRole, duration, expiredEvent);
    }

    /** The same type, telling the requester through this event type when the request expires undecided. */
    public ApprovalType whenExpired(EventTypeId event) {
        return new ApprovalType(id, requestEvent, approvedEvent, declinedEvent, approverRole, validFor,
                Objects.requireNonNull(event, "event"));
    }

    /** The "expired" event type, if the app gave one. */
    public Optional<EventTypeId> expired() {
        return Optional.ofNullable(expiredEvent);
    }
}
