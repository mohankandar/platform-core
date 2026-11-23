package com.documentplatform.eraselog.spring;

import com.documentplatform.eraselog.EraseGuard;
import com.documentplatform.eraselog.EraseLogError;
import com.documentplatform.eraselog.EraseRequest;
import com.documentplatform.eraselog.ErasePosition;
import com.documentplatform.eraselog.ErasedBy;
import com.documentplatform.eraselog.Eraser;
import com.documentplatform.eraselog.file.FileEraseLogStore;
import com.documentplatform.eraselog.jdbc.EraseLogSchema;
import com.documentplatform.eraselog.jdbc.PostgresErasePosition;
import com.documentplatform.eraselog.testkit.InMemoryErasePosition;
import com.documentplatform.eraselog.testkit.RecordingEraseHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EraseLogWiringTest {

    static final UUID ENTITY = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    static EraseRequest document(String id) {
        return new EraseRequest(ENTITY, "PRODUCT_A", "case", id, "document", Instant.now(), ErasedBy.rule("RET-7Y"),
                "AUD-" + id);
    }

    private static ApplicationContextRunner app(Path location, String... more) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(EraseLogAutoConfiguration.class))
                .withPropertyValues("platform.erase-log.location=" + location.toAbsolutePath(),
                        "platform.erase-log.exit-after-replay=false")
                .withPropertyValues(more);
    }

    @Test
    void theAppsHandlersAreWiredAndTheGuardLetsWorkStartWhenInStep(@TempDir Path dir) {
        RecordingEraseHandler documents = new RecordingEraseHandler("document").store("d1");
        app(dir.resolve("log"), "platform.erase-log.backup-retention-days=40")
                .withBean(ErasePosition.class, InMemoryErasePosition::new)
                .withBean(RecordingEraseHandler.class, () -> documents)
                .run(ctx -> {
                    assertTrue(ctx.getStartupFailure() == null, () -> String.valueOf(ctx.getStartupFailure()));
                    EraseGuard guard = ctx.getBean(EraseGuard.class);
                    assertTrue(guard.mayTakeWork());
                    ctx.getBean(Eraser.class).erase(document("d1"));
                    assertTrue(documents.present().isEmpty());
                    assertInstanceOf(FileEraseLogStore.class, ctx.getBean(com.documentplatform.eraselog.EraseLogStore.class));
                    Health h = ctx.getBean(EraseLogHealthIndicator.class).health();
                    assertEquals(Status.UP, h.getStatus());
                    assertEquals(40, h.getDetails().get("backupRetentionDays"));
                    assertEquals(70, h.getDetails().get("logRetentionDays"));
                    assertEquals(dir.resolve("log").toAbsolutePath().normalize().toString(), h.getDetails().get("location"));
                    ctx.getBean(EraseLogWorker.class).recheck();
                    assertEquals(1L, guard.state().applied());
                });
    }

    /**
     * The health details, exactly: IDs, the location, positions, counts, the code and its message, retention days and
     * the time checked. Never what an entry names (record type or ID, kind, rule, audit ID) or anything of the record.
     */
    @Test
    void healthDetailsAreExactlyTheSafeOnes(@TempDir Path dir) {
        Path location = dir.resolve("log");
        String record = "PLANTED-RECORD-77";
        List<String> never = List.of(record, "AUD-" + record, "RET-7Y", "document", "case", ENTITY.toString(),
                "PRODUCT_A");
        RecordingEraseHandler documents = new RecordingEraseHandler("document").store(record);
        app(location)
                .withBean(ErasePosition.class, InMemoryErasePosition::new)
                .withBean(RecordingEraseHandler.class, () -> documents)
                .run(ctx -> {
                    assertTrue(ctx.getStartupFailure() == null, () -> String.valueOf(ctx.getStartupFailure()));
                    ctx.getBean(Eraser.class).erase(document(record));
                    ctx.getBean(EraseLogWorker.class).recheck();
                    EraseGuard.State s = ctx.getBean(EraseGuard.class).state();
                    Health h = ctx.getBean(EraseLogHealthIndicator.class).health();
                    assertEquals(Status.UP, h.getStatus());
                    assertEquals(java.util.Map.of(
                            "mayTakeWork", true,
                            "message", "The database is in step with the erase log",
                            "logId", s.logId().toString(),
                            "location", location.toAbsolutePath().normalize().toString(),
                            "logHead", 1L,
                            "applied", 1L,
                            "pending", 0L,
                            "backupRetentionDays", 35,
                            "logRetentionDays", 65,
                            "checkedAt", s.checkedAt().toString()), h.getDetails());
                    for (String text : never) {
                        assertFalse(h.getDetails().toString().contains(text), text + " in: " + h.getDetails());
                    }
                });

        // The same log, a restored (empty) database: out of service with the code, still nothing of the entry.
        app(location)
                .withBean(ErasePosition.class, InMemoryErasePosition::new)
                .withBean(RecordingEraseHandler.class, () -> new RecordingEraseHandler("document").store(record))
                .run(ctx -> {
                    EraseGuard.State s = ctx.getBean(EraseGuard.class).state();
                    Health h = ctx.getBean(EraseLogHealthIndicator.class).health();
                    assertEquals(Status.OUT_OF_SERVICE, h.getStatus());
                    assertEquals(java.util.Set.of("mayTakeWork", "code", "message", "logId", "location", "logHead",
                            "applied", "pending", "backupRetentionDays", "logRetentionDays", "checkedAt"),
                            h.getDetails().keySet());
                    assertEquals(false, h.getDetails().get("mayTakeWork"));
                    assertEquals("ERASE_LOG_RESTORE_DETECTED", h.getDetails().get("code"));
                    assertEquals(s.message(), h.getDetails().get("message"));
                    assertEquals(1L, h.getDetails().get("pending"));
                    for (String text : never) {
                        assertFalse(h.getDetails().toString().contains(text), text + " in: " + h.getDetails());
                    }
                });
    }

    @Test
    void badSettingsStopTheAppWithEveryProblemListed() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(EraseLogAutoConfiguration.class))
                .withPropertyValues("platform.erase-log.backup-retention-days=100", "platform.erase-log.recheck=PT1S")
                .withBean(ErasePosition.class, InMemoryErasePosition::new)
                .run(ctx -> {
                    Throwable t = ctx.getStartupFailure();
                    assertNotNull(t);
                    while (!(t instanceof EraseLogConfigException) && t.getCause() != null) {
                        t = t.getCause();
                    }
                    EraseLogConfigException e = assertInstanceOf(EraseLogConfigException.class, t);
                    assertEquals(3, e.problems().size(), e.getMessage());
                    assertTrue(e.getMessage().contains("platform.erase-log.location is required"));
                    assertTrue(e.getMessage().contains("backup-retention-days must be 30 to 90"));
                });
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(EraseLogAutoConfiguration.class))
                .withPropertyValues("platform.erase-log.location=relative/dir")
                .withBean(ErasePosition.class, InMemoryErasePosition::new)
                .run(ctx -> assertTrue(String.valueOf(rootCause(ctx.getStartupFailure()).getMessage())
                        .contains("location must be an absolute path")));
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(EraseLogAutoConfiguration.class))
                .withPropertyValues("platform.erase-log.enabled=false")
                .run(ctx -> assertFalse(ctx.containsBean("eraseGuard")));
    }

    @Test
    void aRestoredDatabaseStartsOutOfServiceAndTheReplayRunLiftsIt(@TempDir Path dir) {
        Path location = dir.resolve("log");
        FileEraseLogStore existing = new FileEraseLogStore(location);
        existing.append(document("d1"), Instant.now()); // erased before; the restored database does not know
        RecordingEraseHandler documents = new RecordingEraseHandler("document").store("d1");
        app(location)
                .withBean(ErasePosition.class, InMemoryErasePosition::new)
                .withBean(RecordingEraseHandler.class, () -> documents)
                .run(ctx -> {
                    assertTrue(ctx.getStartupFailure() == null, () -> String.valueOf(ctx.getStartupFailure()));
                    EraseGuard guard = ctx.getBean(EraseGuard.class);
                    assertFalse(guard.mayTakeWork());
                    Health h = ctx.getBean(EraseLogHealthIndicator.class).health();
                    assertEquals(Status.OUT_OF_SERVICE, h.getStatus());
                    EraseLogHealthIndicator health = ctx.getBean(EraseLogHealthIndicator.class);
                    assertEquals("ERASE_LOG_RESTORE_DETECTED", h.getDetails().get("code"));
                    assertTrue(String.valueOf(h.getDetails().get("message"))
                            .startsWith("Restored from backup: run the erase replay before starting work"));
                    assertEquals(1L, h.getDetails().get("pending"));

                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    AtomicInteger exited = new AtomicInteger(-1);
                    EraseReplayRunner runner = new EraseReplayRunner(ctx.getBean(com.documentplatform.eraselog
                            .EraseReplay.class), guard, ctx, true, new PrintStream(out, true, StandardCharsets.UTF_8),
                            exited::set);
                    runner.run(new DefaultApplicationArguments("--other"));
                    assertEquals(-1, exited.get(), "not asked: nothing runs");
                    runner.run(new DefaultApplicationArguments("--erase-replay"));
                    assertEquals(0, exited.get(), "exit code 0: passed");
                    assertTrue(out.toString(StandardCharsets.UTF_8).startsWith("Erase replay done and verified"),
                            out.toString(StandardCharsets.UTF_8));
                    assertTrue(documents.present().isEmpty());
                    assertTrue(guard.mayTakeWork());
                    assertEquals(Status.UP, health.health().getStatus());
                });
    }

    @Test
    void aFailingReplayRunExitsWithOneAndTheAppStaysBlocked(@TempDir Path dir) {
        Path location = dir.resolve("log");
        new FileEraseLogStore(location).append(document("d1"), Instant.now());
        RecordingEraseHandler documents = new RecordingEraseHandler("document").failWith("DOC_STORE_DOWN");
        app(location)
                .withBean(ErasePosition.class, InMemoryErasePosition::new)
                .withBean(RecordingEraseHandler.class, () -> documents)
                .run(ctx -> {
                    AtomicInteger exited = new AtomicInteger(-1);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    EraseGuard guard = ctx.getBean(EraseGuard.class);
                    new EraseReplayRunner(ctx.getBean(com.documentplatform.eraselog.EraseReplay.class),
                            guard, ctx, true, new PrintStream(out, true, StandardCharsets.UTF_8),
                            exited::set).run(new DefaultApplicationArguments("--erase-replay"));
                    assertEquals(1, exited.get());
                    assertTrue(out.toString(StandardCharsets.UTF_8).contains("ERASE_HANDLER_FAILED (DOC_STORE_DOWN)"));
                    assertEquals(EraseLogError.ERASE_LOG_RESTORE_DETECTED, guard.state().code());
                });
    }

    // ---- Postgres ----

    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine");

    static {
        PG.start();
        Runtime.getRuntime().addShutdownHook(new Thread(PG::stop));
    }

    static PGSimpleDataSource ds(String user, String password, String schema) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(PG.getJdbcUrl());
        ds.setUser(user);
        ds.setPassword(password);
        if (schema != null) {
            ds.setCurrentSchema(schema);
        }
        return ds;
    }

    static void exec(DataSource ds, String... sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            for (String q : sql) {
                s.execute(q);
            }
        }
    }

    @Test
    void onPostgresTheTableIsCheckedAtStartupAndTheDmlUserIsEnough(@TempDir Path dir) throws Exception {
        String schema = "erls" + ProcessHandle.current().pid();
        DataSource admin = ds(PG.getUsername(), PG.getPassword(), null);
        exec(admin, "create schema " + schema, "create role " + schema + "_app login password 'pw'",
                "grant usage on schema " + schema + " to " + schema + "_app");
        DataSource owner = ds(PG.getUsername(), PG.getPassword(), schema);
        DataSource appUser = ds(schema + "_app", "pw", schema);

        app(dir.resolve("log"))
                .withBean(DataSource.class, () -> appUser)
                .run(ctx -> assertTrue(String.valueOf(rootCause(ctx.getStartupFailure()).getMessage())
                        .startsWith("ERL_TABLES_MISSING"), String.valueOf(ctx.getStartupFailure())));

        exec(owner, EraseLogSchema.script("V1__erase_log.sql"));
        exec(admin, "grant select, insert, update on " + schema + ".erl_position to " + schema + "_app",
                "grant select on " + schema + ".erl_schema_version to " + schema + "_app");
        RecordingEraseHandler documents = new RecordingEraseHandler("document").store("d1");
        app(dir.resolve("log"))
                .withBean(DataSource.class, () -> appUser)
                .withBean(RecordingEraseHandler.class, () -> documents)
                .run(ctx -> {
                    assertTrue(ctx.getStartupFailure() == null, () -> String.valueOf(ctx.getStartupFailure()));
                    assertInstanceOf(PostgresErasePosition.class, ctx.getBean(ErasePosition.class));
                    assertTrue(ctx.getBean(EraseGuard.class).mayTakeWork());
                    ctx.getBean(Eraser.class).erase(document("d1"));
                    ctx.getBean(EraseLogWorker.class).daily();
                    assertEquals(1L, ctx.getBean(EraseGuard.class).state().applied());
                    assertEquals(List.of(), ctx.getBean(ErasePosition.class).otherLogs(
                            ctx.getBean(com.documentplatform.eraselog.EraseLog.class).logId()));
                });
    }

    private static Throwable rootCause(Throwable t) {
        while (t != null && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }
}
