package com.documentplatform.notifications.email;

import com.documentplatform.notifications.Channel;
import com.documentplatform.notifications.ChannelKind;
import com.documentplatform.notifications.FailureCode;
import com.documentplatform.notifications.Message;
import com.documentplatform.notifications.OutgoingMessage;
import com.documentplatform.notifications.Recipient;
import com.documentplatform.notifications.SendResult;
import com.documentplatform.notifications.secret.Secret;
import com.documentplatform.notifications.secret.SecretResolver;
import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.eclipse.angus.mail.smtp.SMTPTransport;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/**
 * Sends e-mail through the customer's own mail server (SMTP, with Jakarta Mail / Eclipse Angus). Host, port, TLS
 * and login come from the {@link MailServerDirectory}; the password is read from the secret store for each attempt.
 *
 * <p><b>One message per recipient</b> (P055): each is addressed to one person only, so nobody sees who else was told.
 * Each message is plain text, with a {@code Message-ID} made from the outbox's idempotency key (so a resend after a crash carries the same id and mail systems can recognise it) and
 * the {@code Auto-Submitted} header, so out-of-office replies are not sent back.
 *
 * <p>Microsoft 365 ({@link MailAuth#OAUTH2_MICROSOFT}): the sender signs in with {@code AUTH XOAUTH2} as the sender
 * mailbox, with a token from {@link MicrosoftTokens} (client-credentials flow); no password login is tried.
 *
 * <p>Failures come back as specific codes. Nothing from the server's reply, the login or the addresses is kept:
 * Jakarta Mail's debug output is off and the authentication trace is never enabled.
 */
public final class EmailChannel implements Channel {

    /**
     * Angus Mail logs recipients' addresses to java.util.logging at FINE, and traces the whole SMTP conversation, the
     * message text included, at FINEST, whatever {@code mail.debug} says. That would put addresses and the one-click
     * links' tokens in a log, so both are kept off: before each send, these loggers are set back to INFO if something
     * (for example a "log everything" setting) opened them. Held here so the setting is not lost with the loggers.
     */
    private static final List<java.util.logging.Logger> SMTP_TRACE = List.of(
            java.util.logging.Logger.getLogger("org.eclipse.angus.mail.smtp"),
            java.util.logging.Logger.getLogger("org.eclipse.angus.mail.smtp.protocol"));

    /** Failures that concern one recipient's message; after any other the rest of the recipients would fail alike. */
    private static final Set<FailureCode> RECIPIENT_FAILURES = Set.of(FailureCode.MAIL_RECIPIENT_REFUSED,
            FailureCode.MAIL_TEMPORARILY_REFUSED, FailureCode.MAIL_MESSAGE_REJECTED, FailureCode.SENDER_FAILED);

    private final MailServerDirectory servers;
    private final SecretResolver secrets;
    private final SSLSocketFactory sslSocketFactory;
    private final MicrosoftTokens tokens;

    public EmailChannel(MailServerDirectory servers, SecretResolver secrets) {
        this(servers, secrets, null);
    }

    /**
     * @param sslSocketFactory for a mail server whose certificate is signed by the customer's own CA: a factory that
     *                         trusts it; null to use the Java runtime's trust store. Server names are always checked.
     */
    public EmailChannel(MailServerDirectory servers, SecretResolver secrets, SSLSocketFactory sslSocketFactory) {
        this(servers, secrets, sslSocketFactory, new MicrosoftTokens());
    }

    /**
     * @param tokens where Microsoft 365 tokens are fetched and held (shared with health, which shows their state)
     */
    public EmailChannel(MailServerDirectory servers, SecretResolver secrets, SSLSocketFactory sslSocketFactory,
                        MicrosoftTokens tokens) {
        this.servers = Objects.requireNonNull(servers, "servers");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.sslSocketFactory = sslSocketFactory;
        this.tokens = Objects.requireNonNull(tokens, "tokens");
    }

