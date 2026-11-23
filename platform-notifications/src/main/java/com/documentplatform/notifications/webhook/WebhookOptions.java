package com.documentplatform.notifications.webhook;

import java.time.Duration;
import java.util.Objects;

/**
 * Connection settings for webhook senders.
 *
 * @param allowPlainHttp accept {@code http://} addresses to this machine only (localhost, 127.0.0.1, [::1]). Off by
 *                       default: the address carries a token, which must not
 *                       travel unencrypted. Only for tests against a local server
 */
public record WebhookOptions(Duration connectTimeout, Duration requestTimeout, boolean allowPlainHttp) {

    /** 10 s to connect, 30 s for the reply, https only. */
    public static final WebhookOptions DEFAULT = new WebhookOptions(Duration.ofSeconds(10), Duration.ofSeconds(30), false);

    public WebhookOptions {
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (connectTimeout.isNegative() || connectTimeout.isZero() || requestTimeout.isNegative()
                || requestTimeout.isZero()) {
            throw new IllegalArgumentException("Timeouts must be positive");
        }
    }

    public WebhookOptions withTimeouts(Duration connect, Duration request) {
        return new WebhookOptions(connect, request, allowPlainHttp);
    }

    /**
     * Accepts {@code http://} addresses to this machine only (localhost, 127.0.0.1, [::1]): for tests against a local
     * server. Any other host stays https only even with this switch (P037), so it cannot weaken production sending.
     */
    public WebhookOptions allowingPlainHttpForTests() {
        return new WebhookOptions(connectTimeout, requestTimeout, true);
    }
}
