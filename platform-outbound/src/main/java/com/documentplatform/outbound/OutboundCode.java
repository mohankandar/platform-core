package com.documentplatform.outbound;

/**
 * Why an outbound call could not be made or got no answer. Stable codes; a caller maps them to its own (the
 * integrations to their {@code INT_…} codes of the same name, so no integration result changes). Never holds an
 * address, header, body or secret.
 */
public enum OutboundCode {
    /** Could not connect (refused, unreachable, or the address cannot be used). */
    OUT_CANT_CONNECT,
    /** The host name is not known. */
    OUT_HOST_UNKNOWN,
    /** The connection could not be opened within the connect timeout. */
    OUT_CONNECT_TIMEOUT,
    /** No answer within the read timeout. The request may have arrived. */
    OUT_TIMEOUT,
    /** The connection was lost before a full answer was read. */
    OUT_CONNECTION_LOST,
    /** The TLS handshake failed: certificate not trusted, host name not in the certificate, protocol refused. */
    OUT_TLS_FAILED,
    /** The address resolves to a blocked address (cloud metadata, link-local; internal unless allowed). */
    OUT_ADDRESS_BLOCKED,
    /** The address is not https and plain http is not allowed for this installation. */
    OUT_ADDRESS_NOT_SECURE,
    /** The outbound proxy refused the connection. */
    OUT_PROXY_REFUSED,
    /** The outbound proxy refused the sign-in (407). */
    OUT_PROXY_SIGN_IN_REFUSED("The outbound proxy refused the sign-in (407). Check the proxy user and the password in "
            + "the secret store (platform.outbound.proxy.user, platform.outbound.proxy.password-secret)."),
    /** The other side refused the client certificate (a TLS alert: bad, unknown, expired or revoked certificate). */
    OUT_CREDENTIAL_REFUSED("The destination refused the client certificate. Check with its owner that they trust "
            + "this certificate (subject, issuer and fingerprint as shown) and that it is not revoked."),
    /** The proxy password's reference names nothing in the secret store. */
    OUT_SECRET_NOT_FOUND,
    /** The secret store could not be read. */
    OUT_SECRET_UNAVAILABLE,
    /** A header the HTTP layer sets itself, or a header value with control characters. */
    OUT_VALUE_NOT_ALLOWED;

    private final String message;

    OutboundCode() {
        this(null);
    }

    OutboundCode(String message) {
        this.message = message;
    }

    /** What to tell the customer's IT, for the codes they act on; else the code's name. Never holds a value. */
    public String message() {
        return message == null ? name() : message;
    }

    /** Whether {@link #message()} is more than the name. */
    public boolean hasMessage() {
        return message != null;
    }
}
