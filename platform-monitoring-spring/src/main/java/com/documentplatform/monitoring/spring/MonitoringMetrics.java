package com.documentplatform.monitoring.spring;

import com.documentplatform.monitoring.CheckResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ListableBeanFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Metrics through Micrometer, named {@code platform.<area>.<measure>}, tags names only (never a value):
 * <ul>
 *   <li>{@code platform.monitoring.check.state} (gauge; tags {@code check}, {@code group}): 0 working, 1 having
 *       problems, 2 down;</li>
 *   <li>{@code platform.monitoring.check.duration} (timer; same tags);</li>
 *   <li>{@code platform.integration.attempts} (counter; tags {@code integration}, {@code outcome}).</li>
 * </ul>
 */
public final class MonitoringMetrics {

    public static final String STATE = "platform.monitoring.check.state";
    public static final String DURATION = "platform.monitoring.check.duration";
    public static final String ATTEMPTS = "platform.integration.attempts";

    private final MeterRegistry registry;
    private final Map<String, AtomicInteger> states = new ConcurrentHashMap<>();

    MonitoringMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void record(CheckResult r) {
        AtomicInteger state = states.computeIfAbsent(r.name(), n -> {
            AtomicInteger v = new AtomicInteger();
            Gauge.builder(STATE, v, AtomicInteger::get).tag("check", n).tag("group", r.group())
                    .description("0 working, 1 having problems, 2 down").register(registry);
            return v;
        });
        state.set(r.state().gauge());
        Timer.builder(DURATION).tag("check", r.name()).tag("group", r.group()).register(registry).record(r.took());
    }

    void attempt(String integration, String outcome) {
        Counter.builder(ATTEMPTS).tag("integration", integration).tag("outcome", outcome).register(registry)
                .increment();
    }

    /** Counts the call engine's attempts by outcome (loaded only with platform-integration). */
    static final class Attempts {
        static void bind(MonitoringMetrics m, ListableBeanFactory beans) {
            var engine = beans.getBeanProvider(com.documentplatform.integration.engine.CallEngine.class).getIfUnique();
            if (engine != null) {
                engine.onAttempt(r -> m.attempt(String.valueOf(r.integrationId()), r.outcome().name()));
            }
        }
    }
}
