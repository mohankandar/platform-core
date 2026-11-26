package com.documentplatform.notifications.transport;

import com.documentplatform.notifications.ChannelKind;
import com.documentplatform.notifications.DestinationId;
import com.documentplatform.notifications.FailureCode;
import com.documentplatform.notifications.Message;
import com.documentplatform.notifications.MessageKind;
import com.documentplatform.notifications.OutgoingMessage;
import com.documentplatform.notifications.SendResult;
import com.documentplatform.notifications.TenantId;
import com.documentplatform.notifications.email.MicrosoftOAuth;
import com.documentplatform.notifications.email.MicrosoftTokens;
import com.documentplatform.notifications.secret.Secret;
import com.documentplatform.notifications.secret.SecretRef;
import com.documentplatform.notifications.secret.SecretResolver;
import com.documentplatform.notifications.slack.SlackChannel;
import com.documentplatform.notifications.teams.TeamsChannel;
import com.documentplatform.notifications.webhook.WebhookDirectory;
import com.documentplatform.notifications.webhook.WebhookOptions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P059 part 2 (LV-24.3 step 1): token requests, Teams and Slack through a {@link HttpTransport} (in an app:
 * platform-outbound, wired by platform-notifications-spring when {@code via-outbound} is on). The transport here is a
 * fake that records each call and answers what the test says.
 */
class ViaTransportTest {

    static final String DIRECTORY = "11111111-2222-3333-4444-555555555555";
    static final String CLIENT = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    static final SecretRef APP_SECRET = SecretRef.of("notifications/m365-app-secret");
    static final SecretRef TEAMS_REF = SecretRef.of("notifications/ops-teams");
    static final SecretRef SLACK_REF = SecretRef.of("notifications/ops-slack");
    static final DestinationId TEAMS = DestinationId.of("ops-teams");
    static final DestinationId SLACK = DestinationId.of("ops-slack");
    static final String TEAMS_ADDRESS = "https://hooks.example.net/workflows/x/triggers/manual/paths/invoke?sig=S1";
    static final String SLACK_ADDRESS = "https://hooks.example.net/services/T0/B0/TOKEN-S2";
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-26T10:00:00Z"), ZoneOffset.UTC);
    static final MicrosoftOAuth OAUTH = MicrosoftOAuth.of(DIRECTORY, CLIENT, MicrosoftOAuth.CredentialType.SECRET,
            APP_SECRET);

    private final SecretResolver secrets = ref -> Optional.ofNullable(Map.of(APP_SECRET, "app-SECRET-value",
            TEAMS_REF, TEAMS_ADDRESS, SLACK_REF, SLACK_ADDRESS).get(ref)).map(Secret::of);

    /** Records each call; answers the next scripted reply or failure. */
    static final class FakeTransport implements HttpTransport {
        record Call(URI address, Map<String, String> headers, String body, Duration connect, Duration read) {
        }

        final List<Call> calls = new ArrayList<>();
        Object next = new Reply(200, "{}".getBytes(StandardCharsets.UTF_8), null);

        @Override
        public Reply post(URI address, Map<String, String> headers, byte[] body, Duration connectTimeout,
                          Duration readTimeout) throws Failed {
            calls.add(new Call(address, Map.copyOf(headers), new String(body, StandardCharsets.UTF_8), connectTimeout,
                    readTimeout));
            if (next instanceof Failed f) {
                throw f;
            }
            return (Reply) next;
        }

        FakeTransport answering(int status, String body, String retryAfter) {
            next = new Reply(status, body.getBytes(StandardCharsets.UTF_8), retryAfter);
            return this;
        }

        FakeTransport failing(Failed.Kind kind, String reason) {
            next = new Failed(kind, reason);
            return this;
        }
    }

    private static OutgoingMessage message(DestinationId d, ChannelKind kind) {
        return new OutgoingMessage(TenantId.of("t1"), d, kind, Set.of(), new Message(MessageKind.EVENT,
                "Files rejected: batch 7", List.of("Files: 3"), URI.create("https://app.example/intake/7")),
                "m-0123456789abcdef0123456789abcdef01234567");
    }

    // token requests

