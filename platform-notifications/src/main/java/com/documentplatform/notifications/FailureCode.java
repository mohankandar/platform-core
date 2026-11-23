package com.documentplatform.notifications;

/**
 * Why a message was not delivered. Specific causes with stable codes, never "an error occurred", and never anything
 * secret: the text is fixed here, so it cannot carry an address, login, webhook link or server reply.
 *
 * <p>{@link #retryable()} says whether the cause can go away by itself (a server that is down, a rate limit): those
 * are retried with backoff. The others need someone to change something (a refused login, a webhook that no longer
 * exists): the outbox stops at once and reports them.
 */
public enum FailureCode implements com.documentplatform.errors.PlatformCode {
    // Mail server
    MAIL_SERVER_UNREACHABLE("Mail server could not be reached", true),
    MAIL_TIMEOUT("Mail server did not answer in time", true),
    MAIL_TLS_FAILED("Secure connection (TLS) to the mail server failed", false),
    MAIL_LOGIN_REFUSED("Mail server refused the login", false),
    MAIL_RECIPIENT_REFUSED("Mail server refused a recipient address", false),
    MAIL_TEMPORARILY_REFUSED("Mail server refused the message for now", true),
    MAIL_MESSAGE_REJECTED("Mail server rejected the message", false),

    // Microsoft 365 sign-in (OAuth 2.0 for SMTP)
    MAIL_OAUTH_TOKEN_UNREACHABLE("Microsoft sign-in (token endpoint) could not be reached or had a temporary fault", true),
    MAIL_OAUTH_CREDENTIAL_REFUSED("Microsoft refused the app credential (wrong or expired client secret or certificate)", false),
    MAIL_OAUTH_APP_UNKNOWN("Microsoft does not know the directory (tenant) ID or the application (client) ID", false),
    MAIL_OAUTH_CREDENTIAL_INVALID("The app credential in the secret store is not a usable certificate with its private key", false),
    MAIL_OAUTH_TOKEN_REFUSED("Microsoft refused to issue a token for sending mail", false),
    MAIL_OAUTH_UNEXPECTED_REPLY("Microsoft sign-in replied in an unexpected way", false),
    MAIL_OAUTH_SEND_AS_NOT_ALLOWED("The app is not allowed to send as the sender mailbox (Exchange permission missing)", false),
    MAIL_SMTP_AUTH_DISABLED("SMTP AUTH is disabled for the sender mailbox", false),

    // Teams / Slack webhooks
    WEBHOOK_ADDRESS_INVALID("%s webhook address no longer valid", false),
    WEBHOOK_REJECTED("%s refused the webhook request (not authorised)", false),
    WEBHOOK_PAYLOAD_REJECTED("%s rejected the message format", false),
    WEBHOOK_RATE_LIMITED("%s asked to slow down (rate limit)", true),
    WEBHOOK_TIMEOUT("%s did not answer in time", true),
    WEBHOOK_UNREACHABLE("%s could not be reached", true),
    WEBHOOK_SERVER_ERROR("%s had a temporary fault", true),
    WEBHOOK_UNEXPECTED_REPLY("%s replied in an unexpected way", false),
    WEBHOOK_CONNECTOR_RETIRED("The address is a retired Office 365 connector; use a Teams Workflows webhook", false),
    WEBHOOK_ADDRESS_NOT_SECURE("%s webhook address must use https", false),

    // Configuration and secrets
    DESTINATION_NOT_CONFIGURED("No %s server or webhook is configured for the destination", false),
    SECRET_NOT_FOUND("The %s secret is not in the secret store", false),
    SECRET_UNAVAILABLE("The secret store could not be reached", true),

    // Routing
    DESTINATION_NOT_CONNECTED("The destination is no longer connected for the tenant", false),
    CHANNEL_NOT_AVAILABLE("No sender is installed for %s", false),
    NO_RECIPIENTS("No one holds the recipient roles", false),
    RECIPIENTS_UNAVAILABLE("The holders of the recipient roles could not be looked up", true),
    SENDER_FAILED("The %s sender stopped with an unexpected fault", true),

    // Messages that ask for a decision (approval links)
    ACTION_REQUEST_CLOSED("The request was decided or expired before the message went out", false),
    ACTION_LINKS_NOT_AVAILABLE("No maker of approval links is installed", false),
    ACTION_KEY_NOT_FOUND("The approval link key is not in the secret store", false),
    ACTION_KEY_INVALID("The approval link key in the secret store is too short (at least 32 characters)", false);

    private final String template;
    private final boolean retryable;

    FailureCode(String template, boolean retryable) {
        this.template = template;
        this.retryable = retryable;
    }

    /** The neutral reason for logs and the admin screen, for example "Teams webhook address no longer valid". */
    public String describe(ChannelKind channel) {
        return template.contains("%s") ? String.format(template, channel.displayName()) : template;
    }

    /** Whether the cause may clear by itself, so the outbox tries again later. */
    public boolean retryable() {
        return retryable;
    }
}
