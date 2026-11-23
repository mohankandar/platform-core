package com.documentplatform.integration.callback;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One callback in the outbox. Its ID is the {@code Platform-Callback-Id} of every attempt.
 *
 * @param body          the JSON body the app gave (never document content)
 * @param windowFrom    when the give-up window started: when it was created, or when it was last resent
 * @param claimedUntil  while SENDING: the lease of the sender that has it
 * @param lastOutcome   the last attempt's result class, or null before the first
 * @param lastCode      the last attempt's platform code, or null
 * @param lastStatus    the last attempt's HTTP status (0 for none)
 */
public record CallbackEntry(UUID id, UUID integrationId, UUID entity, String product, String body,
                            CallbackState state, int attempts, Instant createdAt, Instant windowFrom,
                            Instant nextAttemptAt, Instant claimedUntil, String lastOutcome, String lastCode,
                            int lastStatus, Instant finishedAt) {

    public CallbackEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(integrationId, "integrationId");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(windowFrom, "windowFrom");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
    }

    /** A new callback, due now. */
    public static CallbackEntry pending(UUID id, UUID integrationId, UUID entity, String product, String body,
                                        Instant now) {
        return new CallbackEntry(id, integrationId, entity, product, body, CallbackState.PENDING, 0, now, now, now,
                null, null, null, 0, null);
    }

    @Override
    public String toString() {
        return "CallbackEntry[" + id + " " + state + ", " + attempts + " attempt(s)]"; // never the body
    }
}
