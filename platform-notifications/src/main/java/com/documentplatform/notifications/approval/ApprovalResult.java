package com.documentplatform.notifications.approval;

import java.util.Objects;

/**
 * What {@link Approvals#open} and {@link Approvals#decide} answer. The app's page shows one of four things:
 * <ul>
 *   <li>{@link Ready} (open only): the request and the action of the link; the page asks for the confirming click
 *       (and a reason, to decline);</li>
 *   <li>{@link Decided} (decide only): this person's decision was stored; it is the first;</li>
 *   <li>{@link AlreadyDecided}: someone decided first; show {@link ApprovalRequest#outcomeText}; nothing changed;</li>
 *   <li>{@link Refused}: show {@link ApprovalRefusal#text()}; nothing changed.</li>
 * </ul>
 */
public sealed interface ApprovalResult {

    record Ready(ApprovalRequest request, ApprovalAction action) implements ApprovalResult {
        public Ready {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(action, "action");
        }
    }

    record Decided(ApprovalRequest request) implements ApprovalResult {
        public Decided {
            Objects.requireNonNull(request.decision(), "decision");
        }
    }

    record AlreadyDecided(ApprovalRequest request) implements ApprovalResult {
        public AlreadyDecided {
            Objects.requireNonNull(request.decision(), "decision");
        }
    }

    record Refused(ApprovalRefusal refusal) implements ApprovalResult {
        public Refused {
            Objects.requireNonNull(refusal, "refusal");
        }
    }
}
