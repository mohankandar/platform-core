package com.documentplatform.notifications;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * One message in the outbox. Saved before the first attempt, so a restart loses nothing; its {@link #key()} is the
 * idempotency key, derived from what the message is (an event and a destination, a condition's n-th alert, a summary
 * bucket), so re-creating the same message after a crash finds the saved one instead of adding a second.
 *
 * <p>Holds what the content rule allows in a message, plus ids. Recipients are kept as roles and looked up at send
 * time, so no e-mail address is stored; no secret is stored either (destinations are ids).
 *
 * @param entity         the entity whose role holders receive it (e-mail), or null for a tenant-level message
 * @param recipientRoles roles whose holders receive it by e-mail; empty for Teams and Slack
 * @param attempts       attempts made so far
 * @param nextAttemptAt  when it may be tried (again)
 * @param lastFailure    the code of the last failed attempt; null if none failed
 * @param finishedAt     when it was sent or given up; null while pending
 * @param recipientUser  the one person who receives it by e-mail, by user id, looked up at send time
 *                       ({@link RecipientResolver#person}); null when it goes to the holders of the recipient roles
 */
public record OutboxEntry(String key, TenantId tenant, DestinationId destination, ChannelKind channel, EntityId entity,
                          Set<RoleId> recipientRoles, Set<EventTypeId> eventTypes, Message message, OutboxState state,
                          int attempts, Instant createdAt, Instant nextAttemptAt, FailureCode lastFailure,
                          Instant finishedAt, String recipientUser) {

    public OutboxEntry {
        Text.require(key, Text.ID, "Outbox key");
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(channel, "channel");
        recipientRoles = Set.copyOf(recipientRoles);
        eventTypes = Set.copyOf(eventTypes);
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        if (recipientUser != null) {
            Text.require(recipientUser, Text.ID, "User id");
            if (!recipientRoles.isEmpty() || channel != ChannelKind.EMAIL) {
                throw new IllegalArgumentException("A message to one person is an e-mail without recipient roles");
            }
        }
    }

    /** A message to the holders of the recipient roles (or to a channel). */
    public OutboxEntry(String key, TenantId tenant, DestinationId destination, ChannelKind channel, EntityId entity,
                       Set<RoleId> recipientRoles, Set<EventTypeId> eventTypes, Message message, OutboxState state,
                       int attempts, Instant createdAt, Instant nextAttemptAt, FailureCode lastFailure,
                       Instant finishedAt) {
        this(key, tenant, destination, channel, entity, recipientRoles, eventTypes, message, state, attempts,
                createdAt, nextAttemptAt, lastFailure, finishedAt, null);
    }

    /** A new message, due at once. */
    public static OutboxEntry pending(String key, TenantId tenant, DestinationId destination, ChannelKind channel,
                                      EntityId entity, Set<RoleId> roles, Set<EventTypeId> types, Message message,
                                      Instant now) {
        return new OutboxEntry(key, tenant, destination, channel, entity, roles, types, message, OutboxState.PENDING, 0,
                now, now, null, null, null);
    }

    /** A new e-mail to one person, due at once. */
    public static OutboxEntry toPerson(String key, TenantId tenant, DestinationId destination, EntityId entity,
                                       String userId, Set<EventTypeId> types, Message message, Instant now) {
        return new OutboxEntry(key, tenant, destination, ChannelKind.EMAIL, entity, Set.of(), types, message,
                OutboxState.PENDING, 0, now, now, null, null, userId);
    }
}
