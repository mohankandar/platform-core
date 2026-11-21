package com.documentplatform.notifications;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * A message ready for a channel. Built only by this module, from catalogue labels and the event's structured fields,
 * so it carries the event's name, the record's identifier, counts and times, and a link into the app — nothing else.
 * Channels format it (P004); they add no content.
 *
 * <p>A message that asks for a decision names its request ({@link #actionRequest()}); its one-click links
 * ({@link #actions()}) are added at send time for each recipient and are never stored.
 *
 * @param link          the main link into the app; null if the event had none
 * @param actionRequest the request this message asks a decision on; null for other messages
 * @param actions       one-click links (for example Approve, Decline); empty until added at send time
 */
public record Message(MessageKind kind, String subject, List<String> lines, URI link, String actionRequest,
                      List<ActionLink> actions) {

    public Message {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subject, "subject");
        lines = List.copyOf(lines);
        if (actionRequest != null) {
            Text.require(actionRequest, Text.ID, "Request id");
        }
        actions = List.copyOf(actions);
    }

    public Message(MessageKind kind, String subject, List<String> lines, URI link) {
        this(kind, subject, lines, link, null, List.of());
    }

    /** The same message about a request. */
    Message forRequest(String requestId) {
        return new Message(kind, subject, lines, link, requestId, actions);
    }

    /** The same message with one recipient's (or one channel's) one-click links. */
    public Message withActions(List<ActionLink> links) {
        return new Message(kind, subject, lines, link, actionRequest, links);
    }

    /** Plain-text form: subject, lines, one-click links, link. */
    public String text() {
        StringBuilder b = new StringBuilder(subject);
        lines.forEach(line -> b.append('\n').append(line));
        if (!actions.isEmpty()) {
            b.append('\n');
            actions.forEach(a -> b.append('\n').append(a.label()).append(": ").append(a.url()));
            b.append('\n');
        }
        if (link != null) {
            b.append('\n').append(link);
        }
        return b.toString();
    }

    /** Leaves out the one-click links, which carry tokens. */
    @Override
    public String toString() {
        return "Message[kind=" + kind + ", subject=" + subject + ", lines=" + lines + ", link=" + link
                + ", actionRequest=" + actionRequest + ", actions=" + actions + "]";
    }
}
