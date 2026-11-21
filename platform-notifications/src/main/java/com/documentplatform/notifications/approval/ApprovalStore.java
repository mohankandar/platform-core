package com.documentplatform.notifications.approval;

import com.documentplatform.notifications.TenantId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Where requests and their decision are kept. Postgres: {@code jdbc.PostgresApprovalStore} (table
 * {@code notif_approval_request}); in memory for tests in the testkit module.
 *
 * <p><b>First decision wins.</b> {@link #decide} stores a decision only if the request has none and has not expired;
 * of two decisions at the same moment, also from two app instances, exactly one is stored and the other is told the
 * stored one.
 */
public interface ApprovalStore {

    /** Saves a new request. */
    void create(ApprovalRequest request);

    Optional<ApprovalRequest> find(TenantId tenant, UUID id);

    /** Stores the decision if the request is still open at {@code now}; otherwise changes nothing. */
    Stored decide(TenantId tenant, UUID id, Decision decision, Instant now);

    /** Deletes requests that expired before {@code before} (decided or not). Returns how many. */
    int purgeExpiredBefore(Instant before);

    /** What {@link #decide} did. */
    record Stored(Kind kind, ApprovalRequest request) {

        public enum Kind {
            /** This decision was stored: it is the first. */
            DECIDED,
            /** Another decision was stored first; {@link #request()} carries it. */
            ALREADY_DECIDED,
            /** Not decided, and past its expiry. */
            EXPIRED,
            /** No such request; {@link #request()} is null. */
            UNKNOWN
        }

        public Stored {
            Objects.requireNonNull(kind, "kind");
            if ((kind == Kind.UNKNOWN) != (request == null)) {
                throw new IllegalArgumentException("Only an unknown request has no request");
            }
        }
    }
}
