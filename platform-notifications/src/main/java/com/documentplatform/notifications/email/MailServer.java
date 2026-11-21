package com.documentplatform.notifications.email;

import com.documentplatform.notifications.secret.SecretRef;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The customer's mail server for one e-mail destination, from the install config. Holds no secret: the password (or,
 * for Microsoft 365, the app credential) is a {@link SecretRef} read from the secret store when it is needed.
 *
 * <p>Two ways to sign in ({@link #auth()}):
 * <ul>
 *   <li>{@link MailAuth#PASSWORD}: a user name and password, or no login for a relay (the customer's own server, for
 *       example Exchange). {@code oauth} is null.</li>
 *   <li>{@link MailAuth#OAUTH2_MICROSOFT}: {@code oauth} is set, {@code username} is the sender mailbox the app signs
 *       in as, and {@code password} is null.</li>
 * </ul>
 *
 * @param username       the login (the sender mailbox for Microsoft 365); null for a relay that takes mail without one
 * @param password       where the password is kept; set exactly when {@code username} is and {@code oauth} is not
 * @param fromAddress    the sender address, for example {@code notifications@customer.example}
 * @param fromName       the display name of the sender; null for none
 * @param connectTimeout how long to wait for the connection (also used for the token endpoint)
 * @param readTimeout    how long to wait for each server reply (also used for the token endpoint)
 * @param oauth          Microsoft 365 sign-in; null for {@link MailAuth#PASSWORD}
 */
public record MailServer(String host, int port, MailSecurity security, String username, SecretRef password,
                         String fromAddress, String fromName, Duration connectTimeout, Duration readTimeout,
                         MicrosoftOAuth oauth) {

    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.:\\[\\]_-]{1,253}");
    private static final Pattern ADDRESS = Pattern.compile("[^@\\s]+@[^@\\s]+");

    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);

    public MailServer {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(security, "security");
        Objects.requireNonNull(fromAddress, "fromAddress");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(readTimeout, "readTimeout");
        if (!HOST.matcher(host).matches()) {
            throw new IllegalArgumentException("Mail server host is not valid");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Mail server port is not valid");
        }
        if (oauth == null) {
            if ((username == null) != (password == null) || (username != null && username.isBlank())) {
                throw new IllegalArgumentException("A login needs both a user name and a password reference");
            }
        } else {
            if (username == null || !ADDRESS.matcher(username).matches()) {
                throw new IllegalArgumentException("Microsoft 365 sign-in needs the sender mailbox address");
            }
            if (password != null) {
                throw new IllegalArgumentException("Microsoft 365 sign-in uses the app credential, not a password");
            }
        }
        if (!ADDRESS.matcher(fromAddress).matches()) {
            throw new IllegalArgumentException("Sender address is not valid");
        }
        if (fromName != null && (fromName.isBlank() || fromName.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("Sender name is not valid");
        }
        if (connectTimeout.isNegative() || connectTimeout.isZero() || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("Timeouts must be positive");
        }
    }

    /** A server signed in to with a password, or a relay (as before Microsoft 365 sign-in existed). */
    public MailServer(String host, int port, MailSecurity security, String username, SecretRef password,
                      String fromAddress, String fromName, Duration connectTimeout, Duration readTimeout) {
        this(host, port, security, username, password, fromAddress, fromName, connectTimeout, readTimeout, null);
    }

    /** A server with a login, default timeouts and no display name. */
    public static MailServer withLogin(String host, int port, MailSecurity security, String username,
                                       SecretRef password, String fromAddress) {
        return new MailServer(host, port, security, username, password, fromAddress, null, DEFAULT_CONNECT_TIMEOUT,
                DEFAULT_READ_TIMEOUT);
    }

    /** A relay without a login, default timeouts and no display name. */
    public static MailServer relay(String host, int port, MailSecurity security, String fromAddress) {
        return new MailServer(host, port, security, null, null, fromAddress, null, DEFAULT_CONNECT_TIMEOUT,
                DEFAULT_READ_TIMEOUT);
    }

    /**
     * Microsoft 365: {@code smtp.office365.com:587} with STARTTLS, signed in as {@code mailbox} with the app's token,
     * sending from the mailbox's address.
     */
    public static MailServer microsoft365(String mailbox, MicrosoftOAuth oauth) {
        return new MailServer(MicrosoftOAuth.SMTP_HOST, MicrosoftOAuth.SMTP_PORT, MailSecurity.STARTTLS, mailbox, null,
                mailbox, null, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT, Objects.requireNonNull(oauth, "oauth"));
    }

    public MailAuth auth() {
        return oauth == null ? MailAuth.PASSWORD : MailAuth.OAUTH2_MICROSOFT;
    }

    public MailServer withTimeouts(Duration connect, Duration read) {
        return new MailServer(host, port, security, username, password, fromAddress, fromName, connect, read, oauth);
    }

    public MailServer withFromName(String name) {
        return new MailServer(host, port, security, username, password, fromAddress, name, connectTimeout, readTimeout,
                oauth);
    }

    /** The same sign-in on another host, port and security (a test server, or a mail gateway in between). */
    public MailServer withEndpoint(String newHost, int newPort, MailSecurity newSecurity) {
        return new MailServer(newHost, newPort, newSecurity, username, password, fromAddress, fromName, connectTimeout,
                readTimeout, oauth);
    }
}
