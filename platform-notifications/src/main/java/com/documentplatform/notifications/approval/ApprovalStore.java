package com.documentplatform.notifications.approval;

import com.documentplatform.notifications.TenantId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Where requests and their decision are kept. Postgres: {@code jdbc.PostgresApprovalStore} (table
 * {@code notif_approval_request}); in memory for tests in the testkit module.
 *
 * <p><b>First decision wins.</b> {@link #decide} stores a decision only if the request has none and has not expired;
 * of two decisions at the same moment, also from two app instances, exactly one is stored and the other is told the
 * stored one.
 *
 * <p>SPI: implemented by stores; apps call {@link Approvals}.
 */
public interface ApprovalStore {

    /** Saves a new request. */
    void create(ApprovalRequest request);

    Optional<ApprovalRequest> find(TenantId tenant, UUID id);

    /** Stores the decision if the request is still open at {@code now}; otherwise changes nothing. */
    Stored decide(TenantId tenant, UUID id, Decision decision, Instant now);

    /** Deletes requests that expired before {@code before} (decided or not). Returns how many. */
    int purgeExpiredBefore(Instant before);

    /**
     * Claims up to {@code limit} requests of these types that expired undecided by {@code now} and whose requester
     * was not yet told, and marks them told. Each request is claimed once, also with several app instances at the
     * same moment. A request past its expiry can no longer be decided, so a claimed one stays undecided.
     *
     * @return the claimed requests, oldest expiry first
     */
    List<ApprovalRequest> claimExpiredUndecided(Set<String> types, Instant now, int limit);

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
