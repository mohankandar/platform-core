package com.documentplatform.notifications.approval;

import com.documentplatform.notifications.EntityId;
import com.documentplatform.notifications.TenantId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Supplied by the app: its audit of every decision attempt. Called once for each {@link Approvals#decide} call,
 * whatever the outcome: the decision stored, too late (someone decided first), or refused. Opening a link is not a
 * decision and is not passed here.
 *
 * <p>Called after the decision is stored and the requester's message is queued. An exception thrown here reaches
 * the app's caller; the stored decision stands.
 */
@FunctionalInterface
public interface ApprovalAudit {

    void record(Entry entry);

    enum Outcome {
        DECIDED,
        ALREADY_DECIDED,
        REFUSED
    }

    /**
     * One decision attempt.
     *
     * @param request  the request, or null if the link could not be read
     * @param entity   the request's entity, or null if unknown
     * @param type     the request type, or null if unknown
     * @param action   what the person chose, or null if the link could not be read
     * @param refusal  why it was refused; null unless {@link Outcome#REFUSED}
     * @param reason   the person's reason as given (for the app's own audit store; never logged here, and
     *                 {@link #toString()} leaves it out); null if none
     */
    record Entry(TenantId tenant, UUID request, EntityId entity, String type, String userId, ApprovalAction action,
                 Outcome outcome, ApprovalRefusal refusal, Instant at, String reason) {

        public Entry {
            Objects.requireNonNull(tenant, "tenant");
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(at, "at");
            if ((outcome == Outcome.REFUSED) != (refusal != null)) {
                throw new IllegalArgumentException("A refusal comes with its reason code, and only a refusal");
            }
        }

        @Override
        public String toString() {
            return "Entry[" + tenant + ", " + request + ", " + userId + ", " + action + ", " + outcome
                    + (refusal == null ? "" : " " + refusal) + ", " + at + "]";
        }
    }
}
