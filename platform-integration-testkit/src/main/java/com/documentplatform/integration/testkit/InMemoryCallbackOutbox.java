package com.documentplatform.integration.testkit;

import com.documentplatform.integration.callback.CallbackEntry;
import com.documentplatform.integration.callback.CallbackOutbox;
import com.documentplatform.integration.callback.CallbackState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The callback outbox in memory, with the Postgres outbox's claim and lease rules. For tests. */
public final class InMemoryCallbackOutbox implements CallbackOutbox {

    private final Map<UUID, CallbackEntry> entries = new LinkedHashMap<>();

    @Override
    public synchronized boolean enqueue(CallbackEntry entry) {
        if (entries.containsKey(entry.id())) return false;
        entries.put(entry.id(), entry);
        return true;
    }

    @Override
    public synchronized List<CallbackEntry> claimDue(Instant now, Instant leaseUntil, int limit) {
        List<CallbackEntry> due = new ArrayList<>(entries.values().stream()
                .filter(e -> (e.state() == CallbackState.PENDING && !e.nextAttemptAt().isAfter(now))
                        || (e.state() == CallbackState.SENDING && !e.claimedUntil().isAfter(now)))
                .sorted(Comparator.comparing(CallbackEntry::nextAttemptAt)).limit(limit).toList());
        List<CallbackEntry> out = new ArrayList<>();
        for (CallbackEntry e : due) {
            CallbackEntry claimed = with(e, CallbackState.SENDING, e.attempts(), e.windowFrom(), e.nextAttemptAt(),
                    leaseUntil, e.lastOutcome(), e.lastCode(), e.lastStatus(), null);
            entries.put(e.id(), claimed);
            out.add(claimed);
        }
        return out;
    }

    @Override
    public synchronized void markSent(UUID id, int attempts, String outcome, String code, int status, Instant at) {
        CallbackEntry e = entries.get(id);
        entries.put(id, with(e, CallbackState.SENT, attempts, e.windowFrom(), e.nextAttemptAt(), null, outcome, code,
                status, at));
    }

    @Override
    public synchronized void markRetry(UUID id, int attempts, String outcome, String code, int status,
                                       Instant nextAttemptAt) {
        CallbackEntry e = entries.get(id);
        entries.put(id, with(e, CallbackState.PENDING, attempts, e.windowFrom(), nextAttemptAt, null, outcome, code,
                status, null));
    }

    @Override
    public synchronized void markGaveUp(UUID id, int attempts, String outcome, String code, int status, Instant at) {
        CallbackEntry e = entries.get(id);
        entries.put(id, with(e, CallbackState.GAVE_UP, attempts, e.windowFrom(), e.nextAttemptAt(), null, outcome,
                code, status, at));
    }

    @Override
    public synchronized boolean resend(UUID id, Instant now) {
        CallbackEntry e = entries.get(id);
        if (e == null || e.state() != CallbackState.GAVE_UP) return false;
        entries.put(id, with(e, CallbackState.PENDING, e.attempts(), now, now, null, e.lastOutcome(), e.lastCode(),
                e.lastStatus(), null));
        return true;
    }

    @Override
    public synchronized Optional<CallbackEntry> find(UUID id) {
        return Optional.ofNullable(entries.get(id));
    }

    @Override
    public synchronized List<CallbackEntry> gaveUp(UUID entity, String product, int limit) {
        return entries.values().stream().filter(e -> e.state() == CallbackState.GAVE_UP
                        && e.entity().equals(entity) && e.product().equals(product))
                .sorted(Comparator.comparing(CallbackEntry::finishedAt).reversed()).limit(limit).toList();
    }

    @Override
    public synchronized int purgeFinishedBefore(Instant before) {
        List<UUID> gone = entries.values().stream().filter(e -> e.finishedAt() != null
                && e.finishedAt().isBefore(before)).map(CallbackEntry::id).toList();
        gone.forEach(entries::remove);
        return gone.size();
    }

    private static CallbackEntry with(CallbackEntry e, CallbackState s, int attempts, Instant windowFrom,
                                      Instant next, Instant claimedUntil, String outcome, String code, int status,
                                      Instant finishedAt) {
        return new CallbackEntry(e.id(), e.integrationId(), e.entity(), e.product(), e.body(), s, attempts,
                e.createdAt(), windowFrom, next, claimedUntil, outcome, code, status, finishedAt);
    }
}