    @Override
    public ChannelKind kind() {
        return ChannelKind.EMAIL;
    }

    @Override
    public SendResult send(OutgoingMessage out) {
        Optional<MailServer> found = servers.find(out.tenant(), out.destination());
        if (found.isEmpty()) {
            return SendResult.failed(FailureCode.DESTINATION_NOT_CONFIGURED);
        }
        MailServer server = found.get();
        if (out.recipients().isEmpty()) {
            return SendResult.failed(FailureCode.NO_RECIPIENTS);
        }
        String password = null;
        if (server.oauth() != null) {
            MicrosoftTokens.Fetch token = tokens.token(server.oauth(), secrets,
                    server.connectTimeout().plus(server.readTimeout()));
            if (!token.ok()) {
                return SendResult.failed(token.failure(), token.retryAfter());
            }
            password = token.token().reveal(); // XOAUTH2 sends the token where a password would go
        } else if (server.username() != null) {
            Optional<Secret> secret;
            try {
                secret = secrets.resolve(server.password());
            } catch (RuntimeException storeDown) {
                return SendResult.failed(FailureCode.SECRET_UNAVAILABLE);
            }
            if (secret.isEmpty()) {
                return SendResult.failed(FailureCode.SECRET_NOT_FOUND);
            }
            password = secret.get().reveal();
        }

        for (java.util.logging.Logger trace : SMTP_TRACE) {
            if (trace.isLoggable(java.util.logging.Level.FINE)) {
                trace.setLevel(java.util.logging.Level.INFO);
            }
        }
        Session session = Session.getInstance(properties(server));
        FailureCode refused = null;
        int sent = 0;
        Transport transport = null;
        try {
            for (Recipient person : out.recipients().stream().sorted(Comparator.comparing(Recipient::userId)
                    .thenComparing(Recipient::emailAddress)).toList()) {
                MimeMessage mail;
                try {
                    mail = build(session, server, out, person);
                } catch (MessagingException | UnsupportedEncodingException badAddress) {
                    refused = worse(refused, FailureCode.MAIL_RECIPIENT_REFUSED);
                    continue;
                }
                if (transport == null || !transport.isConnected()) {
                    close(transport);
                    transport = null;
                    try {
                        transport = session.getTransport(protocol(server));
                        transport.connect(server.host(), server.port(), server.username(), password);
                    } catch (MessagingException e) {
                        return SendResult.failed(failure(e, transport, server));
                    }
                }
                try {
                    transport.sendMessage(mail, mail.getAllRecipients());
                    sent++;
                } catch (MessagingException e) {
                    FailureCode code = failure(e, transport, server);
                    if (!RECIPIENT_FAILURES.contains(code)) {
                        return SendResult.failed(code); // the server's fault: the same for everyone left
                    }
                    refused = worse(refused, code);
                }
            }
        } finally {
            close(transport);
        }
        if (refused == null) {
            return SendResult.ok();
        }
        // Some got it: never resend the whole message.
        return SendResult.failed(sent > 0 ? worse(refused, FailureCode.MAIL_RECIPIENT_REFUSED) : refused);
    }

    /** The permanent one of two failures, the first one otherwise. */
    private static FailureCode worse(FailureCode first, FailureCode second) {
        return first == null || (first.retryable() && !second.retryable()) ? second : first;
    }

    private FailureCode failure(MessagingException e, Transport transport, MailServer server) {
        if (server.oauth() == null) {
            return classify(e, transport);
        }
        FailureCode code = classifyMicrosoft(e, transport);
        if (code == FailureCode.MAIL_OAUTH_SEND_AS_NOT_ALLOWED || code == FailureCode.MAIL_SMTP_AUTH_DISABLED) {
            tokens.invalidate(server.oauth()); // a permission granted later is only in a new token
        }
        return code;
    }

