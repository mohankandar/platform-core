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
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * Sends e-mail through the customer's own mail server (SMTP, with Jakarta Mail / Eclipse Angus). Host, port, TLS
 * and login come from the {@link MailServerDirectory}; the password is read from the secret store for each attempt.
 *
 * <p>Each message is plain text, addressed to the holders of the recipient roles, with a {@code Message-ID} made from
 * the outbox's idempotency key (so a resend after a crash carries the same id and mail systems can recognise it) and
 * the {@code Auto-Submitted} header, so out-of-office replies are not sent back.
 *
 * <p>Microsoft 365 ({@link MailAuth#OAUTH2_MICROSOFT}): the sender signs in with {@code AUTH XOAUTH2} as the sender
 * mailbox, with a token from {@link MicrosoftTokens} (client-credentials flow); no password login is tried.
 *
 * <p>Failures come back as specific codes. Nothing from the server's reply, the login or the addresses is kept:
 * Jakarta Mail's debug output is off and the authentication trace is never enabled.
 */
public final class EmailChannel implements Channel {

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

        Session session = Session.getInstance(properties(server));
        MimeMessage mail;
        try {
            mail = build(session, server, out);
        } catch (MessagingException | UnsupportedEncodingException badAddress) {
            return SendResult.failed(FailureCode.MAIL_RECIPIENT_REFUSED);
        }
        Transport transport = null;
        try {
            transport = session.getTransport(protocol(server));
            transport.connect(server.host(), server.port(), server.username(), password);
            transport.sendMessage(mail, mail.getAllRecipients());
            return SendResult.ok();
        } catch (MessagingException e) {
            if (server.oauth() == null) {
                return SendResult.failed(classify(e, transport));
            }
            FailureCode code = classifyMicrosoft(e, transport);
            if (code == FailureCode.MAIL_OAUTH_SEND_AS_NOT_ALLOWED || code == FailureCode.MAIL_SMTP_AUTH_DISABLED) {
                tokens.invalidate(server.oauth()); // a permission granted later is only in a new token
            }
            return SendResult.failed(code);
        } finally {
            close(transport);
        }
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
        // Recipients the server accepts get the message even if it refuses another; the refusal is reported and not
        // retried, so the ones who got it do not get it twice.
        props.put(p + "sendpartial", "true");
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

    private static MimeMessage build(Session session, MailServer server, OutgoingMessage out)
            throws MessagingException, UnsupportedEncodingException {
        String domain = server.fromAddress().substring(server.fromAddress().indexOf('@') + 1);
        String messageId = "<" + out.idempotencyKey() + "@" + domain + ">";
        MimeMessage mail = new MimeMessage(session) {
            @Override
            protected void updateMessageID() throws MessagingException {
                setHeader("Message-ID", messageId);
            }
        };
        mail.setFrom(server.fromName() == null ? new InternetAddress(server.fromAddress(), true)
                : new InternetAddress(server.fromAddress(), server.fromName(), StandardCharsets.UTF_8.name()));
        Address[] to = new Address[out.recipients().size()];
        int i = 0;
        for (Recipient r : out.recipients().stream().sorted((a, b) -> a.userId().compareTo(b.userId())).toList()) {
            to[i++] = new InternetAddress(r.emailAddress(), true);
        }
        mail.setRecipients(jakarta.mail.Message.RecipientType.TO, to);
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
