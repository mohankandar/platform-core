package com.documentplatform.notifications.email;

import com.documentplatform.notifications.ChannelKind;
import com.documentplatform.notifications.DestinationId;
import com.documentplatform.notifications.FailureCode;
import com.documentplatform.notifications.Message;
import com.documentplatform.notifications.MessageKind;
import com.documentplatform.notifications.OutgoingMessage;
import com.documentplatform.notifications.Recipient;
import com.documentplatform.notifications.SendResult;
import com.documentplatform.notifications.secret.Secret;
import com.documentplatform.notifications.secret.SecretRef;
import com.documentplatform.notifications.secret.SecretResolver;
import com.documentplatform.notifications.support.ScriptedSmtpServer;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.documentplatform.notifications.support.TestCatalogue.ANN;
import static com.documentplatform.notifications.support.TestCatalogue.CY;
import static com.documentplatform.notifications.support.TestCatalogue.MAIL;
import static com.documentplatform.notifications.support.TestCatalogue.TENANT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The SMTP sender against GreenMail (real delivery) and a scripted server (each failure a mail server can give). */
class EmailChannelTest {

    private static final String PASSWORD = "Mail-Pw-91x-SECRET";
    private static final SecretRef PASSWORD_REF = SecretRef.of("notifications/t1/mail-password");
    private static final SecretResolver SECRETS =
            ref -> ref.equals(PASSWORD_REF) ? Optional.of(Secret.of(PASSWORD)) : Optional.empty();
    private static final Message MESSAGE = new Message(MessageKind.EVENT, "Approval waiting: LN 12345",
            List.of("Time: 2026-11-20 10:05 Z", "Due: 2026-11-21 09:00 Z"),
            URI.create("https://app.customer.example/approvals/LN-12345"));
    private static final String KEY = "m-0123456789abcdef0123456789abcdef01234567";

    private GreenMail greenMail;
    private ScriptedSmtpServer scripted;

