package com.documentplatform.integration;

/**
 * The platform's stable codes for integrations: the code of every attempt, health check and Test result, and of every
 * configuration problem. Logs, results and the app's audit carry these codes (and the destination's own code), never a
 * secret, a document's content or the destination's message text.
 */
public enum IntegrationCode {

    // ---- One attempt: what happened ----
    /** Every step answered as expected. */
    INT_SENT,
    /** The destination already had it (a classification rule said so, for example a replay answer). */
    INT_ALREADY_RECEIVED,
    /** The destination refused the request (a 4xx other than sign-in); its own code is in the result. */
    INT_REFUSED,
    /** 404 without a destination code: the address or path is wrong, which is a set-up fault, not a refusal. */
    INT_ADDRESS_NOT_FOUND,
    /** The destination answered with a redirect; redirects are never followed. */
    INT_REDIRECT_REFUSED,
    /** The destination refused the sign-in (401 / 403). */
    INT_SIGN_IN_REFUSED,
    /** The destination is busy: wait (Retry-After when sent) and send less; no attempt used. */
    INT_BUSY,
    /** The destination is down or failing (5xx not classed as busy). */
    INT_OUTAGE,
    /** A 2xx answer that could not be read as the configuration expects (a value missing, not JSON, too long). */
    INT_UNCLEAR_ANSWER,
    /** The answer was larger than the integration's limit and was not read. */
    INT_RESPONSE_TOO_LARGE,
    /** An answer with a status no rule covers. */
    INT_UNEXPECTED_STATUS,

    // ---- Could not connect ----
    INT_CANT_CONNECT,
    INT_HOST_UNKNOWN,
    INT_CONNECT_TIMEOUT,
    /** No answer within the read timeout. The request may have arrived: resend with the same idempotency key. */
    INT_TIMEOUT,
    /** The connection was lost before a full answer was read. */
    INT_CONNECTION_LOST,
    /** The TLS handshake failed: certificate not trusted, host name not in the certificate, protocol refused. */
    INT_TLS_FAILED,
    /** The address resolves to a blocked address (cloud metadata, link-local; internal when hosted). */
    INT_ADDRESS_BLOCKED,
    /** The outbound proxy refused the connection. */
    INT_PROXY_REFUSED,

    // ---- Sign-in bundle ----
    INT_SECRET_NOT_FOUND,
    INT_SECRET_UNAVAILABLE,
    /** Sign-in NONE is used where it is not allowed (hosted, or not switched on for this installation). */
    INT_SIGN_IN_NONE_NOT_ALLOWED,
    /** The token address could not be reached, or answered 429 / 5xx. */
    INT_TOKEN_UNREACHABLE,
    /** The token address refused the request (for example an unknown scope or audience). */
    INT_TOKEN_REFUSED,
    /** The token address refused the client's credential ({@code invalid_client}, {@code unauthorized_client}). */
    INT_TOKEN_CREDENTIAL_REFUSED,
    /** The token address does not know the client or the directory. */
    INT_TOKEN_CLIENT_UNKNOWN,
    /** The certificate credential in the secret store is not a certificate with its matching private key. */
    INT_TOKEN_CREDENTIAL_INVALID,
    /** The certificate credential has expired. */
    INT_TOKEN_CREDENTIAL_EXPIRED,
    /** The token address answered 200 with something that is not a bearer token. */
    INT_TOKEN_UNEXPECTED_REPLY,

    // ---- Before sending ----
    /** The integration is switched off. */
    INT_DISABLED,
    /** This kind of integration is not run by the call engine (yet). */
    INT_KIND_NOT_SUPPORTED,
    /** A value a template needs was not supplied. */
    INT_VALUE_MISSING,
    /** A value cannot be placed where the template puts it (control characters in a header, a non-number). */
    INT_VALUE_NOT_ALLOWED,
    /** A document is larger than the integration's limit. */
    INT_FILE_TOO_LARGE,
    /** More documents than the integration's limit. */
    INT_TOO_MANY_DOCUMENTS,
    /** A document's content could not be read from the app. */
    INT_CONTENT_UNREADABLE,
    /** The address is not https and plain http is not allowed for this installation. */
    INT_ADDRESS_NOT_SECURE,

    // ---- Health and Test ----
    INT_HEALTHY,
    /** Test action in check-only mode: health and sign-in worked; no step was sent. */
    INT_TEST_CHECKED,

    // ---- Configuration (when it is saved) ----
    INT_CONFIG_INVALID,
    INT_CONFIG_ADDRESS_INVALID,
    INT_CONFIG_ADDRESS_NOT_SECURE,
    INT_CONFIG_PLACEHOLDER_UNKNOWN,
    /** A placeholder for a secret, sign-in or document content. */
    INT_CONFIG_PLACEHOLDER_FORBIDDEN,
    /** A placeholder in the query string or the host of an address. */
    INT_CONFIG_PLACEHOLDER_IN_QUERY,
    /** A placeholder where its value cannot go (for example text outside a JSON string). */
    INT_CONFIG_PLACEHOLDER_PLACE,
    /** A step placeholder naming a step that does not run earlier, or a value that step does not read. */
    INT_CONFIG_STEP_VALUE_UNKNOWN,
    INT_CONFIG_TEMPLATE_INVALID,
    INT_CONFIG_BODY_NOT_JSON,
    /** A header that only the sign-in bundle or the HTTP layer may set (Authorization, Host, ...). */
    INT_CONFIG_HEADER_RESERVED,
    INT_CONFIG_HEADER_INVALID,
    INT_CONFIG_SIGN_IN_NONE_HOSTED,
    INT_CONFIG_SIGN_IN_NONE_OFF,
    /** Warning (not an error): sign-in NONE is in use on this on-premises installation. */
    INT_CONFIG_SIGN_IN_NONE_WARNING,
    INT_CONFIG_RULE_INVALID,
    INT_CONFIG_STEPS_INVALID,
    INT_CONFIG_VERSION_CONFLICT,
    INT_CONFIG_NOT_FOUND,
    INT_CONFIG_EXISTS
}
