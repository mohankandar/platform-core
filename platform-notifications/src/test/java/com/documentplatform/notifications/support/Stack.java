package com.documentplatform.notifications.support;

import com.documentplatform.notifications.Channel;
import com.documentplatform.notifications.ConditionStore;
import com.documentplatform.notifications.DeliveryReport;
import com.documentplatform.notifications.DestinationId;
import com.documentplatform.notifications.EntityId;
import com.documentplatform.notifications.EventSettings;
import com.documentplatform.notifications.EventTypeId;
import com.documentplatform.notifications.NotificationEvent;
import com.documentplatform.notifications.NotificationSettings;
import com.documentplatform.notifications.Notifier;
import com.documentplatform.notifications.OutboxStore;
import com.documentplatform.notifications.Schedule;
import com.documentplatform.notifications.SummaryStore;
import com.documentplatform.notifications.email.EmailChannel;
import com.documentplatform.notifications.email.MailSecurity;
import com.documentplatform.notifications.email.MailServer;
import com.documentplatform.notifications.email.MailServerDirectory;
import com.documentplatform.notifications.jdbc.PostgresConditionStore;
import com.documentplatform.notifications.jdbc.PostgresOutboxStore;
import com.documentplatform.notifications.jdbc.PostgresSettingsStore;
import com.documentplatform.notifications.jdbc.PostgresSummaryStore;
import com.documentplatform.notifications.secret.Secret;
import com.documentplatform.notifications.secret.SecretRef;
import com.documentplatform.notifications.secret.SecretResolver;
import com.documentplatform.notifications.slack.SlackChannel;
import com.documentplatform.notifications.teams.TeamsChannel;
import com.documentplatform.notifications.webhook.WebhookDirectory;
import com.documentplatform.notifications.webhook.WebhookOptions;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;

import javax.sql.DataSource;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static com.documentplatform.notifications.support.TestCatalogue.MAIL;
import static com.documentplatform.notifications.support.TestCatalogue.SLACK;
import static com.documentplatform.notifications.support.TestCatalogue.START;
import static com.documentplatform.notifications.support.TestCatalogue.TEAMS;
import static com.documentplatform.notifications.support.TestCatalogue.TENANT;

/**
 * Everything real: Postgres stores in a fresh schema, the SMTP sender against GreenMail, the Teams and Slack senders
 * against a local webhook server, secrets behind a resolver. Notifiers built from it share the stores, so building a
 * second one is a restart.
 */
public final class Stack implements AutoCloseable {

    public static final String MAIL_PASSWORD = "Mail-Pw-91x-SECRET";
    public static final String TEAMS_SIG = "sig-TEAMS-SECRET-4711";
    public static final String SLACK_TOKEN = "SLACK-SECRET-TOKEN-0815";
    public static final SecretRef MAIL_REF = SecretRef.of("notifications/t1/mail-password");
    public static final SecretRef TEAMS_REF = SecretRef.of("notifications/t1/ops-teams");
    public static final SecretRef SLACK_REF = SecretRef.of("notifications/t1/ops-slack");

    public final DataSource ds = Pg.freshSchema();
    public final TestCatalogue.MovingClock clock = new TestCatalogue.MovingClock(START);
    public final GreenMail mail;
    public final WebhookServer hooks;
    public final Map<SecretRef, String> secrets = new ConcurrentHashMap<>();
    public final SecretResolver secretResolver = ref -> Optional.ofNullable(secrets.get(ref)).map(Secret::of);
    public final NotificationSettings settings =
            new NotificationSettings(TestCatalogue.catalogue(), new PostgresSettingsStore(ds));
    public final OutboxStore outbox = new PostgresOutboxStore(ds);
    public final SummaryStore summaries = new PostgresSummaryStore(ds);
    public final ConditionStore conditions = new PostgresConditionStore(ds);
    public final EmailChannel email;
    public final TeamsChannel teams;
    public final SlackChannel slack;

    public Stack() throws IOException {
        mail = new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP).dynamicPort())
                .withConfiguration(GreenMailConfiguration.aConfig()
                        .withUser("notifications@customer.example", "notify", MAIL_PASSWORD));
        mail.start();
        hooks = new WebhookServer();
        secrets.put(MAIL_REF, MAIL_PASSWORD);
        secrets.put(TEAMS_REF, hooks.base() + "/workflows/abc/triggers/manual/paths/invoke?api-version=1&sig=" + TEAMS_SIG);
        secrets.put(SLACK_REF, hooks.base() + "/services/T000/B000/" + SLACK_TOKEN);
        MailServer server = MailServer.withLogin("127.0.0.1", mail.getSmtp().getPort(), MailSecurity.NONE, "notify",
                MAIL_REF, "notifications@customer.example").withTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(2));
        WebhookOptions local = com.documentplatform.notifications.webhook.LocalWebhookOptions.plainHttpToThisMachine(
                WebhookOptions.DEFAULT.withTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(2)));
        email = new EmailChannel(MailServerDirectory.of(Map.of(MAIL, server)), secretResolver);
        teams = new TeamsChannel(WebhookDirectory.of(Map.of(TEAMS, TEAMS_REF)), secretResolver, local);
        slack = new SlackChannel(WebhookDirectory.of(Map.of(SLACK, SLACK_REF)), secretResolver, local);
        settings.connectChannels(TENANT, TestCatalogue.tenant());
    }

    public Notifier notifier() {
        return notifier(summaries, conditions, List.of(email, teams, slack));
    }

    public Notifier notifier(SummaryStore summaries, ConditionStore conditions, List<? extends Channel> channels) {
        return Notifier.builder(settings, TestCatalogue.resolver()).channels(channels).clock(clock).outbox(outbox)
                .summaries(summaries).conditions(conditions).build();
    }

    /** Moves the clock and ticks. */
    public List<DeliveryReport> advance(Notifier n, Duration by) {
        clock.advance(by);
        return n.tick(clock.instant());
    }

    /** Publishes, then runs the sender once (as the background sender does at once), and reports the outcome. */
    public List<DeliveryReport> publish(Notifier n, NotificationEvent event) {
        return SendNow.delivered(n, clock.instant(), n.publish(event));
    }

    /** Runs the sender once after a call that queued messages. */
    public List<DeliveryReport> delivered(Notifier n, List<DeliveryReport> queued) {
        return SendNow.delivered(n, clock.instant(), queued);
    }

    public void routeTechnical(EventTypeId type, Schedule schedule, DestinationId... destinations) {
        settings.setTechnicalEvent(TENANT, type, EventSettings.defaultsFor(settings.catalogue().require(type))
                .withSchedule(schedule).withDestinations(destinations));
    }

    public void routeBusiness(EntityId entity, EventTypeId type, DestinationId... destinations) {
        settings.setBusinessEvent(TENANT, entity, type,
                EventSettings.defaultsFor(settings.catalogue().require(type)).withDestinations(destinations));
    }

    @Override
    public void close() {
        mail.stop();
        hooks.close();
        teams.close();
        slack.close();
    }
}
