package com.documentplatform.notifications.spring;

import com.documentplatform.notifications.ChannelKind;
import com.documentplatform.notifications.DestinationId;
import com.documentplatform.notifications.EventSettings;
import com.documentplatform.notifications.FailureCode;
import com.documentplatform.notifications.NotificationSettings;
import com.documentplatform.notifications.Notifier;
import com.documentplatform.notifications.SendResult;
import com.documentplatform.notifications.secret.SecretRef;
import com.icegreen.greenmail.util.GreenMail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static com.documentplatform.notifications.spring.TestApp.APPROVAL;
import static com.documentplatform.notifications.spring.TestApp.E1;
import static com.documentplatform.notifications.spring.TestApp.MAIL;
import static com.documentplatform.notifications.spring.TestApp.SLACK;
import static com.documentplatform.notifications.spring.TestApp.TEAMS;
import static com.documentplatform.notifications.spring.TestApp.TENANT;
import static com.documentplatform.notifications.spring.TestApp.approval;
import static com.documentplatform.notifications.spring.TestApp.await;
import static com.documentplatform.notifications.spring.TestApp.mailProperties;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class HealthTest {

    private static final String RETIRED_TEAMS =
            "https://contoso.webhook.office.com/webhookb2/abc@def/IncomingWebhook/123/SECRET-TEAMS-TOKEN";
    private static final String PLAIN_HTTP_SLACK = "http://hooks.slack.test/services/T0/B0/SECRET-SLACK-TOKEN";

    private final GreenMail mail = TestApp.startMail();

    @AfterEach
    void stop() {
        mail.stop();
    }

    @Test
    void eachDestinationShowsWorkingHavingProblemsOrDownWithItsCodeAndNoSecret() {
        TestApp.SECRETS.put(SecretRef.of(TestApp.TEAMS_REF), RETIRED_TEAMS);
        TestApp.SECRETS.put(SecretRef.of(TestApp.SLACK_REF), PLAIN_HTTP_SLACK);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(NotificationsAutoConfiguration.class))
                .withUserConfiguration(TestApp.Beans.class)
                .withBean(DataSource.class, () -> Db.migrated().owner)
                .withPropertyValues(mailProperties(mail.getSmtp().getPort(),
                        "platform.notifications.teams.ops-teams=" + TestApp.TEAMS_REF,
                        "platform.notifications.slack.ops-slack=" + TestApp.SLACK_REF))
                .run(ctx -> {
                    NotificationsHealthIndicator indicator = ctx.getBean(NotificationsHealthIndicator.class);
                    assertEquals(Status.UP, indicator.health().getStatus());
                    assertEquals(Map.of("ops-slack", Map.of("channel", "Slack", "state", "Not used yet"),
                            "ops-teams", Map.of("channel", "Teams", "state", "Not used yet")), destinations(indicator));

                    NotificationSettings settings = ctx.getBean(NotificationSettings.class);
                    settings.connectChannels(TENANT, TestApp.tenant());
                    settings.setBusinessEvent(TENANT, E1, APPROVAL, EventSettings.defaultsFor(
                            settings.catalogue().require(APPROVAL)).withDestinations(MAIL, TEAMS, SLACK));
                    ctx.getBean(Notifier.class).publish(approval("LN 1"));
                    await(Duration.ofSeconds(10), "all three tried", () -> destinations(indicator).values().stream()
                            .allMatch(d -> ((Map<?, ?>) d).containsKey("lastAttempt")) && destinations(indicator).size() == 3);

                    Map<String, Object> d = destinations(indicator);
                    assertEquals("Working", state(d, "mail"));
                    assertEquals("Down", state(d, "ops-teams"));
                    assertEquals("WEBHOOK_CONNECTOR_RETIRED", field(d, "ops-teams", "code"));
                    assertEquals("The address is a retired Office 365 connector; use a Teams Workflows webhook",
                            field(d, "ops-teams", "reason"));
                    assertEquals("Down", state(d, "ops-slack"));
                    assertEquals("WEBHOOK_ADDRESS_NOT_SECURE", field(d, "ops-slack", "code"));
                    assertEquals("Slack webhook address must use https", field(d, "ops-slack", "reason"));
                    Health h = indicator.health();
                    assertEquals("DEGRADED", h.getStatus().getCode());

                    // The mail password changes and the login is refused: the mail destination is down.
                    TestApp.SECRETS.put(SecretRef.of(TestApp.MAIL_REF), "Wrong-SECRET-pw");
                    ctx.getBean(Notifier.class).publish(approval("LN 2"));
                    await(Duration.ofSeconds(10), "login refused", () -> "MAIL_LOGIN_REFUSED".equals(
                            field(destinations(indicator), "mail", "code")));
                    assertEquals("Down", state(destinations(indicator), "mail"));
                    assertEquals("Mail server refused the login", field(destinations(indicator), "mail", "reason"));

                    String all = indicator.health().toString();
                    for (String secretOrAddress : List.of("SECRET", "webhook.office.com", "hooks.slack", "127.0.0.1",
                            "admin@customer.example", "notifications@customer.example", TestApp.MAIL_REF,
                            TestApp.TEAMS_REF)) {
                        assertFalse(all.contains(secretOrAddress), secretOrAddress + " in " + all);
                    }
                });
        TestApp.SECRETS.put(SecretRef.of(TestApp.MAIL_REF), TestApp.MAIL_PASSWORD);
    }

    @Test
    void statesFollowTheOutcomes() {
        MovingClock clock = new MovingClock();
        DestinationHealth health = new DestinationHealth(clock);
        DestinationId mailbox = DestinationId.of("mail");
        health.expect(mailbox, ChannelKind.EMAIL);
        assertEquals(DestinationHealth.State.NOT_USED_YET, health.statuses().get(mailbox).state());

        health.record(mailbox, ChannelKind.EMAIL, SendResult.ok());
        assertEquals(DestinationHealth.State.WORKING, health.statuses().get(mailbox).state());

        health.record(mailbox, ChannelKind.EMAIL, SendResult.failed(FailureCode.MAIL_SERVER_UNREACHABLE));
        Instant firstFailure = clock.instant();
        assertEquals(DestinationHealth.State.HAVING_PROBLEMS, health.statuses().get(mailbox).state());
        clock.advance(Duration.ofMinutes(14));
        health.record(mailbox, ChannelKind.EMAIL, SendResult.failed(FailureCode.MAIL_TIMEOUT));
        assertEquals(DestinationHealth.State.HAVING_PROBLEMS, health.statuses().get(mailbox).state());
        assertEquals(firstFailure, health.statuses().get(mailbox).failingSince());
        clock.advance(Duration.ofMinutes(1));
        assertEquals(DestinationHealth.State.DOWN, health.statuses().get(mailbox).state(),
                "temporary failures for 15 minutes");

        health.record(mailbox, ChannelKind.EMAIL, SendResult.ok());
        assertEquals(DestinationHealth.State.WORKING, health.statuses().get(mailbox).state());
        assertEquals(null, health.statuses().get(mailbox).failure());

        health.record(mailbox, ChannelKind.EMAIL, SendResult.failed(FailureCode.MAIL_RECIPIENT_REFUSED));
        assertEquals(DestinationHealth.State.HAVING_PROBLEMS, health.statuses().get(mailbox).state(),
                "one refused recipient is not the server being down");
        health.record(mailbox, ChannelKind.EMAIL, SendResult.failed(FailureCode.SECRET_NOT_FOUND));
        assertEquals(DestinationHealth.State.DOWN, health.statuses().get(mailbox).state());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> destinations(NotificationsHealthIndicator indicator) {
        Map<String, Object> d = (Map<String, Object>) indicator.health().getDetails().get("destinations");
        return new java.util.TreeMap<>(d);
    }

    private static String state(Map<String, Object> d, String id) {
        return field(d, id, "state");
    }

    private static String field(Map<String, Object> d, String id, String name) {
        Map<?, ?> one = (Map<?, ?>) d.get(id);
        return one == null ? null : (String) one.get(name);
    }

    private static final class MovingClock extends Clock {
        private Instant now = Instant.parse("2026-11-20T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
