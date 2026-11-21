package com.documentplatform.licensing;

import java.time.Instant;
import java.util.Optional;

/**
 * Keeps the latest time the installation has seen, for {@link ClockGuard}. The app supplies a database-backed one;
 * the testkit has an in-memory one.
 */
public interface LastSeenStore {

    /** The latest time recorded, or empty if none yet. */
    Optional<Instant> lastSeen();

    /** Records {@code seen}. Only called with a time later than {@link #lastSeen()}; must never move the value back. */
    void advanceTo(Instant seen);
}
