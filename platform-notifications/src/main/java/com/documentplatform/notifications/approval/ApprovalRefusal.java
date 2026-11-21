package com.documentplatform.notifications.approval;

/**
 * Why a link or a decision was refused. Each has a fixed text the app's page may show as is; none repeats the token,
 * the reason or anything about the request.
 */
public enum ApprovalRefusal {
    /** Tampered, malformed, made with another key, or for another tenant. */
    LINK_INVALID("This link is not valid."),
    /** An e-mail link opened by someone signed in as another person. */
    LINK_NOT_FOR_YOU("This link was sent to someone else."),
    REQUEST_UNKNOWN("This request no longer exists."),
    OWN_REQUEST("You cannot decide on your own request."),
    NOT_AN_APPROVER("You do not hold the approver role for this request."),
    REQUEST_EXPIRED("This request has expired."),
    REASON_REQUIRED("Give a reason to decline."),
    REASON_INVALID("The reason is too long or holds characters that are not allowed."),
    KEY_UNAVAILABLE("Links cannot be checked right now. Try again later."),
    APPROVER_CHECK_UNAVAILABLE("Your approver role cannot be checked right now. Try again later.");

    private final String text;

    ApprovalRefusal(String text) {
        this.text = text;
    }

    /** The text for the page. */
    public String text() {
        return text;
    }
}
