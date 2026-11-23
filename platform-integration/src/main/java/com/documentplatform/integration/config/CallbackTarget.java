package com.documentplatform.integration.config;

import com.documentplatform.notifications.secret.SecretRef;

import java.util.Objects;

/**
 * What a callback (kind {@code CALLBACK}) needs besides its receiver address, sign-in and limits: the secret it signs
 * with, by reference into the secret store (read for each send, never stored here). Each callback carries
 * {@code Platform-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>} and
 * {@code Platform-Callback-Id}. To rotate the secret, the receiver first accepts the old and the new one, then the
 * secret store's value (or this reference) changes to the new one, then the receiver drops the old one.
 *
 * @param signingSecret the signing secret's reference
 */
public record CallbackTarget(SecretRef signingSecret) {

    public CallbackTarget {
        Objects.requireNonNull(signingSecret, "signingSecret");
    }
}
