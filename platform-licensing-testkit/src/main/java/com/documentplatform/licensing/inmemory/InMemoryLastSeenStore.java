package com.documentplatform.licensing.inmemory;

import com.documentplatform.licensing.LastSeenStore;

import java.time.Instant;
import java.util.Optional;

/** The latest time seen, in memory, for tests. Never moves back. */
public final class InMemoryLastSeenStore implements LastSeenStore {

    private Instant lastSeen;

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
}
