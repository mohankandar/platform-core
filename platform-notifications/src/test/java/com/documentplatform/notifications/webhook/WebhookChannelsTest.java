package com.documentplatform.notifications.webhook;

import com.documentplatform.notifications.Channel;
import com.documentplatform.notifications.ChannelKind;
import com.documentplatform.notifications.DestinationId;
import com.documentplatform.notifications.FailureCode;
import com.documentplatform.notifications.Message;
import com.documentplatform.notifications.MessageKind;
import com.documentplatform.notifications.OutgoingMessage;
import com.documentplatform.notifications.SendResult;
import com.documentplatform.notifications.secret.Secret;
import com.documentplatform.notifications.secret.SecretRef;
import com.documentplatform.notifications.secret.SecretResolver;
import com.documentplatform.notifications.slack.SlackChannel;
import com.documentplatform.notifications.support.WebhookServer;
import com.documentplatform.notifications.support.WebhookServer.Reply;
import com.documentplatform.notifications.teams.TeamsChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.ServerSocket;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.documentplatform.notifications.support.TestCatalogue.SLACK;
import static com.documentplatform.notifications.support.TestCatalogue.TEAMS;
import static com.documentplatform.notifications.support.TestCatalogue.TENANT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Teams (Workflows webhook, Adaptive Card) and Slack (incoming webhook, Block Kit) against a local HTTP server. */
class WebhookChannelsTest {

