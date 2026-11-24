package com.documentplatform.monitoring;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Every check of the app with its latest result, for the Support screen ({@link #results()}), the health endpoints
 * and the metrics. A scheduler ({@link #start}) runs each check every {@link DependencyCheck#every()}; each state
 * change is a {@link MonitoringEvent} to the listeners. Checks come from a fixed list and from sources asked again on
 * every round (for example one check per enabled integration).
 */
public final class DependencyStatus implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(DependencyStatus.class.getName());

    private final List<DependencyCheck> fixed;
    private final List<Supplier<List<DependencyCheck>>> sources;
    private final CheckRunner runner;
    private final Clock clock;
    private final Map<String, CheckResult> latest = new ConcurrentHashMap<>();
    private final Map<String, Instant> due = new ConcurrentHashMap<>();
    private final List<Consumer<MonitoringEvent>> listeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<CheckResult>> resultListeners = new CopyOnWriteArrayList<>();
    private final Duration every;
    private ScheduledExecutorService scheduler;

    public DependencyStatus(List<? extends DependencyCheck> checks, List<Supplier<List<DependencyCheck>>> sources,
                            CheckRunner runner, Clock clock) {
        this(checks, sources, runner, clock, null);
    }

    /**
     * @param every how often a check runs when it does not say itself (it keeps {@link DependencyCheck#every()}
     *              unchanged): the app's {@code platform.monitoring.every}; null = each check's own (60 s by default)
     */
    public DependencyStatus(List<? extends DependencyCheck> checks, List<Supplier<List<DependencyCheck>>> sources,
                            CheckRunner runner, Clock clock, Duration every) {
        this.fixed = List.copyOf(checks);
        this.sources = List.copyOf(sources);
        this.runner = Objects.requireNonNull(runner, "runner");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.every = every;
    }

    /** How often the check runs: its own interval if it says one, else the app's, else the default (P039). */
    Duration everyFor(DependencyCheck c) {
        if (every == null) return c.every();
        try {
            boolean own = c.getClass().getMethod("every").getDeclaringClass() != DependencyCheck.class;
            return own ? c.every() : every;
        } catch (NoSuchMethodException impossible) {
            return c.every();
        }
    }

    public DependencyStatus(List<? extends DependencyCheck> checks) {
        this(checks, List.of(), new CheckRunner(), Clock.systemUTC());
    }

    /** Tells the listener of every state change. */
    public void onChange(Consumer<MonitoringEvent> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Tells the listener of every result (the metrics). */
    public void onResult(Consumer<CheckResult> listener) {
        resultListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Every check now: the fixed ones and those the sources give. Names are unique (a later duplicate is dropped). */
    public List<DependencyCheck> checks() {
        Map<String, DependencyCheck> out = new LinkedHashMap<>();
        for (DependencyCheck c : fixed) out.putIfAbsent(c.name(), c);
        for (Supplier<List<DependencyCheck>> s : sources) {
            try {
                for (DependencyCheck c : s.get()) out.putIfAbsent(c.name(), c);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "A check source failed: {0}", e.getClass().getName());
            }
        }
        return List.copyOf(out.values());
    }

    /** Runs every check now and returns the results. */
    public List<CheckResult> runAll() {
        List<CheckResult> out = new ArrayList<>();
        for (DependencyCheck c : checks()) out.add(record(c, runner.run(c)));
        return out;
    }

    /** Runs the checks that are due (each at most every {@link DependencyCheck#every()}). */
    public void runDue() {
        Instant now = clock.instant();
        for (DependencyCheck c : checks()) {
            Instant next = due.get(c.name());
            if (next != null && now.isBefore(next)) continue;
            due.put(c.name(), now.plus(everyFor(c)));
            record(c, runner.run(c));
        }
    }

    /** The latest result of every check now known, sorted by group and name. */
    public List<CheckResult> results() {
        List<String> names = checks().stream().map(DependencyCheck::name).toList();
        latest.keySet().retainAll(names);
        return latest.values().stream().sorted(Comparator.comparing(CheckResult::group)
                .thenComparing(CheckResult::name)).toList();
    }

    /** The latest results of the checks required at start-up (readiness). */
    public List<CheckResult> required() {
        return results().stream().filter(CheckResult::requiredAtStartup).toList();
    }

    /** Starts the scheduler: every {@code tick}, the checks that are due run. */
    public synchronized void start(Duration tick) {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "platform-monitoring");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                runDue();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Monitoring round failed: {0}", e.getClass().getName());
            }
        }, 0, Math.max(100, tick.toMillis()), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) scheduler.shutdownNow();
        scheduler = null;
        runner.close();
    }

    /** Keeps a start-up result (StartupChecks). */
    CheckResult recordStartup(DependencyCheck c, CheckResult r) {
        return record(c, r);
    }

    private CheckResult record(DependencyCheck c, CheckResult r) {
        CheckResult before = latest.put(c.name(), r);
        for (Consumer<CheckResult> l : resultListeners) {
            try {
                l.accept(r);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "A result listener failed: {0}", e.getClass().getName());
            }
        }
        MonitoringEvent e = MonitoringEvent.between(before, r);
        if (e != null) {
            if (r.state() != CheckState.WORKING) {
                LOG.log(System.Logger.Level.WARNING, "check={0} state={1} code={2} cause=\"{3}\"", r.name(),
                        r.state(), r.code().code(), com.documentplatform.errors.SafeText.oneLine(r.cause()).replace('"', '\''));
            } else {
                LOG.log(System.Logger.Level.INFO, "check={0} state=WORKING (recovered)", r.name());
            }
            for (Consumer<MonitoringEvent> l : listeners) {
                try {
                    l.accept(e);
                } catch (RuntimeException ex) {
                    LOG.log(System.Logger.Level.WARNING, "A monitoring listener failed: {0}", ex.getClass().getName());
                }
            }
        }
        return r;
    }
}
