package com.documentplatform.notifications.spring;

import com.documentplatform.notifications.spring.internal.NotificationsConfig;
import com.documentplatform.notifications.Channel;
import com.documentplatform.notifications.ChannelKind;
import com.documentplatform.notifications.email.EmailChannel;
import com.documentplatform.notifications.email.MicrosoftTokens;
import com.documentplatform.notifications.secret.SecretResolver;
import com.documentplatform.notifications.slack.SlackChannel;
import com.documentplatform.notifications.teams.TeamsChannel;
import com.documentplatform.notifications.transport.HttpTransport;
import com.documentplatform.notifications.webhook.WebhookDirectory;

import java.util.ArrayList;
import java.util.List;

/**
 * The senders the install config asks for: e-mail when a mail server is configured, Teams and Slack when they have
 * destinations. Each is observed for {@link DestinationHealth}. The config says which mail server sends a message.
 */
final class NotificationChannels implements AutoCloseable {

    private final List<Channel> channels;
    private final List<AutoCloseable> closeables = new ArrayList<>();

    NotificationChannels(NotificationsConfig config, SecretResolver secrets, DestinationHealth health) {
        this(config, secrets, health, new MicrosoftTokens());
    }

    /** @param tokens Microsoft 365 tokens, shared with health (used only with auth OAUTH2_MICROSOFT) */
    NotificationChannels(NotificationsConfig config, SecretResolver secrets, DestinationHealth health,
                         MicrosoftTokens tokens) {
        this(config, secrets, health, tokens, null);
    }

    /** @param webhooks the transport of the Teams and Slack posts (P059, webhook.via-outbound), or null: direct */
    NotificationChannels(NotificationsConfig config, SecretResolver secrets, DestinationHealth health,
                         MicrosoftTokens tokens, HttpTransport webhooks) {
        List<Channel> list = new ArrayList<>();
        if (config.anyMail()) {
            list.add(new ObservedChannel(new EmailChannel(config::mailServer, secrets, null, tokens), health));
        }
        if (!config.teams().isEmpty()) {
            TeamsChannel teams = webhooks == null
                    ? new TeamsChannel(WebhookDirectory.of(config.teams()), secrets, config.webhookOptions())
                    : new TeamsChannel(WebhookDirectory.of(config.teams()), secrets, config.webhookOptions(), webhooks);
            closeables.add(teams);
            list.add(new ObservedChannel(teams, health));
            config.teams().keySet().forEach(id -> health.expect(id, ChannelKind.TEAMS));
        }
        if (!config.slack().isEmpty()) {
            SlackChannel slack = webhooks == null
                    ? new SlackChannel(WebhookDirectory.of(config.slack()), secrets, config.webhookOptions())
                    : new SlackChannel(WebhookDirectory.of(config.slack()), secrets, config.webhookOptions(), webhooks);
            closeables.add(slack);
            list.add(new ObservedChannel(slack, health));
            config.slack().keySet().forEach(id -> health.expect(id, ChannelKind.SLACK));
        }
        this.channels = List.copyOf(list);
    }

    public List<Channel> channels() {
        return channels;
    }

    @Override
    public void close() throws Exception {
        for (AutoCloseable c : closeables) {
            c.close();
        }
    }
}
