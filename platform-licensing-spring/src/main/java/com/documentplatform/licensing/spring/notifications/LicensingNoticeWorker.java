package com.documentplatform.licensing.spring.notifications;

import org.springframework.context.SmartLifecycle;

import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@link LicensingNotices#check()} on its own daemon thread: first one minute after start (or after the interval,
 * if shorter), then every {@code platform.licensing.notifications.check-interval} (default 1 hour). Restarts never
 * cause duplicates (see {@link LicensingNotices}), and several app instances may run it at once.
 *
 * <p>A failing check (database down) is logged once with its neutral reason, and again when it works.
 */
final class LicensingNoticeWorker implements SmartLifecycle {

    private static final System.Logger LOG = System.getLogger(LicensingNoticeWorker.class.getName());
    private static final Duration FIRST_RUN = Duration.ofMinutes(1);

    private final LicensingNotices notices;
    private final Duration interval;
    private ScheduledExecutorService timer;
    private volatile boolean running;
    private boolean failing;

    LicensingNoticeWorker(LicensingNotices notices, Duration interval) {
        this.notices = notices;
        this.interval = interval;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        if (notices.registered().isEmpty()) {
            LOG.log(Level.INFO, "Licensing notifications off: the app's event catalogue has no licensing event types"
                    + " (register them with LicensingEvents.register)");
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "platform-licensing-notices");
            t.setDaemon(true);
            return t;
        });
        long first = Math.min(FIRST_RUN.toMillis(), interval.toMillis());
        timer.scheduleWithFixedDelay(this::checkOnce, first, interval.toMillis(), TimeUnit.MILLISECONDS);
        LOG.log(Level.INFO, "Licensing notifications: checking every {0} min, {1} event types",
                interval.toMinutes(), notices.registered().size());
    }

    void checkOnce() {
        try {
            notices.check();
            if (failing) {
                failing = false;
                LOG.log(Level.INFO, "Licensing notification check works again");
            }
        } catch (RuntimeException e) {
            if (!failing) {
                failing = true;
                LOG.log(Level.WARNING, "Licensing notification check failed ({0}); retrying every {1} min",
                        e.getClass().getSimpleName(), interval.toMinutes());
            }
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