    private static String protocol(MailServer server) {
        return server.security() == MailSecurity.TLS ? "smtps" : "smtp";
    }

    private Properties properties(MailServer server) {
        String p = "mail." + protocol(server) + ".";
        Properties props = new Properties();
        props.put("mail.transport.protocol", protocol(server));
        props.put("mail.debug", "false");
        props.put("mail.debug.auth", "false"); // never put the login exchange in a trace
        props.put(p + "host", server.host());
        props.put(p + "port", Integer.toString(server.port()));
        props.put(p + "auth", Boolean.toString(server.username() != null));
        if (server.oauth() != null) {
            props.put(p + "auth.mechanisms", "XOAUTH2"); // the token, never a password login
        }
        props.put(p + "from", server.fromAddress());
        props.put(p + "connectiontimeout", Long.toString(server.connectTimeout().toMillis()));
        props.put(p + "timeout", Long.toString(server.readTimeout().toMillis()));
        props.put(p + "writetimeout", Long.toString(server.readTimeout().toMillis()));
        if (server.security() == MailSecurity.STARTTLS) {
            props.put(p + "starttls.enable", "true");
            props.put(p + "starttls.required", "true");
        }
        if (server.security() != MailSecurity.NONE) {
            props.put(p + "ssl.checkserveridentity", "true");
            props.put(p + "ssl.protocols", "TLSv1.3 TLSv1.2");
            if (sslSocketFactory != null) {
                props.put(p + "ssl.socketFactory", sslSocketFactory);
            }
        }
        return props;
    }

    private static MimeMessage build(Session session, MailServer server, OutgoingMessage out, Recipient person)
            throws MessagingException, UnsupportedEncodingException {
        String domain = server.fromAddress().substring(server.fromAddress().indexOf('@') + 1);
        String copy = out.recipients().size() == 1 ? "" : "." + UUID.nameUUIDFromBytes(
                (person.userId() + " " + person.emailAddress()).getBytes(StandardCharsets.UTF_8));
        String messageId = "<" + out.idempotencyKey() + copy + "@" + domain + ">";
        MimeMessage mail = new MimeMessage(session) {
            @Override
            protected void updateMessageID() throws MessagingException {
                setHeader("Message-ID", messageId);
            }
        };
        mail.setFrom(server.fromName() == null ? new InternetAddress(server.fromAddress(), true)
                : new InternetAddress(server.fromAddress(), server.fromName(), StandardCharsets.UTF_8.name()));
        mail.setRecipient(jakarta.mail.Message.RecipientType.TO, new InternetAddress(person.emailAddress(), true));
        Message m = out.message();
        mail.setSubject(m.subject(), StandardCharsets.UTF_8.name());
        mail.setText(m.text(), StandardCharsets.UTF_8.name());
        mail.setHeader("Auto-Submitted", "auto-generated");
        mail.setHeader("X-Auto-Response-Suppress", "All");
        mail.saveChanges();
        return mail;
    }

