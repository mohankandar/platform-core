package com.documentplatform.notifications;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Where occurrences wait for their summary. Postgres implementation: {@code jdbc.PostgresSummaryStore}; in-memory one
 * for tests in the testkit module.
 *
 * <p>SPI: implemented by stores; apps call {@link Notifier}.
 */
public interface SummaryStore {

    /** Adds an item. An item with the same key is kept once. */
    void add(SummaryItem item);

    /** The items of every bucket due at {@code now}, in the order they were added. */
    List<SummaryItem> due(Instant now);

    /** Removes the given items (after their summary is in the outbox). */
    void remove(Collection<String> itemKeys);

    /** How many items are waiting. */
    int pending();
}
