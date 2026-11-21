package com.documentplatform.licensing;

import java.time.Instant;
import java.util.Optional;

/**
 * Keeps the latest time the installation has seen, for {@link ClockGuard}. The Postgres one is
 * {@code jdbc.PostgresLastSeenStore}; the testkit has an in-memory one.
 */
public interface LastSeenStore {

    /** The latest time recorded, or empty if none yet. */
    Optional<Instant> lastSeen();

    /** Records {@code seen} if it is later than the value held; must never move the value back. */
    void advanceTo(Instant seen);

    /**
     * Sets the value to {@code to} (even backwards) and records {@code resetId} as applied, in one step. Called only by
     * {@link ClockGuard#apply} with a verified, signed reset.
     */
    void resetTo(Instant to, String resetId);

    /** Whether the reset with this ID was applied before. */
    boolean resetApplied(String resetId);
}