    /** The specific cause, from the exception types and SMTP reply codes only (never their text). */
    static FailureCode classify(MessagingException e, Transport transport) {
        if (e instanceof AuthenticationFailedException) {
            return FailureCode.MAIL_LOGIN_REFUSED;
        }
        if (causedBy(e, SSLException.class) || causedBy(e, GeneralSecurityException.class)
                || (e.getMessage() != null && e.getMessage().contains("STARTTLS"))) {
            return FailureCode.MAIL_TLS_FAILED;
        }
        if (causedBy(e, SocketTimeoutException.class)) {
            return FailureCode.MAIL_TIMEOUT;
        }
        if (e instanceof SMTPSenderFailedException s) {
            return byReplyCode(s.getReturnCode(), FailureCode.MAIL_MESSAGE_REJECTED);
        }
        if (e instanceof SendFailedException s) {
            Address[] invalid = s.getInvalidAddresses();
            Address[] sent = s.getValidSentAddresses();
            if (invalid != null && invalid.length > 0) {
                if (sent != null && sent.length > 0) {
                    return FailureCode.MAIL_RECIPIENT_REFUSED; // some got it: never resend
                }
                return allRecipientRefusalsTemporary(s) ? FailureCode.MAIL_TEMPORARILY_REFUSED
                        : FailureCode.MAIL_RECIPIENT_REFUSED;
            }
            if (s instanceof SMTPSendFailedException f) {
                return byReplyCode(f.getReturnCode(), FailureCode.MAIL_MESSAGE_REJECTED);
            }
        }
        if (causedBy(e, IOException.class)) {
            return FailureCode.MAIL_SERVER_UNREACHABLE;
        }
        if (transport instanceof SMTPTransport smtp && smtp.getLastReturnCode() >= 400) {
            // For example a 421 greeting ("service not available") or a 554 refusing the session.
            return byReplyCode(smtp.getLastReturnCode(), FailureCode.MAIL_MESSAGE_REJECTED);
        }
        if (transport == null || !transport.isConnected()) {
            return FailureCode.MAIL_SERVER_UNREACHABLE;
        }
        return FailureCode.SENDER_FAILED;
    }

    /**
     * Microsoft 365 sign-in: a refused XOAUTH2 login is told apart by Microsoft's reply. "SmtpClientAuthentication is
     * disabled" (or a server that does not offer XOAUTH2 at all) means SMTP AUTH is off for the mailbox; any other
     * refusal, and "SendAsDenied" when sending, means the app is not allowed to send as the mailbox. The reply text is
     * only matched here, never kept.
     */
    static FailureCode classifyMicrosoft(MessagingException e, Transport transport) {
        if (anyMessageContains(e, "SendAsDenied")) {
            return FailureCode.MAIL_OAUTH_SEND_AS_NOT_ALLOWED;
        }
        if (e instanceof AuthenticationFailedException) {
            if (anyMessageContains(e, "SmtpClientAuthentication is disabled")
                    || anyMessageContains(e, "No authentication mech")) {
                return FailureCode.MAIL_SMTP_AUTH_DISABLED;
            }
            return FailureCode.MAIL_OAUTH_SEND_AS_NOT_ALLOWED;
        }
        return classify(e, transport);
    }

    private static boolean anyMessageContains(Throwable e, String text) {
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        for (Throwable t = e; t != null; ) {
            if (t.getMessage() != null && t.getMessage().toLowerCase(java.util.Locale.ROOT).contains(lower)) {
                return true;
            }
            Throwable next = t instanceof MessagingException m && m.getNextException() != null
                    ? m.getNextException() : t.getCause();
            t = next == t ? null : next;
        }
        return false;
    }

    private static FailureCode byReplyCode(int code, FailureCode permanent) {
        return code >= 400 && code < 500 ? FailureCode.MAIL_TEMPORARILY_REFUSED : permanent;
    }

    /** True if every recipient refusal in the chain is a 4xx ("try later") reply. */
    private static boolean allRecipientRefusalsTemporary(MessagingException e) {
        boolean any = false;
        for (Exception next = e; next != null;
             next = next instanceof MessagingException m ? m.getNextException() : null) {
            if (next instanceof SMTPAddressFailedException a) {
                any = true;
                if (a.getReturnCode() < 400 || a.getReturnCode() >= 500) {
                    return false;
                }
            }
        }
        return any;
    }

    private static boolean causedBy(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; ) {
            if (type.isInstance(t)) {
                return true;
            }
            Throwable next = t instanceof MessagingException m && m.getNextException() != null
                    ? m.getNextException() : t.getCause();
            t = next == t ? null : next;
        }
        return false;
    }

    private static void close(Transport transport) {
        if (transport != null) {
            try {
                transport.close();
            } catch (MessagingException ignored) {
                // The message was handed over or the attempt already failed; closing changes nothing.
            }
        }
    }
}
