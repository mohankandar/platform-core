package com.documentplatform.notifications;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Where continuous conditions are kept while they last. Postgres implementation: {@code jdbc.PostgresConditionStore};
 * in-memory one for tests in the testkit module.
 *
 * <p>SPI: implemented by stores; apps call {@link Notifier}.
 */
public interface ConditionStore {

    /**
     * Records a failing report: starts the condition at {@code now} if it is new, otherwise counts one more failure
     * and keeps {@code latest} as the latest report. Atomic, so concurrent reports are all counted.
     */
    ConditionState recordFailure(ConditionKey key, NotificationEvent latest, Instant now);

    List<ConditionState> active();

    Optional<ConditionState> find(ConditionKey key);

    /** Records that alert number {@code alerts} was raised at {@code at}. */
    void markAlerted(ConditionKey key, int alerts, Instant at);

    /** Ends the condition. */
    void remove(ConditionKey key);
}
