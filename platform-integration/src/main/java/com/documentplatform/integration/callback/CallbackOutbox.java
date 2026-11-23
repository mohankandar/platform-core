package com.documentplatform.integration.callback;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Where callbacks wait until they are delivered or given up (the notifications outbox pattern). Built:
 * {@code jdbc.PostgresCallbackOutbox} ({@code int_callback_outbox}); in memory for tests in the testkit.
 *
 * <p>A claim is a lease: one sender has a callback at a time, also when several app instances share the outbox; if it
 * stops before it records the outcome, the lease runs out and the callback is claimed again (it survives restarts).
 */
public interface CallbackOutbox {

    /** Saves a new callback. False, and nothing changes, when the ID is taken. */
    boolean enqueue(CallbackEntry entry);

    /** Claims up to {@code limit} due callbacks (PENDING and due, or SENDING with a lapsed lease), oldest due first. */
    List<CallbackEntry> claimDue(Instant now, Instant leaseUntil, int limit);

    void markSent(UUID id, int attempts, String outcome, String code, int status, Instant at);

    /** Back to PENDING, tried again at {@code nextAttemptAt}. */
    void markRetry(UUID id, int attempts, String outcome, String code, int status, Instant nextAttemptAt);

    void markGaveUp(UUID id, int attempts, String outcome, String code, int status, Instant at);

    /** A GAVE_UP callback back to PENDING, due now, with a new give-up window. False when it is not GAVE_UP. */
    boolean resend(UUID id, Instant now);

    Optional<CallbackEntry> find(UUID id);

    /** The callbacks given up, of an entity in a product, newest first. */
    List<CallbackEntry> gaveUp(UUID entity, String product, int limit);

    /** Deletes SENT and GAVE_UP callbacks finished before {@code before}. Returns how many. */
    int purgeFinishedBefore(Instant before);
}
