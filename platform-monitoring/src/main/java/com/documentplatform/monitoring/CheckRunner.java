package com.documentplatform.monitoring;

import com.documentplatform.errors.HasPlatformCode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs checks with their time limit. A check that throws is DOWN with the exception's own {@link HasPlatformCode}
 * code when it has one, else {@code MON_CHECK_FAILED} with the exception's class name only (never its text, which
 * may hold a value); a check that does not answer in time is DOWN with {@code MON_CHECK_TIMEOUT}. Never throws.
 */
public final class CheckRunner implements AutoCloseable {

    private static final AtomicInteger THREADS = new AtomicInteger();

    private final ExecutorService pool;
    private final Clock clock;

    public CheckRunner() {
        this(Clock.systemUTC());
    }

    public CheckRunner(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "platform-check-" + THREADS.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** One run of the check. */
    public CheckResult run(DependencyCheck check) {
        Instant start = clock.instant();
        long t0 = System.nanoTime();
        Verdict v;
        Future<Verdict> f = pool.submit(check::check);
        try {
            v = f.get(check.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (v == null) v = Verdict.down(MonitoringCode.MON_CHECK_FAILED, Map.of("check", check.name(),
                    "error", "no verdict"));
        } catch (TimeoutException e) {
            f.cancel(true);
            v = Verdict.down(MonitoringCode.MON_CHECK_TIMEOUT, Map.of("check", check.name(),
                    "timeout", check.timeout().toSeconds() + " s"));
        } catch (ExecutionException e) {
            v = failed(check, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            v = Verdict.down(MonitoringCode.MON_CHECK_FAILED, Map.of("check", check.name(), "error", "interrupted"));
        } catch (RuntimeException e) {
            v = failed(check, e);
        }
        return CheckResult.of(check, v, start, Duration.ofNanos(System.nanoTime() - t0));
    }

    private static Verdict failed(DependencyCheck check, Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof HasPlatformCode h && h.platformCode() != null) return Verdict.down(h.platformCode());
        }
        return Verdict.down(MonitoringCode.MON_CHECK_FAILED, Map.of("check", check.name(),
                "error", t == null ? "unknown" : t.getClass().getName()));
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
