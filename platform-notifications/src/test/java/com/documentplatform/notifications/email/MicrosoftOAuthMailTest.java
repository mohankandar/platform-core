package com.documentplatform.notifications.email;

import com.documentplatform.notifications.ChannelKind;
import com.documentplatform.notifications.DeliveryReport;
import com.documentplatform.notifications.DeliveryStatus;
import com.documentplatform.notifications.FailureCode;
import com.documentplatform.notifications.Message;
import com.documentplatform.notifications.MessageKind;
import com.documentplatform.notifications.NotificationEvent;
import com.documentplatform.notifications.Notifier;
import com.documentplatform.notifications.OutgoingMessage;
import com.documentplatform.notifications.RecordIdentifier;
import com.documentplatform.notifications.SendResult;
import com.documentplatform.notifications.secret.Secret;
import com.documentplatform.notifications.secret.SecretRef;
import com.documentplatform.notifications.secret.SecretResolver;
import com.documentplatform.notifications.support.FakeTokenEndpoint;
import com.documentplatform.notifications.support.LogCapture;
import com.documentplatform.notifications.support.ScriptedSmtpServer;
import com.documentplatform.notifications.support.Stack;
import com.documentplatform.notifications.support.TestCatalogue;
import com.documentplatform.notifications.support.TestCertificates;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.documentplatform.notifications.support.TestCatalogue.ANN;
import static com.documentplatform.notifications.support.TestCatalogue.APPROVAL;
import static com.documentplatform.notifications.support.TestCatalogue.CY;
import static com.documentplatform.notifications.support.TestCatalogue.E1;
import static com.documentplatform.notifications.support.TestCatalogue.MAIL;
import static com.documentplatform.notifications.support.TestCatalogue.TENANT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Microsoft 365 sign-in (OAuth 2.0 client credentials, SMTP AUTH XOAUTH2) against a local fake token endpoint and the
 * scripted SMTP server.
 */
class MicrosoftOAuthMailTest {

    static final String DIRECTORY = "11111111-2222-3333-4444-555555555555";
    static final String CLIENT = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    static final String MAILBOX = "notifications@customer.example";
    static final String CLIENT_SECRET = "Client-Secret-Value-8f2c-SECRET";
    static final SecretRef SECRET_REF = SecretRef.of("notifications/t1/m365-app-secret");
    static final SecretRef CERT_REF = SecretRef.of("notifications/t1/m365-app-certificate");
    static final Message MESSAGE = new Message(MessageKind.EVENT, "Approval waiting: LN 12345",
            List.of("Time: 2026-11-20 10:05 Z"), URI.create("https://app.customer.example/approvals/LN-12345"));

    @TempDir
    Path dir;

    private FakeTokenEndpoint entra;
    private ScriptedSmtpServer smtp;
    private final Map<SecretRef, String> store = new ConcurrentHashMap<>();
    private final SecretResolver secrets = ref -> Optional.ofNullable(store.get(ref)).map(Secret::of);
    private final TestCatalogue.MovingClock clock = new TestCatalogue.MovingClock(Instant.now());
    private MicrosoftTokens tokens;

    @BeforeEach
    void start() throws Exception {
        entra = new FakeTokenEndpoint();
        smtp = new ScriptedSmtpServer();
        smtp.extensions = List.of("AUTH LOGIN XOAUTH2");
        store.put(SECRET_REF, CLIENT_SECRET);
        tokens = new MicrosoftTokens(clock, Duration.ofSeconds(2));
    }

    @AfterEach
    void stop() throws Exception {
        entra.close();
        smtp.close();
    }

    private MicrosoftOAuth oauth(MicrosoftOAuth.CredentialType type, SecretRef ref) {
        return new MicrosoftOAuth(DIRECTORY, CLIENT, type, ref, entra.authority());
    }

