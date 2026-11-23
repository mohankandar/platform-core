package com.documentplatform.notifications.spring;

import com.documentplatform.notifications.DestinationId;
import com.documentplatform.notifications.email.MailSecurity;
import com.documentplatform.notifications.email.MailServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The install config is checked at startup, each wrong value with its key, and no secret is ever repeated. */
class PropertyValidationTest {

    private static final String P = "platform.notifications.";

    /** Binds the properties the way Spring does, then checks them. */
    private static NotificationsConfig config(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String kv : keyValues) {
            int eq = kv.indexOf('=');
            map.put(kv.substring(0, eq), kv.substring(eq + 1));
        }
        NotificationsProperties p = new Binder(new MapConfigurationPropertySource(map))
                .bindOrCreate(NotificationsProperties.PREFIX, NotificationsProperties.class);
        return NotificationsConfig.from(p);
    }

    private static List<String> problems(String... keyValues) {
        return assertThrows(NotificationsConfigException.class, () -> config(keyValues)).problems();
    }

    private static final String[] GOOD_MAIL = {
            P + "mail.host=smtp.customer.example", P + "mail.username=notifications@customer.example",
            P + "mail.password-secret=notifications/mail-password", P + "mail.from-address=notifications@customer.example",
            P + "mail.from-name=Notifications"};

    @Test
    void aCompleteConfigIsAcceptedWithDefaults() {
        NotificationsConfig c = config(concat(GOOD_MAIL, P + "teams.ops-teams=notifications/ops-teams",
                P + "slack.ops-slack=notifications/ops-slack"));
        MailServer m = c.mail().orElseThrow();
        assertEquals(MailSecurity.STARTTLS, m.security());
        assertEquals(587, m.port(), "the STARTTLS port when none is given");
        assertEquals("notifications/mail-password", m.password().value());
        assertEquals("Notifications", m.fromName());
        assertEquals(Map.of(DestinationId.of("ops-teams"), com.documentplatform.notifications.secret.SecretRef.of(
                "notifications/ops-teams")), c.teams());
        assertEquals(Duration.ofMinutes(1), c.retry().firstDelay());
        assertEquals(Duration.ofMinutes(30), c.retry().maxDelay());
        assertEquals(Duration.ofHours(24), c.retry().giveUpAfter());
        assertEquals(Duration.ofMinutes(5), c.lease());
        assertEquals(Duration.ofDays(30), c.retention());
        assertEquals(Duration.ofMinutes(1), c.tickInterval());
    }

    @Test
    void noChannelsAtAllIsValid() {
        NotificationsConfig c = config();
        assertTrue(c.mail().isEmpty() && c.teams().isEmpty() && c.slack().isEmpty());
    }

    @Test
    void portDefaultsFollowTheSecurity() {
        assertEquals(465, config(concat(GOOD_MAIL, P + "mail.security=tls")).mail().orElseThrow().port());
        assertEquals(25, config(P + "mail.host=relay.internal", P + "mail.security=NONE",
                P + "mail.from-address=n@customer.example").mail().orElseThrow().port());
    }

    @Test
    void eachMailMistakeIsNamed() {
        assertEquals(List.of(P + "mail.host: missing; give the mail server's host name, or remove the other "
                        + P + "mail.* settings to switch e-mail off"),
                problems(P + "mail.from-address=n@customer.example"));
        assertEquals(List.of(P + "mail.security: must be STARTTLS, TLS or NONE"),
                problems(concat(GOOD_MAIL, P + "mail.security=ssl")));
        assertEquals(List.of(P + "mail.port: must be between 1 and 65535, was 70000"),
                problems(concat(GOOD_MAIL, P + "mail.port=70000")));
        assertEquals(List.of(P + "mail.password-secret: missing; a login (" + P + "mail.username) needs the secret "
                        + "reference of its password"),
                problems(P + "mail.host=smtp.customer.example", P + "mail.username=u",
                        P + "mail.from-address=n@customer.example"));
        assertEquals(List.of(P + "mail.from-address: not a valid e-mail address"),
                problems(P + "mail.host=smtp.customer.example", P + "mail.from-address=notifications"));
        assertEquals(List.of(P + "mail.read-timeout: must be positive"),
                problems(concat(GOOD_MAIL, P + "mail.read-timeout=0s")));
    }

    @Test
    void aSecretPutWhereItsReferenceGoesIsRefusedAndNotRepeated() {
        String webhook = "https://prod-12.westeurope.logic.azure.com/workflows/abc/triggers/manual?sig=SECRET-SIG-1";
        List<String> problems = problems(P + "teams.ops-teams=" + webhook, P + "slack.ops-slack=xoxb SECRET slack");
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.get(0).startsWith(P + "teams.ops-teams: not a valid secret reference"), problems.get(0));
        assertTrue(problems.get(0).contains("never the webhook address itself"));
        assertTrue(problems.get(0).contains("looks like an address"));
        assertTrue(problems.get(1).startsWith(P + "slack.ops-slack: not a valid secret reference"), problems.get(1));

        List<String> mail = problems(concat(GOOD_MAIL, P + "mail.password-secret=Pa55 SECRET word"));
        assertEquals(List.of(P + "mail.password-secret: not a valid secret reference (letters, digits and . _ : / -, "
                + "no \"//\"); give the path of the secret that holds the mail password, never the mail password "
                + "itself"), mail);

        NotificationsConfigException e = assertThrows(NotificationsConfigException.class,
                () -> config(concat(GOOD_MAIL, P + "mail.password-secret=Pa55 SECRET word",
                        P + "teams.ops-teams=" + webhook)));
        assertFalse(e.getMessage().contains("SECRET"), e.getMessage());
        FailureAnalysis analysis = new NotificationsFailureAnalyzer().analyze(new IllegalStateException(e));
        assertFalse(analysis.getDescription().contains("SECRET"));
        assertTrue(analysis.getAction().startsWith("Correct the platform.notifications settings"));
    }

    @Test
    void aDestinationIdNamesOneDestination() {
        assertEquals(List.of(P + "slack.ops: the id is also used under " + P + "teams; each destination id names one "
                + "destination"), problems(P + "teams.ops=notifications/a", P + "slack.ops=notifications/b"));
        assertEquals(List.of(P + "teams.ops: missing the secret reference of the webhook address"),
                problems(P + "teams.ops="));
    }

    @Test
    void retryLeaseAndIntervalsAreChecked() {
        assertEquals(List.of(P + "retry.max-delay: must not be shorter than retry.first-delay (600s), was 60s"),
                problems(P + "retry.first-delay=10m", P + "retry.max-delay=1m"));
        assertEquals(List.of(P + "retry.first-delay: must be at least 1s, was PT0.5S"),
                problems(P + "retry.first-delay=500ms"));
        assertEquals(List.of(P + "retry.give-up-after: must be positive"), problems(P + "retry.give-up-after=0s"));
        assertEquals(List.of(P + "lease: must be longer than the longest send (mail.connect-timeout + "
                        + "mail.read-timeout = 40s), was 30s; otherwise a slow send is taken for a stopped one and sent "
                        + "twice"),
                problems(concat(GOOD_MAIL, P + "lease=30s")));
        assertEquals(List.of(P + "tick-interval: must be at most 5 minutes, was PT1H"),
                problems(P + "tick-interval=1h"));
        assertEquals(List.of(P + "retention: must not be negative"), problems(P + "retention=-1d"));
        assertEquals(List.of(P + "sender.batch-size: must be between 1 and 1000, was 0"),
                problems(P + "sender.batch-size=0"));
    }

    @Test
    void destinationIdsMayHoldDotsAndCapitals() {
        NotificationsConfig c = config(P + "teams.ops.alerts=notifications/a", P + "slack[Ops_Slack]=notifications/b");
        assertEquals(java.util.Set.of(DestinationId.of("ops.alerts")), c.teams().keySet());
        assertEquals(java.util.Set.of(DestinationId.of("Ops_Slack")), c.slack().keySet());
    }

    @Test
    void allProblemsAreListedAtOnce() {
        assertEquals(3, problems(P + "mail.host=smtp.customer.example", P + "tick-interval=0s",
                P + "sender.poll-interval=10m").size());
    }

    @Test
    void theAppRefusesToStartWithAWrongConfig() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(NotificationsAutoConfiguration.class))
                .withUserConfiguration(TestApp.Beans.class)
                .withBean(DataSource.class, () -> Db.migrated().owner)
                .withPropertyValues(P + "mail.host=smtp.customer.example", P + "mail.from-address=n@customer.example",
                        P + "mail.security=starttls-please")
                .run(ctx -> {
                    Throwable failure = WiringTest.rootOf(ctx.getStartupFailure(), NotificationsConfigException.class);
                    assertEquals("NOTIF_SETTINGS_INVALID: Notifications configuration is not valid:\n - " + P
                            + "mail.security: must be STARTTLS, TLS or NONE", failure.getMessage());
                });
    }

    private static String[] concat(String[] a, String... b) {
        String[] out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
