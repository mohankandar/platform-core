package com.documentplatform.monitoring.testkit;

import com.documentplatform.errors.PlatformCode;
import com.documentplatform.monitoring.DependencyCheck;
import com.documentplatform.monitoring.MonitoringCode;
import com.documentplatform.monitoring.Verdict;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** A check whose answer a test sets: working, having problems, down with a code, throwing, or hanging. */
public final class SwitchableCheck implements DependencyCheck {

    private final String name;
    private final boolean required;
    private volatile Verdict verdict = Verdict.working();
    private volatile RuntimeException throwing;
    private volatile Duration hang = Duration.ZERO;
    private volatile Duration timeout = Duration.ofSeconds(2);
    private volatile Duration every = Duration.ofSeconds(60);
    private final AtomicInteger runs = new AtomicInteger();

    public SwitchableCheck(String name, boolean required) {
        this.name = name;
        this.required = required;
    }

    public SwitchableCheck working() {
        verdict = Verdict.working();
        throwing = null;
        hang = Duration.ZERO;
        return this;
    }

    public SwitchableCheck problems(PlatformCode code) {
        verdict = Verdict.problems(code);
        return this;
    }

    public SwitchableCheck down(PlatformCode code, Map<String, ?> details) {
        verdict = Verdict.down(code, details);
        return this;
    }

    public SwitchableCheck down() {
        return down(MonitoringCode.MON_HTTP_UNREACHABLE, Map.of("service", name, "address", "http://127.0.0.1:1/",
                "timeout", "1 s"));
    }

    /** The next runs throw this. */
    public SwitchableCheck throwing(RuntimeException e) {
        throwing = e;
        return this;
    }

    /** The next runs take this long (past the timeout, the run is DOWN with MON_CHECK_TIMEOUT). */
    public SwitchableCheck hanging(Duration d) {
        hang = d;
        return this;
    }

    public SwitchableCheck timeout(Duration d) {
        timeout = d;
        return this;
    }

    public SwitchableCheck every(Duration d) {
        every = d;
        return this;
    }

    public int runs() {
        return runs.get();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String group() {
        return "test";
    }

    @Override
    public boolean requiredAtStartup() {
        return required;
    }

    @Override
    public Duration timeout() {
        return timeout;
    }

    @Override
    public Duration every() {
        return every;
    }

    @Override
    public Verdict check() throws Exception {
        runs.incrementAndGet();
        if (!hang.isZero()) Thread.sleep(hang.toMillis());
        RuntimeException t = throwing;
        if (t != null) throw t;
        return verdict;
    }
}