    @BeforeEach
    void start() throws Exception {
        greenMail = new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP).dynamicPort())
                .withConfiguration(GreenMailConfiguration.aConfig().withUser("notify@customer.example", "notify", PASSWORD));
        greenMail.start();
        scripted = new ScriptedSmtpServer();
    }

    @AfterEach
    void stop() throws Exception {
        greenMail.stop();
        scripted.close();
    }

    private static OutgoingMessage out() {
        return new OutgoingMessage(TENANT, MAIL, ChannelKind.EMAIL, Set.of(ANN, CY), MESSAGE, KEY);
    }

    private EmailChannel channel(MailServer server) {
        return new EmailChannel(MailServerDirectory.of(Map.of(MAIL, server)), SECRETS);
    }

    private MailServer greenMailServer(String username, SecretRef password) {
        int port = greenMail.getSmtp().getPort();
        return (username == null ? MailServer.relay("127.0.0.1", port, MailSecurity.NONE, "notifications@customer.example")
                : MailServer.withLogin("127.0.0.1", port, MailSecurity.NONE, username, password,
                "notifications@customer.example")).withFromName("Notifications");
    }

    private MailServer scriptedServer(MailSecurity security) {
        return MailServer.withLogin("127.0.0.1", scripted.port(), security, "notify", PASSWORD_REF,
                        "notifications@customer.example")
                .withTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    private SendResult sendScripted() {
        return channel(scriptedServer(MailSecurity.NONE)).send(out());
    }

    @Test
    void deliversThePlainTextMessageWithItsIdempotencyKeyAsMessageId() throws Exception {
        SendResult r = channel(greenMailServer("notify", PASSWORD_REF)).send(out());
        assertTrue(r.sent(), String.valueOf(r.failure()));

        MimeMessage[] received = greenMail.getReceivedMessages();
        assertEquals(2, received.length, "one message per recipient");
        MimeMessage m = received[0];
        assertEquals("Approval waiting: LN 12345", m.getSubject());
        assertEquals(MESSAGE.text(), ((String) m.getContent()).replace("\r\n", "\n").stripTrailing());
        assertTrue(m.getMessageID().startsWith("<" + KEY + "."), m.getMessageID());
        assertEquals("auto-generated", m.getHeader("Auto-Submitted")[0]);
        assertEquals("Notifications <notifications@customer.example>", m.getFrom()[0].toString());
    }

    private static final Recipient BEA = new Recipient("bea", "bea@customer.example");

    /** P055 (LV-24.1): the holders of a role must not learn each other's address from the message. */
    @Test
    void eachRecipientGetsAMessageOfTheirOwn() throws Exception {
        for (Set<Recipient> people : List.of(Set.of(ANN, CY), Set.of(ANN, BEA, CY))) {
            greenMail.purgeEmailFromAllMailboxes();
            SendResult r = channel(greenMailServer("notify", PASSWORD_REF)).send(
                    new OutgoingMessage(TENANT, MAIL, ChannelKind.EMAIL, people, MESSAGE, KEY));
            assertTrue(r.sent(), String.valueOf(r.failure()));

            MimeMessage[] received = greenMail.getReceivedMessages();
            assertEquals(people.size(), received.length);
            Set<String> addressed = new HashSet<>();
            Set<String> ids = new HashSet<>();
            for (MimeMessage m : received) {
                assertEquals(1, m.getAllRecipients().length);
                String own = m.getAllRecipients()[0].toString();
                addressed.add(own);
                ids.add(m.getMessageID());
                ByteArrayOutputStream raw = new ByteArrayOutputStream();
                m.writeTo(raw);
                for (Recipient other : people) {
                    if (!other.emailAddress().equals(own)) {
                        assertFalse(raw.toString(StandardCharsets.UTF_8).contains(other.emailAddress()),
                                "another recipient's address is in the message");
                    }
                }
                assertEquals(MESSAGE.subject(), m.getSubject());
                assertEquals(MESSAGE.text(), ((String) m.getContent()).replace("\r\n", "\n").stripTrailing());
                assertEquals("auto-generated", m.getHeader("Auto-Submitted")[0]);
                assertEquals("All", m.getHeader("X-Auto-Response-Suppress")[0]);
                assertEquals("Notifications <notifications@customer.example>", m.getFrom()[0].toString());
            }
            assertEquals(Set.copyOf(people.stream().map(Recipient::emailAddress).toList()), addressed);
            assertEquals(people.size(), ids.size(), "different messages have different ids");
        }
    }

    @Test
    void oneBadAddressDoesNotStopTheOthers() {
        scripted.rcptReply = address -> address.startsWith("ann@") ? "550 5.1.1 User unknown" : "250 OK";
        SendResult r = channel(scriptedServer(MailSecurity.NONE)).send(
                new OutgoingMessage(TENANT, MAIL, ChannelKind.EMAIL, Set.of(ANN, BEA, CY), MESSAGE, KEY));
        assertFailed(FailureCode.MAIL_RECIPIENT_REFUSED, false, r);
        assertEquals(List.of("To: bea@customer.example", "To: cy@customer.example"),
                scripted.received().stream().filter(l -> l.startsWith("To: ")).sorted().toList());
        assertEquals(1, scripted.received().stream().filter(l -> l.startsWith("AUTH")).count(), "one sign-in");
    }

    @Test
    void relayWithoutLoginNeedsNoSecret() {
        SecretResolver none = ref -> {
            throw new AssertionError("no secret is read for a relay");
        };
        int port = greenMail.getSmtp().getPort();
        EmailChannel relay = new EmailChannel(MailServerDirectory.of(Map.of(MAIL,
                MailServer.relay("127.0.0.1", port, MailSecurity.NONE, "notifications@customer.example"))), none);
        assertTrue(relay.send(out()).sent());
    }

    @Test
    void wrongPasswordIsLoginRefused() {
        SecretRef wrong = SecretRef.of("notifications/t1/wrong");
        EmailChannel c = new EmailChannel(MailServerDirectory.of(Map.of(MAIL, greenMailServer("notify", wrong))),
                ref -> Optional.of(Secret.of("not-the-password")));
        assertFailed(FailureCode.MAIL_LOGIN_REFUSED, false, c.send(out()));
    }

    @Test
    void scriptedLoginRefusal() {
        scripted.authReply = "535 5.7.8 Authentication credentials invalid";
        assertFailed(FailureCode.MAIL_LOGIN_REFUSED, false, sendScripted());
    }

    @Test
    void serverNotListeningIsUnreachable() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        MailServer server = MailServer.withLogin("127.0.0.1", closedPort, MailSecurity.NONE, "notify", PASSWORD_REF,
                "notifications@customer.example").withTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(2));
        assertFailed(FailureCode.MAIL_SERVER_UNREACHABLE, true, channel(server).send(out()));
    }

    @Test
    void serverThatNeverAnswersIsATimeout() {
        scripted.silent = true;
        MailServer server = scriptedServer(MailSecurity.NONE).withTimeouts(Duration.ofSeconds(2), Duration.ofMillis(500));
        assertFailed(FailureCode.MAIL_TIMEOUT, true, channel(server).send(out()));
    }

    @Test
    void starttlsRequiredButNotOfferedIsTlsFailed() {
        assertFailed(FailureCode.MAIL_TLS_FAILED, false, channel(scriptedServer(MailSecurity.STARTTLS)).send(out()));
    }

    @Test
    void implicitTlsToAPlainServerIsTlsFailed() {
        assertFailed(FailureCode.MAIL_TLS_FAILED, false, channel(scriptedServer(MailSecurity.TLS)).send(out()));
    }

    @Test
    void untrustedCertificateIsTlsFailed() throws Exception {
        GreenMail smtps = new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTPS).dynamicPort());
        smtps.start();
        try {
            MailServer server = MailServer.relay("127.0.0.1", smtps.getSmtps().getPort(), MailSecurity.TLS,
                    "notifications@customer.example").withTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(2));
            assertFailed(FailureCode.MAIL_TLS_FAILED, false, channel(server).send(out()));
        } finally {
            smtps.stop();
        }
    }

    @Test
    void permanentlyRefusedRecipientIsReportedAndNotRetried() {
        scripted.rcptReply = address -> "550 5.1.1 User unknown";
        assertFailed(FailureCode.MAIL_RECIPIENT_REFUSED, false, sendScripted());
    }

    @Test
    void oneRefusedRecipientStillDeliversToTheOthersAndIsNotRetried() {
        scripted.rcptReply = address -> address.startsWith("cy@") ? "550 5.1.1 User unknown" : "250 OK";
        assertFailed(FailureCode.MAIL_RECIPIENT_REFUSED, false, sendScripted());
        assertTrue(scripted.received().stream().anyMatch(l -> l.equals("Subject: Approval waiting: LN 12345")),
                "the accepted recipient got the message");
    }

    @Test
    void temporarilyRefusedRecipientIsRetried() {
        scripted.rcptReply = address -> "450 4.2.1 Mailbox busy, try later";
        assertFailed(FailureCode.MAIL_TEMPORARILY_REFUSED, true, sendScripted());
    }

    @Test
    void messageRejectedAfterDataIsPermanent() {
        scripted.endOfDataReply = "554 5.7.1 Message rejected by policy";
        assertFailed(FailureCode.MAIL_MESSAGE_REJECTED, false, sendScripted());
    }

    @Test
    void temporaryRejectionAfterDataIsRetried() {
        scripted.endOfDataReply = "451 4.3.0 Try again later";
        assertFailed(FailureCode.MAIL_TEMPORARILY_REFUSED, true, sendScripted());
    }

    @Test
    void senderAddressRefused() {
        scripted.mailFromReply = "553 5.7.1 Sender not allowed";
        assertFailed(FailureCode.MAIL_MESSAGE_REJECTED, false, sendScripted());
    }

    @Test
    void serviceNotAvailableGreetingIsTemporary() {
        scripted.greeting = "421 4.3.2 Service not available, closing channel";
        assertFailed(FailureCode.MAIL_TEMPORARILY_REFUSED, true, sendScripted());
    }

    @Test
    void secretMissingOrStoreDownOrNoServer() {
        MailServer missing = MailServer.withLogin("127.0.0.1", scripted.port(), MailSecurity.NONE, "notify",
                SecretRef.of("notifications/t1/nothing-here"), "notifications@customer.example");
        assertFailed(FailureCode.SECRET_NOT_FOUND, false, channel(missing).send(out()));

        EmailChannel storeDown = new EmailChannel(MailServerDirectory.of(Map.of(MAIL, scriptedServer(MailSecurity.NONE))),
                ref -> {
                    throw new IllegalStateException("vault sealed");
                });
        assertFailed(FailureCode.SECRET_UNAVAILABLE, true, storeDown.send(out()));

        EmailChannel noServer = new EmailChannel(MailServerDirectory.of(Map.of(DestinationId.of("other"),
                scriptedServer(MailSecurity.NONE))), SECRETS);
        assertFailed(FailureCode.DESTINATION_NOT_CONFIGURED, false, noServer.send(out()));
        assertTrue(scripted.received().isEmpty(), "nothing was sent");
    }

    @Test
    void passwordGoesOnlyToTheServerNeverIntoTheResult() {
        SendResult r = sendScripted();
        assertTrue(r.sent());
        assertFalse(r.toString().contains(PASSWORD));
        assertTrue(scripted.received().stream().anyMatch(l -> l.startsWith("AUTH")), "it did log in");
    }

    @Test
    void mailServerSettingsAreChecked() {
        assertThrowsIae(() -> MailServer.withLogin("bad host", 25, MailSecurity.NONE, "u", PASSWORD_REF, "a@b"));
        assertThrowsIae(() -> MailServer.withLogin("h", 0, MailSecurity.NONE, "u", PASSWORD_REF, "a@b"));
        assertThrowsIae(() -> new MailServer("h", 25, MailSecurity.NONE, "u", null, "a@b", null,
                Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThrowsIae(() -> MailServer.relay("h", 25, MailSecurity.NONE, "not-an-address"));
        IllegalArgumentException e = assertThrowsIae(() -> SecretRef.of("https://hooks.example/T0/B0/xyzSECRET"));
        assertFalse(e.getMessage().contains("xyzSECRET"), "a refused reference is not repeated");
    }

    private static IllegalArgumentException assertThrowsIae(Runnable r) {
        return org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, r::run);
    }

    private static void assertFailed(FailureCode code, boolean retryable, SendResult r) {
        assertFalse(r.sent(), "expected " + code);
        assertEquals(code, r.failure());
        assertEquals(retryable, r.failure().retryable());
        assertNull(r.retryAfter());
    }
}
