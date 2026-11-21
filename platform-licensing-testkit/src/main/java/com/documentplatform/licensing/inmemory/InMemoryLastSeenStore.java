package com.documentplatform.licensing.inmemory;

import com.documentplatform.licensing.LastSeenStore;

import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** The latest time seen, in memory, for tests. Never moves back except through {@link #resetTo}. */
public final class InMemoryLastSeenStore implements LastSeenStore {

    private Instant lastSeen;
    private final Set<String> resets = new HashSet<>();

    @Override
    public synchronized Optional<Instant> lastSeen() {
        return Optional.ofNullable(lastSeen);
    }

    @Override
    public synchronized void advanceTo(Instant seen) {
        if (lastSeen == null || seen.isAfter(lastSeen)) {
            lastSeen = seen;
        }
    }

    @Override
    public synchronized void resetTo(Instant to, String resetId) {
        if (!resets.add(resetId)) {
            throw new IllegalStateException("reset " + resetId + " applied twice");
        }
        lastSeen = to;
    }

    @Override
    public synchronized boolean resetApplied(String resetId) {
        return resets.contains(resetId);
    }
}
