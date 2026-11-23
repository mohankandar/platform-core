package com.documentplatform.integration.spring;

import com.documentplatform.integration.callback.Callbacks;
import com.documentplatform.integration.credential.IntegrationCredentials;
import org.springframework.context.SmartLifecycle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The integrations' background work, on one daemon thread: callbacks that are due ({@link Callbacks#deliverDue}),
 * client certificates whose switch time came ({@link IntegrationCredentials#switchDue}), and once a day the purge of
 * finished callbacks. Several app instances may run it: claims and numbered changes keep them apart. Logs ids and
 * codes only (the services do); a failing run is logged and tried at the next one.
 */
public final class IntegrationWorker implements SmartLifecycle {

    private static final System.Logger LOG = System.getLogger(IntegrationWorker.class.getName());
    static final int BATCH = 50;

    private final Callbacks callbacks;
    private final IntegrationCredentials credentials;
    private final Duration every;
    private final Duration keepFinished;
    private final Clock clock;
    private ScheduledExecutorService pool;
    private volatile Instant lastPurge = Instant.EPOCH;

    public IntegrationWorker(Callbacks callbacks, IntegrationCredentials credentials, Duration every,
                             Duration keepFinished, Clock clock) {
        this.callbacks = callbacks;
        this.credentials = credentials;
        this.every = every;
        this.keepFinished = keepFinished;
        this.clock = clock;
    }

    /** One run (also for tests): due callbacks until none is left (at most 20 batches), switches, the daily purge. */
    public void runOnce() {
        try {
            if (callbacks != null) {
                for (int i = 0; i < 20 && callbacks.deliverDue(BATCH) == BATCH; i++) {
                    // a full batch: there may be more
                }
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Integration worker: callbacks could not be sent this run ({0})",
                    e.getClass().getSimpleName());
        }
        try {
            if (credentials != null) credentials.switchDue(clock.instant());
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Integration worker: timed certificate switches failed this run ({0})",
                    e.getClass().getSimpleName());
        }
        try {
            Instant now = clock.instant();
            if (callbacks != null && now.isAfter(lastPurge.plus(Duration.ofDays(1)))) {
                int n = callbacks.purge(keepFinished);
                lastPurge = now;
                if (n > 0) LOG.log(System.Logger.Level.INFO, "Integration worker: {0} finished callback(s) purged", n);
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Integration worker: the purge failed this run ({0})",
                    e.getClass().getSimpleName());
        }
    }

    @Override
    public synchronized void start() {
        if (pool != null) return;
        pool = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "platform-integration-worker");
            t.setDaemon(true);
            return t;
        });
        pool.scheduleWithFixedDelay(this::runOnce, every.toMillis(), every.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (pool == null) return;
        pool.shutdownNow();
        pool = null;
    }

    @Override
    public synchronized boolean isRunning() {
        return pool != null;
    }
}