    private static final SecretRef TEAMS_REF = SecretRef.of("notifications/t1/ops-teams");
    private static final SecretRef SLACK_REF = SecretRef.of("notifications/t1/ops-slack");
    private static final String TEAMS_SIG = "sig-TEAMS-SECRET-4711";
    private static final String SLACK_TOKEN = "SLACK-SECRET-TOKEN-0815";
    private static final Message MESSAGE = new Message(MessageKind.EVENT, "Files rejected: batch 7",
            List.of("Time: 2026-11-20 10:05 Z", "Files: 3"), URI.create("https://app.customer.example/intake/batch-7"));
    private static final WebhookOptions LOCAL = WebhookOptions.DEFAULT
            .withTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(2)).allowingPlainHttpForTests();

    private WebhookServer server;
    private final Map<SecretRef, String> secrets = new HashMap<>();
    private final SecretResolver resolver = ref -> Optional.ofNullable(secrets.get(ref)).map(Secret::of);

    @BeforeEach
    void start() throws Exception {
        server = new WebhookServer();
        secrets.put(TEAMS_REF, server.base() + "/workflows/abc/triggers/manual/paths/invoke?api-version=1&sig=" + TEAMS_SIG);
        secrets.put(SLACK_REF, server.base() + "/services/T000/B000/" + SLACK_TOKEN);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private TeamsChannel teams(WebhookOptions options) {
        return new TeamsChannel(WebhookDirectory.of(Map.of(TEAMS, TEAMS_REF)), resolver, options);
    }

    private SlackChannel slack(WebhookOptions options) {
        return new SlackChannel(WebhookDirectory.of(Map.of(SLACK, SLACK_REF)), resolver, options);
    }

    private static OutgoingMessage to(DestinationId d, ChannelKind kind) {
        return new OutgoingMessage(TENANT, d, kind, Set.of(), MESSAGE, "m-0123456789abcdef0123456789abcdef01234567");
    }

    private SendResult sendTeams() {
        return teams(LOCAL).send(to(TEAMS, ChannelKind.TEAMS));
    }

    private SendResult sendSlack() {
        return slack(LOCAL).send(to(SLACK, ChannelKind.SLACK));
    }

    @Test
    void teamsGetsAWorkflowsMessageWithOneAdaptiveCard() {
        server.always(Reply.of(202));
        assertTrue(sendTeams().sent());
        WebhookServer.Request r = server.requests().get(0);
        assertEquals("POST", r.method());
        assertEquals("/workflows/abc/triggers/manual/paths/invoke", r.path());
        assertTrue(r.contentType().startsWith("application/json"));
        assertEquals("{\"type\":\"message\",\"attachments\":[{\"contentType\":\"application/vnd.microsoft.card.adaptive\","
                + "\"contentUrl\":null,\"content\":{\"$schema\":\"http://adaptivecards.io/schemas/adaptive-card.json\","
                + "\"type\":\"AdaptiveCard\",\"version\":\"1.4\",\"body\":["
                + "{\"type\":\"TextBlock\",\"text\":\"Files rejected: batch 7\",\"weight\":\"Bolder\",\"size\":\"Medium\",\"wrap\":true},"
                + "{\"type\":\"TextBlock\",\"text\":\"Time: 2026-11-20 10:05 Z\",\"wrap\":true,\"spacing\":\"None\"},"
                + "{\"type\":\"TextBlock\",\"text\":\"Files: 3\",\"wrap\":true,\"spacing\":\"None\"}],"
                + "\"actions\":[{\"type\":\"Action.OpenUrl\",\"title\":\"Open in the app\","
                + "\"url\":\"https://app.customer.example/intake/batch-7\"}]}}]}", r.body());
    }

    @Test
    void slackGetsABlockKitMessageInPlainText() {
        assertTrue(sendSlack().sent());
        WebhookServer.Request r = server.requests().get(0);
        assertEquals("/services/T000/B000/" + SLACK_TOKEN, r.path());
        assertEquals("{\"text\":\"Files rejected: batch 7\",\"blocks\":["
                + "{\"type\":\"header\",\"text\":{\"type\":\"plain_text\",\"text\":\"Files rejected: batch 7\",\"emoji\":false}},"
                + "{\"type\":\"section\",\"text\":{\"type\":\"plain_text\",\"text\":\"Time: 2026-11-20 10:05 Z\\nFiles: 3\",\"emoji\":false}},"
                + "{\"type\":\"actions\",\"elements\":[{\"type\":\"button\",\"text\":{\"type\":\"plain_text\",\"text\":\"Open in the app\",\"emoji\":false},"
                + "\"url\":\"https://app.customer.example/intake/batch-7\"}]}]}", r.body());
    }

    @ParameterizedTest(name = "HTTP {0} -> {1}")
    @CsvSource({
            "400, WEBHOOK_PAYLOAD_REJECTED, false",
            "401, WEBHOOK_REJECTED, false",
            "403, WEBHOOK_REJECTED, false",
            "404, WEBHOOK_ADDRESS_INVALID, false",
            "410, WEBHOOK_ADDRESS_INVALID, false",
            "413, WEBHOOK_PAYLOAD_REJECTED, false",
            "429, WEBHOOK_RATE_LIMITED, true",
            "500, WEBHOOK_SERVER_ERROR, true",
            "502, WEBHOOK_SERVER_ERROR, true",
            "503, WEBHOOK_SERVER_ERROR, true",
            "504, WEBHOOK_TIMEOUT, true",
            "302, WEBHOOK_UNEXPECTED_REPLY, false"})
    void eachHttpReplyHasItsSpecificCode(int status, FailureCode code, boolean retryable) {
        server.always(Reply.of(status));
        for (SendResult r : List.of(sendTeams(), sendSlack())) {
            assertFalse(r.sent());
            assertEquals(code, r.failure());
            assertEquals(retryable, r.failure().retryable());
        }
        assertEquals(2, server.requests().size(), "a redirect is not followed");
    }

    @Test
    void retryAfterInSecondsIsPassedOn() {
        server.always(new Reply(429, Map.of("Retry-After", "120"), 0));
        SendResult r = sendSlack();
        assertEquals(FailureCode.WEBHOOK_RATE_LIMITED, r.failure());
        assertEquals(Duration.ofSeconds(120), r.retryAfter());
    }

    @Test
    void retryAfterAsAnHttpDateIsPassedOn() {
        Instant now = Instant.parse("2026-11-20T10:00:00Z");
        WebhookClient client = new WebhookClient(ChannelKind.SLACK, WebhookDirectory.of(Map.of()), resolver, LOCAL,
                Clock.fixed(now, ZoneOffset.UTC));
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(now.plusSeconds(90), ZoneOffset.UTC));
        assertEquals(Duration.ofSeconds(90), client.result(503, date).retryAfter());
        assertNull(client.result(429, "soon").retryAfter(), "unreadable: the normal backoff applies");
        assertNull(client.result(429, null).retryAfter());
    }

    @Test
    void slowReceiverIsATimeout() {
        server.always(new Reply(200, Map.of(), 3000));
        WebhookOptions quick = LOCAL.withTimeouts(Duration.ofSeconds(2), Duration.ofMillis(300));
        SendResult r = teams(quick).send(to(TEAMS, ChannelKind.TEAMS));
        assertEquals(FailureCode.WEBHOOK_TIMEOUT, r.failure());
        assertTrue(r.failure().retryable());
    }

    @Test
    void nothingListeningIsUnreachable() throws Exception {
        int closed;
        try (ServerSocket s = new ServerSocket(0)) {
            closed = s.getLocalPort();
        }
        secrets.put(SLACK_REF, "http://127.0.0.1:" + closed + "/services/T/B/" + SLACK_TOKEN);
        SendResult r = sendSlack();
        assertEquals(FailureCode.WEBHOOK_UNREACHABLE, r.failure());
        assertTrue(r.failure().retryable());
    }

    @Test
    void retiredOffice365ConnectorAddressesAreRefusedWithoutSending() {
        for (String retired : List.of("https://contoso.webhook.office.com/webhookb2/abc@def/IncomingWebhook/x/y",
                "https://outlook.office.com/webhook/abc/IncomingWebhook/x/y")) {
            secrets.put(TEAMS_REF, retired);
            SendResult r = teams(WebhookOptions.DEFAULT).send(to(TEAMS, ChannelKind.TEAMS));
            assertEquals(FailureCode.WEBHOOK_CONNECTOR_RETIRED, r.failure());
            assertFalse(r.failure().retryable());
        }
        assertTrue(server.requests().isEmpty());
        // Workflows addresses (Logic Apps / Power Platform hosts) are accepted.
        for (String workflows : List.of(
                "https://prod-12.westeurope.logic.azure.com:443/workflows/x/triggers/manual/paths/invoke?sig=s",
                "https://default1.environment.api.powerplatform.com/powerautomate/automations/direct/workflows/x")) {
            assertEquals(Optional.empty(), TeamsChannel.retiredConnector(URI.create(workflows)));
        }
    }

    @Test
    void plainHttpIsRefusedOutsideTests() {
        SendResult r = teams(WebhookOptions.DEFAULT).send(to(TEAMS, ChannelKind.TEAMS));
        assertEquals(FailureCode.WEBHOOK_ADDRESS_NOT_SECURE, r.failure());
        assertTrue(server.requests().isEmpty(), "the token never travelled unencrypted");
    }

    /** xc-public-api-3 (P037): the test switch allows plain http to this machine only, never to another host. */
    @Test
    void theTestSwitchAllowsPlainHttpToThisMachineOnly() {
        for (String other : List.of("http://webhook.corp.example/services/T000/B000/" + SLACK_TOKEN,
                "http://10.1.2.3:8080/services/T000/B000/" + SLACK_TOKEN,
                "http://127.0.0.2:" + URI.create(server.base()).getPort() + "/services/T000/B000/" + SLACK_TOKEN)) {
            secrets.put(SLACK_REF, other);
            assertEquals(FailureCode.WEBHOOK_ADDRESS_NOT_SECURE, sendSlack().failure(), other);
        }
        assertTrue(server.requests().isEmpty(), "the token never travelled unencrypted");
        server.always(Reply.of(200));
        secrets.put(SLACK_REF, server.base() + "/services/T000/B000/" + SLACK_TOKEN);
        assertTrue(sendSlack().sent(), "this machine, with the test switch: allowed");
    }

    @Test
    void addressProblemsAndSecretProblems() {
        secrets.put(SLACK_REF, "not a url SLACK-SECRET-bad");
        assertEquals(FailureCode.WEBHOOK_ADDRESS_INVALID, sendSlack().failure());
        secrets.put(SLACK_REF, "ftp://files.example/SLACK-SECRET");
        assertEquals(FailureCode.WEBHOOK_ADDRESS_INVALID, sendSlack().failure());
        secrets.put(SLACK_REF, "https://user:pw@hooks.example/x");
        assertEquals(FailureCode.WEBHOOK_ADDRESS_INVALID, sendSlack().failure());

        secrets.remove(SLACK_REF);
        assertEquals(FailureCode.SECRET_NOT_FOUND, sendSlack().failure());

        Channel down = new SlackChannel(WebhookDirectory.of(Map.of(SLACK, SLACK_REF)), ref -> {
            throw new IllegalStateException("vault sealed");
        }, LOCAL);
        SendResult r = down.send(to(SLACK, ChannelKind.SLACK));
        assertEquals(FailureCode.SECRET_UNAVAILABLE, r.failure());
        assertTrue(r.failure().retryable());

        assertEquals(FailureCode.DESTINATION_NOT_CONFIGURED,
                slack(LOCAL).send(to(DestinationId.of("elsewhere"), ChannelKind.SLACK)).failure());
        assertTrue(server.requests().isEmpty());
    }

    @Test
    void theWebhookAddressNeverReachesTheResultOrThePayload() {
        server.always(Reply.of(404));
        for (SendResult r : List.of(sendTeams(), sendSlack())) {
            assertFalse(r.toString().contains(TEAMS_SIG) || r.toString().contains(SLACK_TOKEN));
            assertFalse(r.failure().describe(ChannelKind.TEAMS).contains(TEAMS_SIG));
        }
        server.requests().forEach(req -> {
            assertFalse(req.body().contains(TEAMS_SIG) || req.body().contains(SLACK_TOKEN), req.body());
            assertFalse(req.body().contains(server.base()));
        });
    }

    @Test
    void longSlackMessagesAreSplitWithinSlackLimits() {
        List<String> lines = new java.util.ArrayList<>();
        for (int i = 0; i < 400; i++) {
            lines.add("  - item-" + i + ", 2026-11-20 10:05 Z https://app.customer.example/items/item-" + i);
        }
        String json = SlackChannel.payload(new Message(MessageKind.SUMMARY, "Summary: " + "x".repeat(200), lines, null));
        assertTrue(json.contains("\"text\":\"Summary: " + "x".repeat(138) + "...\""), "header cut to 150");
        int sections = json.split("\"type\":\"section\"", -1).length - 1;
        assertTrue(sections > 1 && sections <= 45, "sections: " + sections);
    }

    @Test
    void jsonEscapesEverythingThatCouldBreakOut() {
        assertEquals("\"a\\\"b\\\\c\\n\\u003cscript\\u003e \\u0026 \\u2028\"", Json.write("a\"b\\c\n<script> & \u2028"));
        assertEquals("{\"k\":[1,true,null]}", Json.write(Map.of("k", java.util.Arrays.asList(1, true, null))));
    }
}
