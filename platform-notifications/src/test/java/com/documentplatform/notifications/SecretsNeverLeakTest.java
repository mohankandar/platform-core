package com.documentplatform.notifications;

import com.documentplatform.notifications.jdbc.NotificationStoreException;
import com.documentplatform.notifications.support.LogCapture;
import com.documentplatform.notifications.support.Pg;
import com.documentplatform.notifications.support.Stack;
import com.documentplatform.notifications.support.TestCatalogue;
import com.documentplatform.notifications.support.WebhookServer.Reply;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.documentplatform.notifications.support.TestCatalogue.APPROVAL;
import static com.documentplatform.notifications.support.TestCatalogue.E1;
import static com.documentplatform.notifications.support.TestCatalogue.MAIL;
import static com.documentplatform.notifications.support.TestCatalogue.SLACK;
import static com.documentplatform.notifications.support.TestCatalogue.TEAMS;
import static com.documentplatform.notifications.support.TestCatalogue.TENANT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mail passwords and webhook addresses (which carry tokens) never reach a log, a delivery report, an exception, a
 * message or a table — checked on captured logs at every level, Jakarta Mail's own logging included, across successful
 * sends, every kind of failure and retries.
 */
class SecretsNeverLeakTest {

    private static final String WRONG_PASSWORD = "Wrong-Pw-SECRET-77";
    private static final String BAD_URL = "not a url with SECRET-TOKEN-bad";
    private static final String RETIRED = "https://contoso.webhook.office.com/webhookb2/RETIRED-SECRET-x/IncomingWebhook/y";

    @Test
    void noSecretInLogsReportsMessagesOrTables() throws Exception {
        List<String> secrets = List.of(Stack.MAIL_PASSWORD, Stack.TEAMS_SIG, Stack.SLACK_TOKEN, WRONG_PASSWORD,
                "SECRET-TOKEN-bad", "RETIRED-SECRET-x");
        List<Object> reports = new ArrayList<>();
        List<String> sentText = new ArrayList<>();
        List<String> recipients = new ArrayList<>();
        String logs;
        String tables;
        try (LogCapture capture = new LogCapture(); Stack s = new Stack()) {
            s.routeBusiness(E1, APPROVAL, MAIL, TEAMS, SLACK);
            Notifier n = s.notifier();
            NotificationEvent event = NotificationEvent.builder(APPROVAL, TENANT).entity(E1)
                    .identifier(RecordIdentifier.of("LN 1")).build();

            reports.addAll(s.publish(n, event)); // all three succeed: the password and both tokens are used

            s.secrets.put(Stack.MAIL_REF, WRONG_PASSWORD); // login refused
            s.hooks.then(Reply.of(404)).then(new Reply(429, Map.of("Retry-After", "60"), 0)); // Teams, then Slack
            reports.addAll(s.publish(n, event));
            s.hooks.always(Reply.of(401));
            reports.addAll(s.advance(n, Duration.ofMinutes(1))); // Slack retried, now refused

            s.secrets.put(Stack.TEAMS_REF, RETIRED);
            s.secrets.put(Stack.SLACK_REF, BAD_URL);
            s.hooks.reset();
            reports.addAll(s.publish(n, event));

            s.hooks.requests().forEach(r -> sentText.add(r.path() + " " + r.body()));
            for (var m : s.mail.getReceivedMessages()) {
                sentText.add(m.getSubject() + " " + m.getContent());
                for (var a : m.getAllRecipients()) recipients.add(a.toString());
            }
            tables = Pg.dumpAllTables(s.ds);
            // The receiving side's own logger (the JDK HTTP server standing in for Teams and Slack) logs the request
            // line it got, as Microsoft's or Slack's server would; it is not part of the sending app.
            logs = String.join("\n", capture.records().stream()
                    .filter(r -> !r.contains(" com.sun.net.httpserver ")).toList());

            assertTrue(logs.contains("MAIL_LOGIN_REFUSED") && logs.contains("WEBHOOK_ADDRESS_INVALID")
                    && logs.contains("WEBHOOK_RATE_LIMITED") && logs.contains("WEBHOOK_REJECTED")
                    && logs.contains("WEBHOOK_CONNECTOR_RETIRED"), "the failures were logged, by code");
            assertTrue(capture.records().stream().anyMatch(r -> r.contains("org.eclipse.angus.mail")),
                    "Jakarta Mail's own debug logging was captured too");
        }
        assertEquals(10, reports.size()); // 3 + 3 + the Slack retry + 3
        assertTrue(tables.contains("notif_outbox"));

        // The outbox keeps roles (and user ids), never e-mail addresses: the recipient's address was used for the
        // mail, yet no notif_ table holds it, nor any other address.
        assertTrue(recipients.contains(TestCatalogue.CY.emailAddress()), "the mail went to the approver: " + recipients);
        assertTrue(tables.contains(TestCatalogue.APPROVER.value()), "the outbox keeps the role");
        for (String address : List.of(TestCatalogue.CY.emailAddress(), TestCatalogue.ANN.emailAddress(),
                "notifications@customer.example", "@customer.example")) {
            assertFalse(tables.contains(address), () -> "e-mail address in a table: " + tables.lines()
                    .filter(l -> l.contains(address)).findFirst().orElse(""));
        }

        for (String secret : secrets) {
            assertFalse(logs.contains(secret), () -> "secret in the logs: " + logs.lines()
                    .filter(l -> l.contains(secret)).findFirst().map(l -> l.substring(0, Math.min(300, l.length())))
                    .orElse(""));
            assertFalse(tables.contains(secret), "secret in a table: " + secret);
            reports.forEach(r -> assertFalse(r.toString().contains(secret), "secret in a report"));
            // What was sent: the message bodies (the request path is the webhook address itself).
            sentText.forEach(t -> assertFalse(t.substring(t.indexOf(' ') + 1).contains(secret), "secret in a message"));
        }
    }

    @Test
    void storeErrorsNameTheOperationOnly() {
        PGSimpleDataSource nowhere = new PGSimpleDataSource();
        nowhere.setURL("jdbc:postgresql://127.0.0.1:1/none");
        nowhere.setUser("app");
        nowhere.setPassword("Db-Pw-SECRET-1");
        NotificationStoreException e = assertThrows(NotificationStoreException.class,
                () -> new com.documentplatform.notifications.jdbc.PostgresOutboxStore(nowhere).find("m-1"));
        assertTrue(e.getMessage().startsWith("NOTIF_STORE_FAILED: Notification store failed to read an outbox message"),
                e.getMessage());
        assertFalse(e.getMessage().contains("Db-Pw-SECRET-1"));
    }
}
