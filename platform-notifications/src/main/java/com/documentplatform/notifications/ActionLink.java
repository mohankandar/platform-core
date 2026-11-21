package com.documentplatform.notifications;

import java.net.URI;
import java.util.Objects;
import java.util.Set;

/**
 * A one-click link a message carries in addition to its main link, for example "Approve" or "Decline". Made at send
 * time for one recipient (or one channel) and never stored: the link holds a signed token. {@link #toString()} shows
 * the label only, so the token cannot reach a log.
 *
 * @param label a fixed label (catalogue-style text, for example "Approve")
 * @param url   the full link into the app
 */
public record ActionLink(String label, URI url) {

    public ActionLink {
        Text.require(label, Text.LABEL, "Action label");
        Objects.requireNonNull(url, "url");
        if (!url.isAbsolute() || !Set.of("https", "http").contains(url.getScheme()) || url.getHost() == null
                || url.getRawUserInfo() != null || url.getRawQuery() != null || url.getRawFragment() != null) {
            throw new NotificationException(NotificationException.Reason.INVALID_VALUE, "Action link is not valid");
        }
    }

    @Override
    public String toString() {
        return "ActionLink[" + label + "]";
    }
}
