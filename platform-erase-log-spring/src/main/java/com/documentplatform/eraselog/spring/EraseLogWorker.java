package com.documentplatform.eraselog.spring;

import com.documentplatform.eraselog.EraseGuard;
import com.documentplatform.eraselog.EraseLog;
import com.documentplatform.eraselog.EraseLogException;
import com.documentplatform.eraselog.ErasePosition;
import com.documentplatform.eraselog.EraseRetention;
import org.springframework.context.SmartLifecycle;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.lang.System.Logger.Level;

/**
 * In the background: the guard's cheap recheck every {@code recheck} (so the block lifts after the replay, and a
 * restore recorded by another instance blocks here too), and once a day the full check (the chain) followed by
 * pruning entries older than the log's retention that the database has applied. Ids and counts only in the log.
 */
public final class EraseLogWorker implements SmartLifecycle {

    private static final System.Logger LOG = System.getLogger(EraseLogWorker.class.getName());

    private final EraseGuard guard;
    private final EraseLog log;
    private final ErasePosition position;
    private final EraseRetention retention;
    private final Duration recheck;
    private final Clock clock;
    private ScheduledExecutorService timer;

    public EraseLogWorker(EraseGuard guard, EraseLog log, ErasePosition position, EraseRetention retention,
                          Duration recheck, Clock clock) {
        this.guard = guard;
        this.log = log;
        this.position = position;
        this.retention = retention;
        this.recheck = recheck;
        this.clock = clock;
    }

    /** The recheck: logs a change of state. */
    void recheck() {
        boolean before = guard.mayTakeWork();
        EraseGuard.State s = guard.refresh();
        if (before != s.mayTakeWork()) {
            LOG.log(s.mayTakeWork() ? Level.INFO : Level.WARNING, "Erase log guard: {0} ({1})",
                    s.mayTakeWork() ? "work may start" : "work is blocked", s.code() == null ? "in step" : s.code());
        }
    }

    /** The daily full check and pruning. */
    void daily() {
        EraseGuard.State s = guard.fullCheck();
        if (!s.mayTakeWork()) {
            LOG.log(Level.WARNING, "Erase log guard: work is blocked ({0}); not pruned", s.code());
            return;
        }
        try {
            int pruned = log.prune(clock.instant(), retention, position.read(log.logId()).appliedSeq());
            if (pruned > 0) {
                LOG.log(Level.INFO, "Erase log {0}: {1} entries older than {2} days pruned", log.logId(), pruned,
                        retention.logRetentionDays());
            }
        } catch (EraseLogException e) {
            LOG.log(Level.WARNING, "Erase log {0} not pruned: {1}", log.logId(), e.code());
        }
    }

    @Override
    public synchronized void start() {
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "platform-erase-log");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(this::safeRecheck, recheck.toMillis(), recheck.toMillis(), TimeUnit.MILLISECONDS);
        timer.scheduleWithFixedDelay(this::safeDaily, Duration.ofHours(1).toMillis(), Duration.ofDays(1).toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private void safeRecheck() {
        try {
            recheck();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Erase log recheck failed ({0})", e.getClass().getSimpleName());
        }
    }

    private void safeDaily() {
        try {
            daily();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Erase log daily check failed ({0})", e.getClass().getSimpleName());
        }
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
