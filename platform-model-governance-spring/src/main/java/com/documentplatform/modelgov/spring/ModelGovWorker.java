package com.documentplatform.modelgov.spring;

import com.documentplatform.modelgov.ModelMeasures;
import com.documentplatform.modelgov.ModelReviews;
import org.springframework.context.SmartLifecycle;

import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * In the background, every {@code every}: the review notices ({@link ModelReviews#due}, each stage raised once) and,
 * once a day, the purge of readings older than the retention. Counts only in the log.
 */
public final class ModelGovWorker implements SmartLifecycle {

    private static final System.Logger LOG = System.getLogger(ModelGovWorker.class.getName());

    private final ModelReviews reviews;
    private final ModelMeasures measures;
    private final Duration every;
    private final Clock clock;
    private ScheduledExecutorService timer;
    private volatile Instant lastPurge = Instant.EPOCH;

    public ModelGovWorker(ModelReviews reviews, ModelMeasures measures, Duration every, Clock clock) {
        this.reviews = reviews;
        this.measures = measures;
        this.every = every;
        this.clock = clock;
    }

    /** One run (also for tests). */
    public void runOnce() {
        try {
            long raised = reviews.due(clock.instant()).stream().filter(ModelReviews.ReviewDue::raisedNow).count();
            if (raised > 0) LOG.log(Level.INFO, "Model governance: {0} review notices raised", raised);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Model governance: review notices could not be raised this run ({0})",
                    e.getClass().getSimpleName());
        }
        Instant now = clock.instant();
        if (Duration.between(lastPurge, now).compareTo(Duration.ofDays(1)) >= 0) {
            try {
                int n = measures.purge();
                lastPurge = now;
                if (n > 0) LOG.log(Level.INFO, "Model governance: {0} readings older than {1} days removed", n,
                        measures.retention().toDays());
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Model governance: readings could not be purged this run ({0})",
                        e.getClass().getSimpleName());
            }
        }
    }

    @Override
    public synchronized void start() {
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "platform-model-governance");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(this::runOnce, Math.min(every.toMillis(), 60_000), every.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return timer != null;
    }
}