    /** Microsoft 365 settings, pointed at the scripted server (plain, as it has no TLS). */
    private MailServer server(MicrosoftOAuth oauth) {
        return MailServer.microsoft365(MAILBOX, oauth).withEndpoint("127.0.0.1", smtp.port(), MailSecurity.NONE)
                .withTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    private EmailChannel channel(MailServer server) {
        return new EmailChannel(MailServerDirectory.of(Map.of(MAIL, server)), secrets, null, tokens);
    }

    private EmailChannel secretChannel() {
        return channel(server(oauth(MicrosoftOAuth.CredentialType.SECRET, SECRET_REF)));
    }

    private static OutgoingMessage out() {
        return new OutgoingMessage(TENANT, MAIL, ChannelKind.EMAIL, Set.of(ANN, CY), MESSAGE,
                "m-0123456789abcdef0123456789abcdef01234567");
    }

    /** The XOAUTH2 string the client sent, decoded. */
    private List<String> xoauth2Logins() {
        List<String> out = new ArrayList<>();
        for (String line : smtp.received()) {
            if (line.toUpperCase().startsWith("AUTH XOAUTH2 ")) {
                out.add(new String(Base64.getDecoder().decode(line.substring(13).trim()), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static void assertFailed(FailureCode code, boolean retryable, SendResult r) {
        assertFalse(r.sent(), "expected " + code);
        assertEquals(code, r.failure());
        assertEquals(retryable, r.failure().retryable(), code + " retryable");
    }

    @Test
    void sendsAsTheMailboxWithXoauth2AndKeepsTheTokenUntilShortlyBeforeItExpires() {
        entra.then(FakeTokenEndpoint.token("tok-ONE-SECRET", 3600)).then(FakeTokenEndpoint.token("tok-TWO-SECRET", 3600));
        EmailChannel channel = secretChannel();

        assertTrue(channel.send(out()).sent());
        assertTrue(channel.send(out()).sent());
        assertEquals(1, entra.requests().size(), "the token is kept");
        FakeTokenEndpoint.Request r = entra.requests().get(0);
        assertEquals("/" + DIRECTORY + "/oauth2/v2.0/token", r.path());
        assertEquals(Map.of("client_id", CLIENT, "scope", "https://outlook.office365.com/.default",
                "grant_type", "client_credentials", "client_secret", CLIENT_SECRET), r.form());
        assertEquals(List.of("user=" + MAILBOX + "\u0001auth=Bearer tok-ONE-SECRET\u0001\u0001",
                "user=" + MAILBOX + "\u0001auth=Bearer tok-ONE-SECRET\u0001\u0001"), xoauth2Logins());
        assertTrue(smtp.received().stream().noneMatch(l -> l.toUpperCase().startsWith("AUTH LOGIN")
                || l.toUpperCase().startsWith("AUTH PLAIN")), "no password login is tried");
        assertTrue(smtp.received().stream().anyMatch(l -> l.equals("MAIL FROM:<" + MAILBOX + ">")));

        MicrosoftTokens.Status valid = tokens.status(oauth(MicrosoftOAuth.CredentialType.SECRET, SECRET_REF));
        assertEquals(MicrosoftTokens.State.TOKEN_VALID, valid.state());

        clock.advance(Duration.ofMinutes(54));
        assertTrue(channel.send(out()).sent());
        assertEquals(1, entra.requests().size(), "still more than 5 minutes left");
        clock.advance(Duration.ofMinutes(2)); // 4 minutes left: refreshed before it expires
        assertTrue(channel.send(out()).sent());
        assertEquals(2, entra.requests().size());
        assertTrue(xoauth2Logins().get(xoauth2Logins().size() - 1).contains("Bearer tok-TWO-SECRET"));
    }

    @Test
    void aShortLivedTokenIsRefreshedAtHalfItsLifetime() {
        entra.then(FakeTokenEndpoint.token("tok-SHORT-SECRET", 120)).then(FakeTokenEndpoint.token("tok-NEXT-SECRET", 120));
        EmailChannel channel = secretChannel();
        assertTrue(channel.send(out()).sent());
        clock.advance(Duration.ofSeconds(59));
        assertTrue(channel.send(out()).sent());
        assertEquals(1, entra.requests().size());
        clock.advance(Duration.ofSeconds(2));
        assertTrue(channel.send(out()).sent());
        assertEquals(2, entra.requests().size());
    }

    @Test
    void theCertificateCredentialSendsASignedClientAssertion() throws Exception {
        TestCertificates.Credential cert = TestCertificates.make(dir, "m365app", 0, 365);
        store.put(CERT_REF, cert.pem());
        entra.then(FakeTokenEndpoint.token("tok-CERT-SECRET", 3600));
        MicrosoftOAuth oauth = oauth(MicrosoftOAuth.CredentialType.CERTIFICATE, CERT_REF);
        assertTrue(channel(server(oauth)).send(out()).sent());

        Map<String, String> form = entra.requests().get(0).form();
        assertNull(form.get("client_secret"));
        assertEquals("urn:ietf:params:oauth:client-assertion-type:jwt-bearer", form.get("client_assertion_type"));
        String[] jwt = form.get("client_assertion").split("\\.");
        assertEquals(3, jwt.length);
        Signature rs256 = Signature.getInstance("SHA256withRSA");
        rs256.initVerify(cert.certificate().getPublicKey());
        rs256.update((jwt[0] + "." + jwt[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(rs256.verify(Base64.getUrlDecoder().decode(jwt[2])), "signed with the certificate's key");
        String header = new String(Base64.getUrlDecoder().decode(jwt[0]), StandardCharsets.UTF_8);
        String claims = new String(Base64.getUrlDecoder().decode(jwt[1]), StandardCharsets.UTF_8);
        String thumbprint = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-1").digest(cert.certificate().getEncoded()));
        assertTrue(header.contains("\"alg\":\"RS256\"") && header.contains("\"x5t\":\"" + thumbprint + "\""), header);
        assertTrue(claims.contains("\"aud\":\"" + oauth.tokenEndpoint() + "\""), claims);
        assertTrue(claims.contains("\"iss\":\"" + CLIENT + "\"") && claims.contains("\"sub\":\"" + CLIENT + "\""), claims);
        assertTrue(claims.contains("\"jti\":\"") && claims.contains("\"exp\":"), claims);
    }

    @Test
    void eachTokenEndpointFailureHasItsCode() throws Exception {
        record Case(FakeTokenEndpoint.Reply reply, FailureCode code, boolean retryable) {
        }
        List<Case> cases = List.of(
                new Case(FakeTokenEndpoint.error(401, "invalid_client", 7000215), FailureCode.MAIL_OAUTH_CREDENTIAL_REFUSED, false),
                new Case(FakeTokenEndpoint.error(401, "invalid_client", 7000222), FailureCode.MAIL_OAUTH_CREDENTIAL_REFUSED, false),
                new Case(FakeTokenEndpoint.error(400, "unauthorized_client", 700016), FailureCode.MAIL_OAUTH_APP_UNKNOWN, false),
                new Case(FakeTokenEndpoint.error(400, "invalid_request", 90002), FailureCode.MAIL_OAUTH_APP_UNKNOWN, false),
                new Case(FakeTokenEndpoint.error(400, "invalid_scope", 70011), FailureCode.MAIL_OAUTH_TOKEN_REFUSED, false),
                new Case(new FakeTokenEndpoint.Reply(503, "", Map.of()), FailureCode.MAIL_OAUTH_TOKEN_UNREACHABLE, true),
                new Case(new FakeTokenEndpoint.Reply(429, "", Map.of()), FailureCode.MAIL_OAUTH_TOKEN_UNREACHABLE, true),
                new Case(new FakeTokenEndpoint.Reply(200, "<html>proxy login</html>", Map.of()), FailureCode.MAIL_OAUTH_UNEXPECTED_REPLY, false),
                new Case(new FakeTokenEndpoint.Reply(200, "{\"token_type\":\"Bearer\"}", Map.of()), FailureCode.MAIL_OAUTH_UNEXPECTED_REPLY, false),
                new Case(new FakeTokenEndpoint.Reply(302, "", Map.of("Location", "https://elsewhere.example/")), FailureCode.MAIL_OAUTH_UNEXPECTED_REPLY, false));
        EmailChannel channel = secretChannel();
        for (Case c : cases) {
            entra.then(c.reply());
            SendResult r = channel.send(out());
            assertFailed(c.code(), c.retryable(), r);
        }
        assertTrue(smtp.received().isEmpty(), "no token, so the mail server is never contacted");

        entra.then(new FakeTokenEndpoint.Reply(503, "", Map.of("Retry-After", "30")));
        assertEquals(Duration.ofSeconds(30), channel.send(out()).retryAfter(), "Retry-After is passed on");
        MicrosoftTokens.Status failing = tokens.status(oauth(MicrosoftOAuth.CredentialType.SECRET, SECRET_REF));
        assertEquals(MicrosoftTokens.State.FAILING, failing.state());
        assertEquals(FailureCode.MAIL_OAUTH_TOKEN_UNREACHABLE, failing.failure());
    }

    @Test
    void anUnreachableTokenEndpointIsTemporary() throws Exception {
        MicrosoftOAuth nowhere = new MicrosoftOAuth(DIRECTORY, CLIENT, MicrosoftOAuth.CredentialType.SECRET,
                SECRET_REF, URI.create("http://127.0.0.1:" + freePort()));
        assertFailed(FailureCode.MAIL_OAUTH_TOKEN_UNREACHABLE, true, channel(server(nowhere)).send(out()));
    }

    @Test
    void credentialProblemsFoundBeforeAskingMicrosoft() throws Exception {
        MicrosoftOAuth cert = oauth(MicrosoftOAuth.CredentialType.CERTIFICATE, CERT_REF);
        EmailChannel channel = channel(server(cert));
        assertFailed(FailureCode.SECRET_NOT_FOUND, false, channel.send(out()));

        store.put(CERT_REF, "not a certificate at all");
        assertFailed(FailureCode.MAIL_OAUTH_CREDENTIAL_INVALID, false, channel.send(out()));
        TestCertificates.Credential a = TestCertificates.make(dir, "a", 0, 30);
        TestCertificates.Credential b = TestCertificates.make(dir, "b", 0, 30);
        store.put(CERT_REF, TestCertificates.pem("CERTIFICATE", a.certificate().getEncoded())
                + TestCertificates.pem("PRIVATE KEY", b.key().getEncoded()));
        assertFailed(FailureCode.MAIL_OAUTH_CREDENTIAL_INVALID, false, channel.send(out()));
        store.put(CERT_REF, TestCertificates.make(dir, "old", -60, 30).pem()); // expired 30 days ago
        assertFailed(FailureCode.MAIL_OAUTH_CREDENTIAL_REFUSED, false, channel.send(out()));
        assertTrue(entra.requests().isEmpty(), "none of these reached Microsoft");

        SecretResolver down = ref -> {
            throw new IllegalStateException("vault down");
        };
        EmailChannel storeDown = new EmailChannel(MailServerDirectory.of(Map.of(MAIL, server(cert))), down, null, tokens);
        assertFailed(FailureCode.SECRET_UNAVAILABLE, true, storeDown.send(out()));
    }

    @Test
    void eachMailServerRefusalOfTheTokenHasItsCode() {
        EmailChannel channel = secretChannel();
        smtp.xoauth2Reply = "535 5.7.139 Authentication unsuccessful, SmtpClientAuthentication is disabled for the Mailbox.";
        assertFailed(FailureCode.MAIL_SMTP_AUTH_DISABLED, false, channel.send(out()));

        smtp.xoauth2Reply = "535 5.7.3 Authentication unsuccessful";
        assertFailed(FailureCode.MAIL_OAUTH_SEND_AS_NOT_ALLOWED, false, channel.send(out()));
        smtp.xoauth2Reply = "535 5.7.139 Authentication unsuccessful, the user credentials were incorrect.";
        assertFailed(FailureCode.MAIL_OAUTH_SEND_AS_NOT_ALLOWED, false, channel.send(out()));
        assertEquals(3, entra.requests().size(), "a refused token is dropped: a new one carries a newly granted permission");

        smtp.xoauth2Reply = null;
        smtp.endOfDataReply = "554 5.2.252 SendAsDenied; notifications@customer.example not allowed to send as other@customer.example";
        assertFailed(FailureCode.MAIL_OAUTH_SEND_AS_NOT_ALLOWED, false, channel.send(out()));

        smtp.endOfDataReply = "250 2.0.0 queued";
        smtp.extensions = List.of("AUTH LOGIN PLAIN"); // the server does not offer OAuth
        assertFailed(FailureCode.MAIL_SMTP_AUTH_DISABLED, false, channel.send(out()));

        // The existing mail codes still apply after an accepted token.
        smtp.extensions = List.of("AUTH LOGIN XOAUTH2");
        smtp.rcptReply = address -> "550 5.1.1 unknown recipient";
        assertFailed(FailureCode.MAIL_RECIPIENT_REFUSED, false, channel.send(out()));
        smtp.rcptReply = address -> "250 2.1.5 OK";
        smtp.greeting = "421 4.3.2 service not available";
        assertFailed(FailureCode.MAIL_TEMPORARILY_REFUSED, true, channel.send(out()));
    }

    @Test
    void temporaryFailuresAreRetriedByTheOutboxAndPermanentOnesAreNot() throws Exception {
        try (Stack s = new Stack()) {
            EmailChannel oauthMail = secretChannel();
            Notifier n = s.notifier(s.summaries, s.conditions, List.of(oauthMail));
            entra.then(new FakeTokenEndpoint.Reply(503, "", Map.of()));
            DeliveryReport first = s.publish(n, approval("LN 1")).get(0);
            assertEquals(FailureCode.MAIL_OAUTH_TOKEN_UNREACHABLE, first.failure());
            assertEquals(DeliveryStatus.RETRY_SCHEDULED, first.status());
            assertEquals(DeliveryStatus.SENT, s.advance(n, Duration.ofMinutes(1)).get(0).status(), "retried and sent");

            entra.always = FakeTokenEndpoint.error(401, "invalid_client", 7000215);
            tokens.invalidate(oauth(MicrosoftOAuth.CredentialType.SECRET, SECRET_REF));
            int before = entra.requests().size();
            DeliveryReport refused = s.publish(n, approval("LN 2")).get(0);
            assertEquals(DeliveryStatus.FAILED, refused.status());
            assertEquals(FailureCode.MAIL_OAUTH_CREDENTIAL_REFUSED, refused.failure());
            assertTrue(s.advance(n, Duration.ofHours(2)).isEmpty(), "not retried");
            assertEquals(before + 1, entra.requests().size());
        }
    }

    @Test
    void noTokenSecretOrKeyInAnyOutput() throws Exception {
        TestCertificates.Credential cert = TestCertificates.make(dir, "leak", 0, 30);
        store.put(CERT_REF, cert.pem());
        List<String> outputs = new ArrayList<>();
        String assertion;
        try (LogCapture logs = new LogCapture()) {
            entra.then(FakeTokenEndpoint.token("tok-LEAK-CHECK-SECRET", 3600));
            EmailChannel certChannel = channel(server(oauth(MicrosoftOAuth.CredentialType.CERTIFICATE, CERT_REF)));
            outputs.add(String.valueOf(certChannel.send(out())));
            assertion = entra.requests().get(0).form().get("client_assertion");
            smtp.xoauth2Reply = "535 5.7.3 Authentication unsuccessful";
            outputs.add(String.valueOf(certChannel.send(out())));
            EmailChannel secretChannel = secretChannel();
            entra.then(FakeTokenEndpoint.error(401, "invalid_client", 7000215));
            outputs.add(String.valueOf(secretChannel.send(out())));
            entra.then(FakeTokenEndpoint.token("tok-SECOND-SECRET", 3600));
            outputs.add(String.valueOf(secretChannel.send(out())));
            for (MicrosoftOAuth o : List.of(oauth(MicrosoftOAuth.CredentialType.CERTIFICATE, CERT_REF),
                    oauth(MicrosoftOAuth.CredentialType.SECRET, SECRET_REF))) {
                outputs.add(String.valueOf(tokens.status(o)));
                outputs.add(String.valueOf(tokens.token(o, secrets, Duration.ofSeconds(2))));
            }
            outputs.add(logs.all());
        }
        List<String> secretsToFind = List.of("tok-LEAK-CHECK-SECRET", "tok-SECOND-SECRET", CLIENT_SECRET,
                cert.keyBase64().substring(0, 40), assertion.substring(assertion.lastIndexOf('.') + 1, assertion.lastIndexOf('.') + 30),
                Base64.getEncoder().encodeToString(("user=" + MAILBOX + "\u0001auth=Bearer tok-LEAK-CHECK-SECRET\u0001\u0001")
                        .getBytes(StandardCharsets.UTF_8)), "scripted description");
        for (String text : outputs) {
            for (String secret : secretsToFind) {
                assertFalse(text.contains(secret), "found a secret in: " + text);
            }
        }
        assertTrue(outputs.get(outputs.size() - 1).contains("MAIL_OAUTH_CREDENTIAL_REFUSED"), "codes are logged");
    }

    @Test
    void passwordSignInIsUnchanged() throws Exception {
        try (ScriptedSmtpServer exchange = new ScriptedSmtpServer()) {
            exchange.extensions = List.of("AUTH PLAIN LOGIN XOAUTH2");
            store.put(SecretRef.of("notifications/t1/mail-password"), "Exchange-Pw-SECRET");
            MailServer own = MailServer.withLogin("127.0.0.1", exchange.port(), MailSecurity.NONE, "notify",
                    SecretRef.of("notifications/t1/mail-password"), MAILBOX).withTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(2));
            assertEquals(MailAuth.PASSWORD, own.auth());
            assertTrue(channel(own).send(out()).sent());
            assertTrue(exchange.received().stream().noneMatch(l -> l.toUpperCase().startsWith("AUTH XOAUTH2")));
            assertTrue(entra.requests().isEmpty(), "no token is fetched for a password login");
            exchange.authReply = "535 5.7.139 Authentication unsuccessful, SmtpClientAuthentication is disabled for the Mailbox.";
            assertFailed(FailureCode.MAIL_LOGIN_REFUSED, false, channel(own).send(out()));
        }
    }

    @Test
    void settingsAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> MicrosoftOAuth.of("not a tenant", CLIENT,
                MicrosoftOAuth.CredentialType.SECRET, SECRET_REF));
        assertThrows(IllegalArgumentException.class, () -> MicrosoftOAuth.of(DIRECTORY, "client",
                MicrosoftOAuth.CredentialType.SECRET, SECRET_REF));
        assertEquals("https://login.microsoftonline.com/customer.onmicrosoft.com/oauth2/v2.0/token",
                MicrosoftOAuth.of("customer.onmicrosoft.com", CLIENT, MicrosoftOAuth.CredentialType.SECRET, SECRET_REF)
                        .tokenEndpoint().toString());
        MailServer m365 = MailServer.microsoft365(MAILBOX, MicrosoftOAuth.of(DIRECTORY, CLIENT,
                MicrosoftOAuth.CredentialType.SECRET, SECRET_REF));
        assertEquals("smtp.office365.com", m365.host());
        assertEquals(587, m365.port());
        assertEquals(MailSecurity.STARTTLS, m365.security());
        assertEquals(MailAuth.OAUTH2_MICROSOFT, m365.auth());
        assertThrows(IllegalArgumentException.class, () -> MailServer.microsoft365("not-a-mailbox",
                MicrosoftOAuth.of(DIRECTORY, CLIENT, MicrosoftOAuth.CredentialType.SECRET, SECRET_REF)));
    }

    private static NotificationEvent approval(String id) {
        return NotificationEvent.builder(APPROVAL, TENANT).entity(E1).identifier(RecordIdentifier.of(id)).build();
    }

    private static int freePort() throws Exception {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
