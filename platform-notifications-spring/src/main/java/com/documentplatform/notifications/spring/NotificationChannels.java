package com.documentplatform.notifications.spring;

import com.documentplatform.notifications.Channel;
import com.documentplatform.notifications.ChannelKind;
import com.documentplatform.notifications.email.EmailChannel;
import com.documentplatform.notifications.email.MicrosoftTokens;
import com.documentplatform.notifications.secret.SecretResolver;
import com.documentplatform.notifications.slack.SlackChannel;
import com.documentplatform.notifications.teams.TeamsChannel;
import com.documentplatform.notifications.webhook.WebhookDirectory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The senders the install config asks for: e-mail when a mail server is configured, Teams and Slack when they have
 * destinations. Each is observed for {@link DestinationHealth}. One mail server serves every e-mail destination.
 */
public final class NotificationChannels implements AutoCloseable {

    private final List<Channel> channels;
    private final List<AutoCloseable> closeables = new ArrayList<>();

    NotificationChannels(NotificationsConfig config, SecretResolver secrets, DestinationHealth health) {
        this(config, secrets, health, new MicrosoftTokens());
    }

    /** @param tokens Microsoft 365 tokens, shared with health (used only with auth OAUTH2_MICROSOFT) */
    NotificationChannels(NotificationsConfig config, SecretResolver secrets, DestinationHealth health,
                         MicrosoftTokens tokens) {
        List<Channel> list = new ArrayList<>();
        config.mail().ifPresent(server -> list.add(new ObservedChannel(
                new EmailChannel((tenant, destination) -> Optional.of(server), secrets, null, tokens), health)));
        if (!config.teams().isEmpty()) {
            TeamsChannel teams = new TeamsChannel(WebhookDirectory.of(config.teams()), secrets, config.webhookOptions());
            closeables.add(teams);
            list.add(new ObservedChannel(teams, health));
            config.teams().keySet().forEach(id -> health.expect(id, ChannelKind.TEAMS));
        }
        if (!config.slack().isEmpty()) {
            SlackChannel slack = new SlackChannel(WebhookDirectory.of(config.slack()), secrets, config.webhookOptions());
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
