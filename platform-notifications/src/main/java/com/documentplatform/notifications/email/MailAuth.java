package com.documentplatform.notifications.email;

/** How the sender signs in to the mail server. */
public enum MailAuth {
    /** A user name and password (or no login, for a relay): the customer's own mail server, for example Exchange. */
    PASSWORD,
    /** Microsoft 365 with OAuth 2.0: an Entra app's token, SMTP {@code AUTH XOAUTH2} as the sender mailbox. */
    OAUTH2_MICROSOFT
}