    @Test
    void theTokenRequestGoesThroughTheTransport() {
        FakeTransport t = new FakeTransport().answering(200,
                "{\"access_token\":\"tok-SECRET\",\"token_type\":\"Bearer\",\"expires_in\":3600}", null);
        MicrosoftTokens tokens = new MicrosoftTokens(CLOCK, Duration.ofSeconds(3), oauth -> t);
        MicrosoftTokens.Fetch f = tokens.token(OAUTH, secrets, Duration.ofSeconds(7));
        assertTrue(f.ok());
        assertEquals("tok-SECRET", f.token().reveal());
        assertEquals(1, t.calls.size());
        FakeTransport.Call call = t.calls.get(0);
        assertEquals(OAUTH.tokenEndpoint(), call.address());
        assertEquals("application/x-www-form-urlencoded", call.headers().get("Content-Type"));
        assertTrue(call.body().contains("grant_type=client_credentials"), "the same form as the direct path");
        assertTrue(call.body().contains("client_secret=app-SECRET-value"));
        assertEquals(Duration.ofSeconds(3), call.connect());
        assertEquals(Duration.ofSeconds(7), call.read());
        assertTrue(tokens.token(OAUTH, secrets, Duration.ofSeconds(7)).ok());
        assertEquals(1, t.calls.size(), "the token is held as before");
    }

    @Test
    void aRefusedAddressIsReportedAsRefusedByTheOutboundSettingsAndNotRetried() {
        FakeTransport t = new FakeTransport().failing(HttpTransport.Failed.Kind.REFUSED, "OUT_ADDRESS_BLOCKED");
        MicrosoftTokens tokens = new MicrosoftTokens(CLOCK, Duration.ofSeconds(3), oauth -> t);
        MicrosoftTokens.Fetch f = tokens.token(OAUTH, secrets, Duration.ofSeconds(7));
        assertEquals(FailureCode.MAIL_OUTBOUND_REFUSED, f.failure());
        assertFalse(FailureCode.MAIL_OUTBOUND_REFUSED.retryable(), "someone must change the outbound settings");
        assertEquals(MicrosoftTokens.State.FAILING, tokens.status(OAUTH).state());
        assertEquals(FailureCode.MAIL_OUTBOUND_REFUSED, tokens.status(OAUTH).failure());
    }

    @Test
    void anUnreachableOrSlowTokenEndpointIsRetriedAsToday() {
        for (HttpTransport.Failed.Kind kind : List.of(HttpTransport.Failed.Kind.UNREACHABLE,
                HttpTransport.Failed.Kind.TIMEOUT)) {
            FakeTransport t = new FakeTransport().failing(kind, "OUT_CANT_CONNECT");
            MicrosoftTokens tokens = new MicrosoftTokens(CLOCK, Duration.ofSeconds(3), oauth -> t);
            assertEquals(FailureCode.MAIL_OAUTH_TOKEN_UNREACHABLE, tokens.token(OAUTH, secrets,
                    Duration.ofSeconds(7)).failure(), kind.name());
        }
        FakeTransport busy = new FakeTransport().answering(429, "", "17");
        MicrosoftTokens.Fetch f = new MicrosoftTokens(CLOCK, Duration.ofSeconds(3), oauth -> busy)
                .token(OAUTH, secrets, Duration.ofSeconds(7));
        assertEquals(FailureCode.MAIL_OAUTH_TOKEN_UNREACHABLE, f.failure());
        assertEquals(Duration.ofSeconds(17), f.retryAfter());
        FakeTransport refused = new FakeTransport().answering(401, "{\"error\":\"invalid_client\"}", null);
        assertEquals(FailureCode.MAIL_OAUTH_CREDENTIAL_REFUSED, new MicrosoftTokens(CLOCK, Duration.ofSeconds(3),
                oauth -> refused).token(OAUTH, secrets, Duration.ofSeconds(7)).failure());
    }

    @Test
    void aServerWithoutTheTransportKeepsTheDirectPath() {
        FakeTransport t = new FakeTransport();
        MicrosoftOAuth direct = MicrosoftOAuth.of(DIRECTORY, CLIENT, MicrosoftOAuth.CredentialType.SECRET,
                SecretRef.of("notifications/missing"));
        MicrosoftTokens tokens = new MicrosoftTokens(CLOCK, Duration.ofSeconds(3), oauth -> oauth.equals(OAUTH) ? t
                : null);
        assertEquals(FailureCode.SECRET_NOT_FOUND, tokens.token(direct, secrets, Duration.ofSeconds(7)).failure());
        assertTrue(t.calls.isEmpty(), "this server's sign-in is not sent through the transport");
    }

