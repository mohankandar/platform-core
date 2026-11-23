package com.documentplatform.notifications.webhook;

/**
 * Test helper: webhook options that accept {@code http://} to this machine, for tests in other packages against a
 * local server. Test sources only; the switch is package-private on {@link WebhookOptions} (P041).
 */
public final class LocalWebhookOptions {

    private LocalWebhookOptions() {
    }

    public static WebhookOptions plainHttpToThisMachine(WebhookOptions options) {
        return options.allowingPlainHttpForTests();
    }
}
