package com.documentplatform.monitoring;

import com.documentplatform.identity.jdbc.IdentitySchemaException;
import com.documentplatform.monitoring.testkit.RecordingEvents;
import com.documentplatform.monitoring.testkit.SwitchableCheck;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** P032 B2: the runner, the status, state-change events and the start-up refusal. */
class MonitoringCoreTest {

    /** An exception that carries its code, as the modules' schema exceptions do. */
    static final class Coded extends RuntimeException implements com.documentplatform.errors.HasPlatformCode {
        private final com.documentplatform.errors.PlatformCode code;

        Coded(com.documentplatform.errors.PlatformCode code) {
            super("details that are never shown");
            this.code = code;
        }

        @Override
        public com.documentplatform.errors.PlatformCode platformCode() {
            return code;
        }
    }

    @Test
    void aCheckThatThrowsOrHangsIsDownNeverAnUnhandledError() {
        try (CheckRunner runner = new CheckRunner()) {
            SwitchableCheck c = new SwitchableCheck("ocr", false);
            CheckResult ok = runner.run(c);
            assertEquals(CheckState.WORKING, ok.state());
            assertEquals(MonitoringCode.MON_WORKING, ok.code());

            CheckResult thrown = runner.run(c.throwing(new IllegalStateException("password=hunter2 in the text")));
            assertEquals(CheckState.DOWN, thrown.state());
            assertEquals(MonitoringCode.MON_CHECK_FAILED, thrown.code());
            assertEquals("The check ocr could not run: java.lang.IllegalStateException.", thrown.cause());
            assertFalse(thrown.toString().contains("hunter2") || thrown.cause().contains("hunter2"));

            CheckResult coded = runner.run(c.throwing(new Coded(
                    IdentitySchemaException.Problem.IDN_VERSION_OLDER)));
            assertEquals(IdentitySchemaException.Problem.IDN_VERSION_OLDER, coded.code(), "the exception's own code");

            CheckResult hung = runner.run(new SwitchableCheck("slow", false).hanging(Duration.ofSeconds(5))
                    .timeout(Duration.ofMillis(200)));
            assertEquals(CheckState.DOWN, hung.state());
            assertEquals(MonitoringCode.MON_CHECK_TIMEOUT, hung.code());
            assertTrue(hung.took().compareTo(Duration.ofSeconds(2)) < 0, hung.took().toString());
            assertEquals("The check slow did not answer within 0 s.", hung.cause());
        }
    }

    @Test
    void stateChangesAreEventsAndTheLatestResultsAreKept() {
        SwitchableCheck a = new SwitchableCheck("alpha", true);
        SwitchableCheck b = new SwitchableCheck("beta", false);
        DependencyStatus status = new DependencyStatus(List.of(a, b));
        RecordingEvents events = new RecordingEvents();
        status.onChange(events);
        status.runAll();
        assertEquals(List.of(), events.summary(), "working from the start: no event");
        a.down();
        b.problems(MonitoringCode.MON_DISK_ALMOST_FULL);
        status.runAll();
        assertEquals(List.of("BECAME_DOWN:alpha", "BECAME_HAVING_PROBLEMS:beta"), events.summary());
        status.runAll();
        assertEquals(2, events.events().size(), "no event while the state stays");
        a.working();
        status.runAll();
        assertEquals("RECOVERED:alpha", events.summary().get(2));
        assertEquals(List.of("alpha", "beta"), status.results().stream().map(CheckResult::name).toList());
        assertEquals(List.of("alpha"), status.required().stream().map(CheckResult::name).toList());
        status.close();
    }

    @Test
    void sourcesGiveChecksPerRoundAndRunDueRespectsEachInterval() {
        SwitchableCheck fixed = new SwitchableCheck("fixed", false).every(Duration.ofHours(1));
        java.util.concurrent.atomic.AtomicReference<List<DependencyCheck>> dynamic =
                new java.util.concurrent.atomic.AtomicReference<>(List.of(new SwitchableCheck("one", false)));
        Supplier<List<DependencyCheck>> source = dynamic::get;
        DependencyStatus status = new DependencyStatus(List.of(fixed), List.of(source), new CheckRunner(),
                Clock.systemUTC());
        status.runDue();
        status.runDue();
        assertEquals(1, fixed.runs(), "due again only after its interval");
        dynamic.set(List.of(new SwitchableCheck("two", false)));
        status.runDue();
        assertEquals(List.of("fixed", "two"), status.results().stream().map(CheckResult::name).toList(),
                "a check that is gone leaves the list");
        status.close();
    }

    @Test
    void theSchedulerRunsTheChecks() throws Exception {
        SwitchableCheck c = new SwitchableCheck("ticking", false).every(Duration.ofMillis(100));
        DependencyStatus status = new DependencyStatus(List.of(c));
        status.start(Duration.ofMillis(100));
        long until = System.currentTimeMillis() + 5000;
        while (c.runs() < 3 && System.currentTimeMillis() < until) Thread.sleep(50);
        status.close();
        assertTrue(c.runs() >= 3, "runs " + c.runs());
    }

    @Test
    void theAppRefusesToStartWhenARequiredCheckIsDown() {
        SwitchableCheck db = new SwitchableCheck("database", true).down(IdentitySchemaException.Problem.IDN_VERSION_OLDER,
                java.util.Map.of());
        SwitchableCheck optional = new SwitchableCheck("ocr", false).down();
        SwitchableCheck keys = new SwitchableCheck("key-service", true);
        DependencyStatus status = new DependencyStatus(List.of(db, optional, keys));
        StartupChecks.Refused r = assertThrows(StartupChecks.Refused.class, () -> StartupChecks.require(status));
        assertEquals(List.of("database"), r.down().stream().map(CheckResult::name).toList());
        assertEquals("The app cannot start: 1 required dependency is down.\n"
                + "  database: IDN_VERSION_OLDER: The identity tables are older than the code: the schema step for this "
                + "release was not run. Fix: Run the identity schema step of this release (Flyway location "
                + "db/platform/identity), then start the app.", r.getMessage());
        assertEquals(0, optional.runs(), "only required checks run at start-up");
        db.working();
        assertEquals(2, StartupChecks.require(status).size());
        status.close();
    }
}
