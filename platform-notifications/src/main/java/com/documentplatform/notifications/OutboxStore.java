package com.documentplatform.notifications;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Where outgoing messages are kept until they are delivered or given up. Postgres implementation:
 * {@code jdbc.PostgresOutboxStore}; in-memory one for tests in the testkit module.
 *
 * <p>Claims make sure one worker sends a message at a time, also when several app instances share the store. A claim
 * is a lease: if the worker stops before it records the outcome, the lease runs out and the message is claimed again.
 *
 * <p>SPI: implemented by stores; apps call {@link Notifier}.
 */
public interface OutboxStore {

    /** Saves a new message. Returns false, and changes nothing, if a message with the same key is already saved. */
    boolean enqueue(OutboxEntry entry);

    /** Claims one message if it is due ({@code PENDING} and due, or {@code SENDING} with a lapsed lease). */
    Optional<OutboxEntry> claim(String key, Instant now, Instant leaseUntil);

    /** Claims up to {@code limit} due messages, oldest due first. */
    List<OutboxEntry> claimDue(Instant now, Instant leaseUntil, int limit);

    void markSent(String key, int attempts, Instant at);

    /** Back to {@code PENDING}, to be tried again at {@code nextAttemptAt}. */
    void markRetry(String key, int attempts, Instant nextAttemptAt, FailureCode failure);

    void markFailed(String key, int attempts, FailureCode failure, Instant at);

    Optional<OutboxEntry> find(String key);

    /** Deletes sent and failed messages finished before {@code before}. Returns how many. */
    int purgeFinishedBefore(Instant before);
}
