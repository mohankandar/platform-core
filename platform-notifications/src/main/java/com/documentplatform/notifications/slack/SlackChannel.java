package com.documentplatform.notifications.slack;

import com.documentplatform.notifications.Channel;
import com.documentplatform.notifications.ChannelKind;
import com.documentplatform.notifications.Message;
import com.documentplatform.notifications.OutgoingMessage;
import com.documentplatform.notifications.SendResult;
import com.documentplatform.notifications.secret.SecretResolver;
import com.documentplatform.notifications.webhook.Json;
import com.documentplatform.notifications.webhook.WebhookClient;
import com.documentplatform.notifications.webhook.WebhookDirectory;
import com.documentplatform.notifications.webhook.WebhookOptions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Posts to a Slack channel through an incoming webhook, as a Block Kit message: a header with the subject, the lines
 * as plain text (never mrkdwn, so nothing in a message is read as formatting or a link) and a button into the app.
 */
public final class SlackChannel implements Channel, AutoCloseable {

    static final String OPEN_LABEL = "Open in the app";
    /** Slack limits: header text 150 characters, section text 3000, 50 blocks per message. */
    static final int HEADER_MAX = 150;
    static final int SECTION_MAX = 3000;
    static final int MAX_SECTIONS = 45;

    private final WebhookClient client;

    public SlackChannel(WebhookDirectory webhooks, SecretResolver secrets) {
        this(webhooks, secrets, WebhookOptions.DEFAULT);
    }

    public SlackChannel(WebhookDirectory webhooks, SecretResolver secrets, WebhookOptions options) {
        this.client = new WebhookClient(ChannelKind.SLACK, webhooks, secrets, options);
    }

    @Override
    public ChannelKind kind() {
        return ChannelKind.SLACK;
    }

    @Override
    public SendResult send(OutgoingMessage out) {
        return client.post(out, payload(out.message()), uri -> Optional.empty());
    }

    /** The incoming-webhook payload, built from the message only. */
    public static String payload(Message m) {
        List<Object> blocks = new ArrayList<>();
        blocks.add(map("type", "header", "text", plain(truncate(m.subject(), HEADER_MAX))));
        for (String section : sections(m.lines())) {
            blocks.add(map("type", "section", "text", plain(section)));
        }
        // One-click links (Approve, Decline) are link buttons: they open the app's page, nothing calls back.
        List<Object> buttons = new ArrayList<>();
        m.actions().forEach(a -> buttons.add(map("type", "button", "text", plain(a.label()), "url",
                a.url().toString())));
        if (m.link() != null) {
            buttons.add(map("type", "button", "text", plain(OPEN_LABEL), "url", m.link().toString()));
        }
        if (!buttons.isEmpty()) {
            blocks.add(map("type", "actions", "elements", buttons));
        }
        // "text" is the notification preview; Slack reads it as mrkdwn, so & < > are escaped.
        return Json.write(map("text", escapeMrkdwn(m.subject()), "blocks", blocks));
    }

    /** Lines joined into sections within Slack's size limits. */
    static List<String> sections(List<String> lines) {
        List<String> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            if (current.length() > 0 && current.length() + 1 + line.length() > SECTION_MAX) {
                sections.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(truncate(line, SECTION_MAX));
        }
        if (current.length() > 0) {
            sections.add(current.toString());
        }
        if (sections.size() > MAX_SECTIONS) {
            List<String> kept = new ArrayList<>(sections.subList(0, MAX_SECTIONS - 1));
            kept.add("... more in the app");
            return kept;
        }
        return sections;
    }

    private static Map<String, Object> plain(String text) {
        return map("type", "plain_text", "text", text, "emoji", false);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private static String escapeMrkdwn(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    @Override
    public void close() {
        client.close();
    }
}
