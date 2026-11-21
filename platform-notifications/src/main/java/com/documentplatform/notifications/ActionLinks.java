package com.documentplatform.notifications;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Makes the one-click links of a message that asks for a decision ({@link Notifier#publishActionRequest}). Called by
 * the sender at send time, once per recipient (e-mail) or once per channel (Teams, Slack), so the links are never
 * stored in the outbox. The approval part of this module ({@code approval.ApprovalLinks}) implements it.
 */
public interface ActionLinks {

    /**
     * @param requestId  the request the message is about
     * @param appBaseUrl the tenant's app address; links are made under it
     */
    Result linksFor(TenantId tenant, String requestId, ActionAudience audience, URI appBaseUrl, Instant now);

    /**
     * The links for one audience; or none (this person gets no links, for example the requester); or a failure
     * ({@link FailureCode#ACTION_REQUEST_CLOSED}: the message is not sent at all).
     */
    record Result(List<ActionLink> links, FailureCode failure) {

        public Result {
            links = List.copyOf(links);
            if (failure != null && !links.isEmpty()) {
                throw new IllegalArgumentException("A failed result has no links");
            }
        }

        public static Result of(List<ActionLink> links) {
            if (links.isEmpty()) {
                throw new IllegalArgumentException("Use none() for no links");
            }
            return new Result(links, null);
        }

        /** This audience gets no links and no message. */
        public static Result none() {
            return new Result(List.of(), null);
        }

        public static Result failed(FailureCode failure) {
            return new Result(List.of(), Objects.requireNonNull(failure, "failure"));
        }

        public boolean failed() {
            return failure != null;
        }

        @Override
        public String toString() {
            return failure != null ? "Result[" + failure + "]" : "Result[" + links.size() + " links]";
        }
    }
}
