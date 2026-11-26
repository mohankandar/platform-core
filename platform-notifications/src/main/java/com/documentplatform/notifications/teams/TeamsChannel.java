package com.documentplatform.notifications.teams;

import com.documentplatform.notifications.Channel;
import com.documentplatform.notifications.ChannelKind;
import com.documentplatform.notifications.FailureCode;
import com.documentplatform.notifications.Message;
import com.documentplatform.notifications.OutgoingMessage;
import com.documentplatform.notifications.SendResult;
import com.documentplatform.notifications.secret.SecretResolver;
import com.documentplatform.notifications.webhook.internal.Json;
import com.documentplatform.notifications.webhook.internal.WebhookClient;
import com.documentplatform.notifications.webhook.WebhookDirectory;
import com.documentplatform.notifications.webhook.WebhookOptions;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Posts to a Microsoft Teams channel through a Workflows webhook (the "When a Teams webhook request is received"
 * trigger in Power Automate / Teams Workflows). The payload is a message with one Adaptive Card.
 *
 * <p>The old Office 365 connector webhooks ({@code *.webhook.office.com}, {@code outlook.office.com}) are retired
 * and refused before anything is sent ({@link FailureCode#WEBHOOK_CONNECTOR_RETIRED}).
 */
public final class TeamsChannel implements Channel, AutoCloseable {

    static final String OPEN_LABEL = "Open in the app";

    private final WebhookClient client;

    public TeamsChannel(WebhookDirectory webhooks, SecretResolver secrets) {
        this(webhooks, secrets, WebhookOptions.DEFAULT);
    }

    public TeamsChannel(WebhookDirectory webhooks, SecretResolver secrets, WebhookOptions options) {
        this.client = new WebhookClient(ChannelKind.TEAMS, webhooks, secrets, options);
    }

    /**
     * P059 (LV-24.3 step 1): posts through {@code transport} (in an app: platform-outbound, when
     * {@code webhook.via-outbound} is on). The same checks of the address and the same reading of the answer; a post
     * the outbound rules refuse is {@code WEBHOOK_OUTBOUND_REFUSED}, not retried.
     */
    public TeamsChannel(WebhookDirectory webhooks, SecretResolver secrets, WebhookOptions options,
                        com.documentplatform.notifications.transport.HttpTransport transport) {
        this.client = new WebhookClient(ChannelKind.TEAMS, webhooks, secrets, options, transport);
    }

    @Override
    public ChannelKind kind() {
        return ChannelKind.TEAMS;
    }

    @Override
    public SendResult send(OutgoingMessage out) {
        return client.post(out, payload(out.message()), TeamsChannel::retiredConnector);
    }

    /**
     * Refuses Office 365 connector addresses, retired by Microsoft; Workflows addresses are on other hosts. Checked by
     * {@link #send} before anything is sent.
     */
    static Optional<FailureCode> retiredConnector(URI uri) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        boolean retired = host.equals("webhook.office.com") || host.endsWith(".webhook.office.com")
                || ((host.equals("outlook.office.com") || host.equals("outlook.office365.com"))
                && uri.getRawPath() != null && uri.getRawPath().startsWith("/webhook"));
        return retired ? Optional.of(FailureCode.WEBHOOK_CONNECTOR_RETIRED) : Optional.empty();
    }

    /** The Workflows webhook payload: a message carrying one Adaptive Card built from the message only. */
    static String payload(Message m) {
        List<Object> body = new ArrayList<>();
        body.add(map("type", "TextBlock", "text", m.subject(), "weight", "Bolder", "size", "Medium", "wrap", true));
        for (String line : m.lines()) {
            body.add(map("type", "TextBlock", "text", line, "wrap", true, "spacing", "None"));
        }
        Map<String, Object> card = map("$schema", "http://adaptivecards.io/schemas/adaptive-card.json",
                "type", "AdaptiveCard", "version", "1.4", "body", body);
        // One-click links (Approve, Decline) open the app's page in the browser; nothing calls back into Teams.
        List<Object> actions = new ArrayList<>();
        m.actions().forEach(a -> actions.add(map("type", "Action.OpenUrl", "title", a.label(), "url",
                a.url().toString())));
        if (m.link() != null) {
            actions.add(map("type", "Action.OpenUrl", "title", OPEN_LABEL, "url", m.link().toString()));
        }
        if (!actions.isEmpty()) {
            card.put("actions", actions);
        }
        Map<String, Object> attachment = map("contentType", "application/vnd.microsoft.card.adaptive",
                "contentUrl", null, "content", card);
        return Json.write(map("type", "message", "attachments", List.of(attachment)));
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
