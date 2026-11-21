package com.documentplatform.notifications.inmemory;

import com.documentplatform.notifications.FailureCode;
import com.documentplatform.notifications.OutboxEntry;
import com.documentplatform.notifications.OutboxState;
import com.documentplatform.notifications.OutboxStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The outbox in memory, with the same claim and lease rules as the Postgres one. For tests. */
public final class InMemoryOutboxStore implements OutboxStore {

    private final Map<String, OutboxEntry> entries = new LinkedHashMap<>();
    private final Map<String, Instant> claimedUntil = new HashMap<>();

    @Override
    public synchronized boolean enqueue(OutboxEntry entry) {
        return entries.putIfAbsent(entry.key(), entry) == null;
    }

    @Override
    public synchronized Optional<OutboxEntry> claim(String key, Instant now, Instant leaseUntil) {
        OutboxEntry e = entries.get(key);
        if (e == null || !due(e, now)) {
            return Optional.empty();
        }
        return Optional.of(claim(e, leaseUntil));
    }

    @Override
    public synchronized List<OutboxEntry> claimDue(Instant now, Instant leaseUntil, int limit) {
        List<OutboxEntry> due = entries.values().stream().filter(e -> due(e, now))
                .sorted(Comparator.comparing(OutboxEntry::nextAttemptAt)) // stable: saved order among equals
                .limit(limit).toList();
        List<OutboxEntry> claimed = new ArrayList<>();
        due.forEach(e -> claimed.add(claim(e, leaseUntil)));
        return claimed;
    }

    @Override
    public synchronized void markSent(String key, int attempts, Instant at) {
        replace(key, OutboxState.SENT, attempts, null, null, at);
    }

    @Override
    public synchronized void markRetry(String key, int attempts, Instant nextAttemptAt, FailureCode failure) {
        replace(key, OutboxState.PENDING, attempts, nextAttemptAt, failure, null);
    }

    @Override
    public synchronized void markFailed(String key, int attempts, FailureCode failure, Instant at) {
        replace(key, OutboxState.FAILED, attempts, null, failure, at);
    }

    @Override
    public synchronized Optional<OutboxEntry> find(String key) {
        return Optional.ofNullable(entries.get(key));
    }

    @Override
    public synchronized int purgeFinishedBefore(Instant before) {
        List<String> gone = entries.values().stream()
                .filter(e -> e.finishedAt() != null && e.finishedAt().isBefore(before)).map(OutboxEntry::key).toList();
        gone.forEach(entries::remove);
        return gone.size();
    }

    /** Every message, in the order saved. */
    public synchronized List<OutboxEntry> all() {
        return List.copyOf(entries.values());
    }

    private boolean due(OutboxEntry e, Instant now) {
        return (e.state() == OutboxState.PENDING && !e.nextAttemptAt().isAfter(now))
                || (e.state() == OutboxState.SENDING && !claimedUntil.get(e.key()).isAfter(now));
    }

    private OutboxEntry claim(OutboxEntry e, Instant leaseUntil) {
        OutboxEntry claimed = with(e, OutboxState.SENDING, e.attempts(), e.nextAttemptAt(), e.lastFailure(),
                e.finishedAt());
        entries.put(e.key(), claimed);
        claimedUntil.put(e.key(), leaseUntil);
        return claimed;
    }

    private void replace(String key, OutboxState state, int attempts, Instant next, FailureCode failure,
                         Instant finished) {
        OutboxEntry e = entries.get(key);
        if (e == null) {
            return;
        }
        claimedUntil.remove(key);
        entries.put(key, with(e, state, attempts, next == null ? e.nextAttemptAt() : next,
                failure == null ? e.lastFailure() : failure, finished));
    }

    private static OutboxEntry with(OutboxEntry e, OutboxState state, int attempts, Instant next, FailureCode failure,
                                    Instant finished) {
        return new OutboxEntry(e.key(), e.tenant(), e.destination(), e.channel(), e.entity(), e.recipientRoles(),
                e.eventTypes(), e.message(), state, attempts, e.createdAt(), next, failure, finished,
                e.recipientUser());
    }
}
