package com.documentplatform.notifications.webhook;

import java.time.Duration;
import java.util.Objects;

/**
 * Connection settings for webhook senders: the connect and reply timeouts. Webhook addresses are https only.
 *
 * <p>Since P041 this is a class, not a record: the switch that accepts {@code http://} addresses to this machine
 * (localhost, 127.0.0.1, [::1]) for the module's own tests can no longer be set by apps (no public constructor or
 * method sets it; xc-public-api-15).
 */
public final class WebhookOptions {

    /** 10 s to connect, 30 s for the reply, https only. */
    public static final WebhookOptions DEFAULT = new WebhookOptions(Duration.ofSeconds(10), Duration.ofSeconds(30));

    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final boolean allowPlainHttp;

    public WebhookOptions(Duration connectTimeout, Duration requestTimeout) {
        this(connectTimeout, requestTimeout, false);
    }

    private WebhookOptions(Duration connectTimeout, Duration requestTimeout, boolean allowPlainHttp) {
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (connectTimeout.isNegative() || connectTimeout.isZero() || requestTimeout.isNegative()
                || requestTimeout.isZero()) {
            throw new IllegalArgumentException("Timeouts must be positive");
        }
        this.connectTimeout = connectTimeout;
        this.requestTimeout = requestTimeout;
        this.allowPlainHttp = allowPlainHttp;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    /**
     * Whether {@code http://} addresses to this machine only (localhost, 127.0.0.1, [::1]) are accepted. Always false
     * outside the module's own tests: the address carries a token, which must not travel unencrypted. Any other host
     * stays https only even with the switch (P037).
     */
    public boolean allowPlainHttp() {
        return allowPlainHttp;
    }

    public WebhookOptions withTimeouts(Duration connect, Duration request) {
        return new WebhookOptions(connect, request, allowPlainHttp);
    }

    /**
     * Accepts {@code http://} addresses to this machine only: for the module's tests against a local server.
     * Package-private since P041 (xc-public-api-15).
     */
    WebhookOptions allowingPlainHttpForTests() {
        return new WebhookOptions(connectTimeout, requestTimeout, true);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WebhookOptions w && connectTimeout.equals(w.connectTimeout)
                && requestTimeout.equals(w.requestTimeout) && allowPlainHttp == w.allowPlainHttp;
    }

    @Override
    public int hashCode() {
        return Objects.hash(connectTimeout, requestTimeout, allowPlainHttp);
    }

    @Override
    public String toString() {
        return "WebhookOptions[connectTimeout=" + connectTimeout + ", requestTimeout=" + requestTimeout
                + ", allowPlainHttp=" + allowPlainHttp + "]";
    }
}
