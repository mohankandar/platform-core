package com.documentplatform.notifications.approval;

import com.documentplatform.notifications.AppLink;
import com.documentplatform.notifications.EntityId;
import com.documentplatform.notifications.RecordIdentifier;
import com.documentplatform.notifications.TenantId;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A request waiting for a decision, or decided. What the app needs to show its approval page: the request type, the
 * record's identifier, who asked, until when, and the decision once made.
 *
 * @param requester  the user id of the person who asked; they cannot decide on it
 * @param identifier the record's primary identifier, as in the message; optional
 * @param link       the request's page in the app, as in the message; optional
 * @param expiresAt  when the request and its links stop working (whole seconds)
 * @param decision   the first decision made; null while open
 */
public record ApprovalRequest(TenantId tenant, UUID id, EntityId entity, String type, String requester,
                              RecordIdentifier identifier, AppLink link, Instant createdAt, Instant expiresAt,
                              Decision decision) {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");

    public ApprovalRequest {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(entity, "entity");
        Ids.id(type, "Request type id");
        Ids.id(requester, "User id");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("A request expires after it was made");
        }
        if (decision != null && decision.byUserId().equals(requester)) {
            throw new IllegalArgumentException("Nobody decides on their own request");
        }
    }

    public Optional<Decision> decided() {
        return Optional.ofNullable(decision);
    }

    /** Not decided and not expired at {@code now}. */
    public boolean isOpen(Instant now) {
        return decision == null && now.isBefore(expiresAt);
    }

    /** Not decided, and past its expiry at {@code now}. */
    public boolean isExpired(Instant now) {
        return decision == null && !now.isBefore(expiresAt);
    }

    /**
     * For the page a later click sees: "Already approved by Ann Lee at 2026-11-21 10:15 CET". Empty while open.
     */
    public Optional<String> outcomeText(ZoneId zone) {
        return decided().map(d -> "Already " + d.action().pastTense() + " by " + d.byName() + " at "
                + TIME.format(d.at().atZone(zone)));
    }
}