    // Teams and Slack

    private TeamsChannel teams(HttpTransport t) {
        return new TeamsChannel(WebhookDirectory.of(Map.of(TEAMS, TEAMS_REF)), secrets, WebhookOptions.DEFAULT, t);
    }

    private SlackChannel slack(HttpTransport t) {
        return new SlackChannel(WebhookDirectory.of(Map.of(SLACK, SLACK_REF)), secrets, WebhookOptions.DEFAULT, t);
    }

    @Test
    void teamsAndSlackPostThroughTheTransport() {
        FakeTransport t = new FakeTransport();
        assertTrue(teams(t).send(message(TEAMS, ChannelKind.TEAMS)).sent());
        assertTrue(slack(t).send(message(SLACK, ChannelKind.SLACK)).sent());
        assertEquals(List.of(URI.create(TEAMS_ADDRESS), URI.create(SLACK_ADDRESS)),
                t.calls.stream().map(FakeTransport.Call::address).toList());
        assertTrue(t.calls.get(0).body().contains("AdaptiveCard"), "the same Teams payload");
        assertTrue(t.calls.get(1).body().contains("Files rejected: batch 7"));
        assertEquals("application/json; charset=utf-8", t.calls.get(0).headers().get("Content-Type"));
        assertEquals(WebhookOptions.DEFAULT.connectTimeout(), t.calls.get(0).connect());
        assertEquals(WebhookOptions.DEFAULT.requestTimeout(), t.calls.get(0).read());
    }

    @Test
    void aWebhookAddressTheOutboundSettingsRefuseIsReportedClearly() {
        FakeTransport t = new FakeTransport().failing(HttpTransport.Failed.Kind.REFUSED, "OUT_ADDRESS_BLOCKED");
        SendResult r = teams(t).send(message(TEAMS, ChannelKind.TEAMS));
        assertEquals(FailureCode.WEBHOOK_OUTBOUND_REFUSED, r.failure());
        assertFalse(FailureCode.WEBHOOK_OUTBOUND_REFUSED.retryable());
        assertEquals(FailureCode.WEBHOOK_OUTBOUND_REFUSED, slack(t).send(message(SLACK, ChannelKind.SLACK)).failure());
    }

    @Test
    void webhookFailuresKeepTheirCodes() {
        assertEquals(FailureCode.WEBHOOK_UNREACHABLE, teams(new FakeTransport().failing(
                HttpTransport.Failed.Kind.UNREACHABLE, "OUT_CANT_CONNECT")).send(message(TEAMS, ChannelKind.TEAMS))
                .failure());
        assertEquals(FailureCode.WEBHOOK_TIMEOUT, slack(new FakeTransport().failing(
                HttpTransport.Failed.Kind.TIMEOUT, "OUT_TIMEOUT")).send(message(SLACK, ChannelKind.SLACK)).failure());
        SendResult limited = slack(new FakeTransport().answering(429, "", "30")).send(message(SLACK,
                ChannelKind.SLACK));
        assertEquals(FailureCode.WEBHOOK_RATE_LIMITED, limited.failure());
        assertEquals(Duration.ofSeconds(30), limited.retryAfter());
        assertEquals(FailureCode.WEBHOOK_REJECTED, teams(new FakeTransport().answering(403, "", null))
                .send(message(TEAMS, ChannelKind.TEAMS)).failure());
    }

    @Test
    void theFailureNamesNoAddress() {
        HttpTransport.Failed f = new HttpTransport.Failed(HttpTransport.Failed.Kind.REFUSED, "OUT_ADDRESS_BLOCKED");
        assertEquals("REFUSED: OUT_ADDRESS_BLOCKED", f.getMessage());
        HttpTransport.Reply r = new HttpTransport.Reply(200, "tok-SECRET".getBytes(StandardCharsets.UTF_8), null);
        assertFalse(r.toString().contains("SECRET"), r.toString());
    }
}
