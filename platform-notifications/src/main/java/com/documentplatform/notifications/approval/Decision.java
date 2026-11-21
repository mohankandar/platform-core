package com.documentplatform.notifications.approval;

import java.time.Instant;
import java.util.Objects;

/**
 * The decision on a request: the first one made, which is the only one stored.
 *
 * @param reason the decider's reason: required to decline, optional to approve; null if none. Shown on the app's
 *               page only, never in a message or a log ({@link #toString()} leaves it out)
 */
public record Decision(ApprovalAction action, String byUserId, String byName, Instant at, String reason) {

    public Decision {
        Objects.requireNonNull(action, "action");
        Ids.id(byUserId, "User id");
        byName = Ids.name(byName);
        Objects.requireNonNull(at, "at");
        if (action == ApprovalAction.DECLINE && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("A decline needs a reason");
        }
    }

    @Override
    public String toString() {
        return "Decision[" + action + " by " + byUserId + " at " + at + "]";
    }
}
