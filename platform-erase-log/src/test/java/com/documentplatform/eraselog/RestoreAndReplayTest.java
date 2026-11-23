package com.documentplatform.eraselog;

import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.testkit.InMemoryKeyService;
import com.documentplatform.eraselog.fields.CaseFieldsEraseHandler;
import com.documentplatform.eraselog.file.FileEraseLogStore;
import com.documentplatform.eraselog.jdbc.EraseLogSchema;
import com.documentplatform.eraselog.jdbc.EraseLogSchemaException;
import com.documentplatform.eraselog.jdbc.PostgresErasePosition;
import com.documentplatform.fields.FieldDefinitions;
import com.documentplatform.fields.FieldScope;
import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.FieldType;
import com.documentplatform.fields.FieldValue;
import com.documentplatform.fields.inmemory.InMemoryFieldStore;
import com.documentplatform.fields.inmemory.InMemoryValueStore;
import com.documentplatform.fields.values.FieldValues;
import com.documentplatform.fields.values.FieldValuesError;
import com.documentplatform.fields.values.FieldValuesException;
import com.documentplatform.fields.values.RecordRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole procedure on Postgres, with the app connecting as the DML-only user (SELECT, INSERT, UPDATE on
 * erl_position): the log in a directory of its own, the case-fields handler on platform-fields and a document handler
 * of the "app". A restore is simulated by putting back the database's rows of before the erasures.
 */
class RestoreAndReplayTest {

    static final UUID ENTITY = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final FieldScope SCOPE = FieldScope.of(ENTITY, "PRODUCT_A");
    static final CustomerId CUSTOMER = CustomerId.fromTenantId("TEN-TEST-0001");
    static final String CASE = "business_record";
    static final Instant T0 = Instant.parse("2026-11-01T09:00:00Z");

    /** A clock the test moves. */
    static final class TestClock extends Clock {
        Instant now = T0;

        @Override
        public ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** The app's document store and its handler. */
    static final class Documents implements EraseHandler {
        final Set<String> stored = new HashSet<>();
        String failOn;
        boolean crash;
        boolean claimsGoneButKeeps;

        @Override
        public String kind() {
            return "document";
        }

        @Override
        public Applied apply(EraseEntry entry, Context ctx) {
            String id = entry.request().recordId();
            if (crash) {
                throw new IllegalStateException("the process stopped");
            }
            if (id.equals(failOn)) {
                throw new EraseHandlerException("DOC_STORE_DOWN", "the document store did not answer");
            }
            if (claimsGoneButKeeps) {
                return Applied.ALREADY_GONE;
            }
            return stored.remove(id) ? Applied.ERASED : Applied.ALREADY_GONE;
        }

        RuntimeException verifyFailure;

        @Override
        public Verified verify(EraseEntry entry) {
            if (verifyFailure != null) {
                throw verifyFailure;
            }
            return stored.contains(entry.request().recordId()) ? Verified.READABLE : Verified.GONE;
        }
    }

    /** A handler of the "app" erasing in the app's own database through the position's connection. */
    static final class DbDocuments implements EraseHandler {
        volatile boolean failAfterWrite;

        @Override
        public String kind() {
            return "document";
        }

        @Override
        public Applied apply(EraseEntry entry, Context ctx) {
            Connection c = ctx.connection().orElseThrow();
            int n;
            try (java.sql.PreparedStatement ps = c.prepareStatement("delete from app_document where id = ?")) {
                ps.setString(1, entry.request().recordId());
                n = ps.executeUpdate();
            } catch (java.sql.SQLException e) {
                throw new EraseHandlerException("DOC_DB_FAILED", "the delete failed (SQL state " + e.getSQLState()
                        + ")");
            }
            if (failAfterWrite) {
                throw new EraseHandlerException("DOC_STORE_DOWN", "the copy store did not answer");
            }
            return n > 0 ? Applied.ERASED : Applied.ALREADY_GONE;
        }

        @Override
        public Verified verify(EraseEntry entry) {
            return Verified.GONE;
        }
    }

    /** Records the order entries were applied in, slowly, so that overlapping applies would show. */
    static final class OrderedDocuments implements EraseHandler {
        final List<Long> applied = java.util.Collections.synchronizedList(new ArrayList<>());
        final java.util.concurrent.atomic.AtomicInteger inside = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger overlaps = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public String kind() {
            return "document";
        }

        @Override
        public Applied apply(EraseEntry entry, Context ctx) {
            if (inside.incrementAndGet() > 1) {
                overlaps.incrementAndGet();
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            applied.add(entry.seq());
            inside.decrementAndGet();
            return Applied.ERASED;
        }

        @Override
        public Verified verify(EraseEntry entry) {
            return Verified.GONE;
        }
    }

    final Pg db = Pg.migrated();
    final DataSource app = db.appUser("select, insert, update");
    final TestClock clock = new TestClock();
    final InMemoryFieldStore fieldStore = new InMemoryFieldStore();
    final InMemoryValueStore valueStore = new InMemoryValueStore();
    final InMemoryKeyService keys = new InMemoryKeyService();
    final FieldValues values;
    final Documents documents = new Documents();
    final EraseHandlers handlers;
    final ErasePosition position = new PostgresErasePosition(app);

    RestoreAndReplayTest() {
        keys.createKeys(CUSTOMER);
        new FieldDefinitions(fieldStore, valueStore, clock).create(SCOPE, FieldSpec.builder("loan_number",
                "Loan number", FieldType.TEXT).primary(true).sensitive(false).build(), "u");
        new FieldDefinitions(fieldStore, valueStore, clock).create(SCOPE, FieldSpec.builder("borrower_name",
                "Borrower name", FieldType.TEXT).build(), "u");
        values = new FieldValues(CUSTOMER, fieldStore, valueStore, keys, clock);
        handlers = EraseHandlers.of(new CaseFieldsEraseHandler(values, valueStore), documents);
    }

    static RecordRef ref(String id) {
        return RecordRef.of(SCOPE, CASE, id);
    }

    void claim(String id, String loan) {
        values.claim(ref(id), Map.of("loan_number", List.of(FieldValue.text(loan)),
                "borrower_name", List.of(FieldValue.text("Alice Smith"))));
    }

    EraseRequest caseFields(String id) {
        return new EraseRequest(ENTITY, "PRODUCT_A", CASE, id, CaseFieldsEraseHandler.KIND, clock.instant(),
                ErasedBy.rule("RET-7Y"), "AUD-" + id);
    }

    EraseRequest document(String id) {
        return new EraseRequest(ENTITY, "PRODUCT_A", CASE, id, "document", clock.instant(), ErasedBy.person("u-42"),
                "AUD-" + id);
    }

    /** The database's erl_position rows (a backup holds them). */
    List<String> backupPositions() throws Exception {
        List<String> rows = new ArrayList<>();
        try (Connection c = db.owner.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select log_id, applied_seq, applied_hash from erl_position")) {
            while (rs.next()) {
                rows.add("insert into erl_position (log_id, applied_seq, applied_hash) values ('" + rs.getString(1)
                        + "', " + rs.getLong(2) + ", '" + rs.getString(3) + "')");
            }
        }
        return rows;
    }

    /** Restores the backup's rows (the schema owner, as a database restore would). */
    void restorePositions(List<String> backup) {
        List<String> sql = new ArrayList<>(List.of("truncate erl_position"));
        sql.addAll(backup);
        db.exec(sql.toArray(String[]::new));
    }

    @Test
    void aRestoredDatabaseTakesNoWorkUntilTheReplayHasRunAndVerified(@TempDir Path logDir) throws Exception {
        EraseLogSchema.verify(app);
        EraseLog log = new EraseLog(new FileEraseLogStore(logDir), clock);
        Eraser eraser = new Eraser(log, position, handlers);
        claim("c1", "LN-100");
        claim("c2", "LN-200");
        documents.stored.addAll(Set.of("d1", "d2"));
        eraser.erase(document("d0")); // before the backup: nothing there any more
        List<String> backup = backupPositions(); // the nightly backup
        Set<String> documentsBackup = Set.copyOf(documents.stored);

        clock.now = T0.plus(Duration.ofDays(3));
        Eraser.Erased e1 = eraser.erase(caseFields("c1"));
        assertEquals(EraseHandler.Applied.ERASED, e1.applied());
        assertEquals(2, e1.entry().seq());
        eraser.erase(document("d1"));
        assertEquals(EraseHandler.Applied.ALREADY_GONE, eraser.erase(caseFields("gone-1")).applied(),
                "a record that was never stored counts as done");
        assertEquals(FieldValuesError.VALUES_RECORD_UNKNOWN,
                assertThrows(FieldValuesException.class, () -> values.read(ref("c1"))).code());
        EraseGuard running = new EraseGuard(log, position, clock);
        assertTrue(running.checkAtStartup().mayTakeWork());

        // The database is restored from the backup: c1 and d1 are back, the applied position is the old one.
        clock.now = T0.plus(Duration.ofDays(4));
        restorePositions(backup);
        Instant created = T0.plusSeconds(60);
        Instant later = clock.now;
        clock.now = created;
        claim("c1", "LN-100");
        clock.now = later;
        documents.stored.clear();
        documents.stored.addAll(documentsBackup);
        assertEquals(Set.of("d1", "d2"), documents.stored);

        EraseGuard guard = new EraseGuard(log, position, clock);
        EraseGuard.State blocked = guard.checkAtStartup();
        assertFalse(blocked.mayTakeWork());
        assertEquals(EraseLogError.ERASE_LOG_RESTORE_DETECTED, blocked.code());
        assertTrue(blocked.message().startsWith("Restored from backup: run the erase replay before starting work"),
                blocked.message());
        assertEquals(3, blocked.pending());
        assertTrue(position.read(log.logId()).replayRequired(), "recorded in the database for every instance");
        assertFalse(running.refresh().mayTakeWork(), "another instance sees it on its next recheck");
        assertEquals(EraseLogError.ERASE_LOG_RESTORE_DETECTED, assertThrows(EraseLogException.class,
                () -> eraser.erase(caseFields("c2"))).code(), "no erasure before the replay");

        EraseReplay.Report report = new EraseReplay(log, position, handlers, clock).run();
        assertTrue(report.passed(), report.describe());
        assertEquals(2, report.applied().get(EraseHandler.Applied.ERASED));
        assertEquals(1, report.applied().get(EraseHandler.Applied.ALREADY_GONE));
        assertEquals(4, report.verified());
        assertEquals(FieldValuesError.VALUES_RECORD_UNKNOWN,
                assertThrows(FieldValuesException.class, () -> values.read(ref("c1"))).code());
        assertEquals(Set.of("d2"), documents.stored);
        assertTrue(guard.refresh().mayTakeWork(), "the guard lifts");
        assertTrue(running.refresh().mayTakeWork());
        assertTrue(new EraseGuard(log, position, clock).checkAtStartup().mayTakeWork());
        assertNull(position.read(log.logId()).restoreDetected());

        EraseReplay.Report twice = new EraseReplay(log, position, handlers, clock).run();
        assertTrue(twice.passed(), twice.describe());
        assertEquals(0, twice.appliedCount(), "safe to run twice");
        assertEquals(4, twice.verified());
        eraser.erase(caseFields("c2"));
        assertEquals(5, position.read(log.logId()).appliedSeq());
    }

    @Test
    void aStopBetweenTheLogWriteAndTheEraseIsFinishedByTheReplay(@TempDir Path logDir) {
        EraseLog log = new EraseLog(new FileEraseLogStore(logDir), clock);
        Eraser eraser = new Eraser(log, position, handlers);
        documents.stored.add("d1");
        documents.crash = true;
        EraseLogException e = assertThrows(EraseLogException.class, () -> eraser.erase(document("d1")));
        assertEquals(EraseLogError.ERASE_HANDLER_FAILED, e.code());
        assertEquals("ERASE_HANDLER_ERROR", e.handlerCode().orElseThrow());
        assertEquals(1, log.head().seq(), "the entry was written first");
        assertEquals(0, position.read(log.logId()).appliedSeq(), "the position did not move");
        assertTrue(documents.stored.contains("d1"));

        // The app restarts.
        documents.crash = false;
        EraseGuard guard = new EraseGuard(log, position, clock);
        assertEquals(EraseLogError.ERASE_LOG_RESTORE_DETECTED, guard.checkAtStartup().code());
        EraseReplay.Report r = new EraseReplay(log, position, handlers, clock).run();
        assertTrue(r.passed(), r.describe());
        assertEquals(1, r.appliedCount());
        assertFalse(documents.stored.contains("d1"));
        assertTrue(guard.refresh().mayTakeWork());
    }

    @Test
    void anEraseThatFailedHalfWayIsFinishedByTheNextEraseWhileRunning(@TempDir Path logDir) {
        EraseLog log = new EraseLog(new FileEraseLogStore(logDir), clock);
        Eraser eraser = new Eraser(log, position, handlers);
        documents.stored.addAll(Set.of("d1", "d2"));
        documents.failOn = "d1";
        EraseLogException e = assertThrows(EraseLogException.class, () -> eraser.erase(document("d1")));
        assertEquals("DOC_STORE_DOWN", e.handlerCode().orElseThrow());
        EraseGuard running = new EraseGuard(log, position, clock);
        assertTrue(running.refresh().mayTakeWork(), "while running, a pending entry is an erase in progress");
        assertEquals("DOC_STORE_DOWN", assertThrows(EraseLogException.class,
                () -> eraser.erase(document("d2"))).handlerCode().orElseThrow(), "later erasures wait for it");
        assertEquals(1, log.head().seq(), "and are not logged meanwhile");
        documents.failOn = null;
        Eraser.Erased next = eraser.erase(document("d2"));
        assertEquals(1, next.caughtUp());
        assertEquals(2, next.entry().seq());
        assertTrue(documents.stored.isEmpty());
    }

    @Test
    void aHandlerFailureStopsTheReplayWithItsCodeAndARerunGoesOn(@TempDir Path logDir) throws Exception {
        EraseLog log = new EraseLog(new FileEraseLogStore(logDir), clock);
        Eraser eraser = new Eraser(log, position, handlers);
        List<String> backup = backupPositions();
        documents.stored.addAll(Set.of("d1", "d2", "d3"));
        Set<String> before = Set.copyOf(documents.stored);
        for (String d : List.of("d1", "d2", "d3")) {
            eraser.erase(document(d));
        }
        restorePositions(backup);
        documents.stored.addAll(before);
        EraseGuard guard = new EraseGuard(log, position, clock);
        assertFalse(guard.checkAtStartup().mayTakeWork());

        documents.failOn = "d2";
        EraseReplay.Report stopped = new EraseReplay(log, position, handlers, clock).run();
        assertFalse(stopped.passed());
        assertEquals(EraseLogError.ERASE_HANDLER_FAILED, stopped.code());
        assertEquals("DOC_STORE_DOWN", stopped.handlerCode());
        assertEquals(2L, stopped.stoppedAt());
        assertTrue(stopped.describe().contains("NOT complete: ERASE_HANDLER_FAILED (DOC_STORE_DOWN)"),
                stopped.describe());
        assertEquals(1, position.read(log.logId()).appliedSeq(), "entry 1 stays applied");
        assertFalse(guard.refresh().mayTakeWork());

        documents.failOn = null;
        EraseReplay.Report rerun = new EraseReplay(log, position, handlers, clock).run();
        assertTrue(rerun.passed(), rerun.describe());
        assertEquals(1L, rerun.from());
        assertEquals(2, rerun.appliedCount());
        assertTrue(guard.refresh().mayTakeWork());
    }

    @Test
    void somethingStillReadableAfterTheReplayKeepsTheSystemBlocked(@TempDir Path logDir) throws Exception {
        EraseLog log = new EraseLog(new FileEraseLogStore(logDir), clock);
        Eraser eraser = new Eraser(log, position, handlers);
        List<String> backup = backupPositions();
        documents.stored.add("d1");
        eraser.erase(document("d1"));
        restorePositions(backup);
        documents.stored.add("d1");
        documents.claimsGoneButKeeps = true;
        EraseGuard guard = new EraseGuard(log, position, clock);
        guard.checkAtStartup();
        EraseReplay.Report r = new EraseReplay(log, position, handlers, clock).run();
        assertEquals(EraseLogError.ERASE_VERIFY_FAILED, r.code());
        assertEquals(List.of(1L), r.readable());
        assertFalse(guard.refresh().mayTakeWork());
        assertTrue(position.read(log.logId()).replayRequired());
    }

    @Test
    void theDatabaseNeverMovesItsPositionBackAndTheAppUserNeedsOnlyDml(@TempDir Path logDir) {
        EraseLog log = new EraseLog(new FileEraseLogStore(logDir), clock);
        documents.stored.add("d1");
        new Eraser(log, position, handlers).erase(document("d1"));
        IllegalStateException back = assertThrows(IllegalStateException.class,
                () -> Pg.exec(app, "update erl_position set applied_seq = 0"));
        assertTrue(back.getMessage().contains("never moves back"), back.getMessage());
        assertTrue(assertThrows(IllegalStateException.class, () -> db.exec("delete from erl_position"))
                .getMessage().contains("never deleted"));
        assertTrue(assertThrows(IllegalStateException.class, () -> Pg.exec(app, "create table x (i int)"))
                .getMessage().contains("permission denied"), "no DDL for the app user");

        Pg missing = Pg.empty();
        assertEquals(EraseLogSchemaException.Problem.ERL_TABLES_MISSING, assertThrows(EraseLogSchemaException.class,
                () -> EraseLogSchema.verify(missing.owner)).problem());
        Pg noUpdate = Pg.migrated();
        EraseLogSchemaException e = assertThrows(EraseLogSchemaException.class,
                () -> EraseLogSchema.verify(noUpdate.appUser("select, insert")));
        assertEquals(EraseLogSchemaException.Problem.ERL_PRIVILEGES_MISSING, e.problem());
        assertTrue(e.getMessage().contains("UPDATE on erl_position"), e.getMessage());
    }

    @Test
    void theWrongLogALostLogAndADatabaseOlderThanTheLogAreEachRefused(@TempDir Path dirs) throws Exception {
        EraseLog log = new EraseLog(new FileEraseLogStore(dirs.resolve("a")), clock);
        Eraser eraser = new Eraser(log, position, handlers);
        List<String> empty = backupPositions();
        for (String d : List.of("d1", "d2", "d3")) {
            eraser.erase(document(d));
        }

        EraseLog other = new EraseLog(new FileEraseLogStore(dirs.resolve("b")), clock);
        EraseGuard.State wrong = new EraseGuard(other, position, clock).checkAtStartup();
        assertEquals(EraseLogError.ERASE_LOG_WRONG_LOG, wrong.code());
        assertEquals(EraseLogError.ERASE_LOG_WRONG_LOG, new EraseReplay(other, position, handlers, clock).run().code());

        // The log is rolled back (restored from an older copy): the database has applied more than it holds.
        Path file = dirs.resolve("a").resolve(FileEraseLogStore.LOG_FILE);
        List<String> lines = Files.readAllLines(file);
        Files.write(file, lines.subList(0, 2));
        assertEquals(EraseLogError.ERASE_LOG_BEHIND_DATABASE, new EraseGuard(log, position, clock).checkAtStartup()
                .code());
        Files.write(file, lines);

        // Pruned past what a very old database had applied: the erasures in between cannot be replayed.
        clock.now = T0.plus(Duration.ofDays(70));
        assertEquals(3, log.prune(clock.instant(), EraseRetention.DEFAULT, position.read(log.logId()).appliedSeq()));
        assertTrue(new EraseGuard(log, position, clock).checkAtStartup().mayTakeWork(), "in step at the checkpoint");
        restorePositions(empty);
        EraseGuard.State gap = new EraseGuard(log, position, clock).checkAtStartup();
        assertEquals(EraseLogError.ERASE_LOG_GAP, gap.code());
        assertFalse(gap.mayTakeWork());
    }

    /** erase-log-22: the admin screen reads the pending entry and its handler's code from the guard's state. */
    @Test
    void anEntryThatCouldNotBeAppliedIsInTheGuardsStateUntilItIsApplied(@TempDir Path logDir) throws Exception {
        EraseLog log = new EraseLog(new FileEraseLogStore(logDir), clock);
        EraseGuard guard = new EraseGuard(log, position, clock);
        assertTrue(guard.checkAtStartup().mayTakeWork());
        Eraser eraser = new Eraser(log, position, handlers, guard);
        List<String> backup = backupPositions();
        documents.stored.addAll(Set.of("d1", "d2"));
        documents.failOn = "d1";
        assertThrows(EraseLogException.class, () -> eraser.erase(document("d1")));
        EraseGuard.ApplyFailure failed = new EraseGuard.ApplyFailure(1, "document", CASE, "d1", "DOC_STORE_DOWN",
                EraseLogError.ERASE_HANDLER_FAILED, T0);
        assertEquals(failed, guard.state().lastFailure());
        EraseGuard.State running = guard.refresh();
        assertTrue(running.mayTakeWork(), "an erase in progress");
        assertEquals(1, running.pending());
        assertEquals(failed, running.lastFailure(), "kept across the recheck");
        assertEquals(failed, guard.fullCheck().lastFailure(), "and the daily check");

        clock.now = T0.plusSeconds(5);
        assertThrows(EraseLogException.class, () -> eraser.erase(document("d2")));
        assertEquals(new EraseGuard.ApplyFailure(1, "document", CASE, "d1", "DOC_STORE_DOWN",
                EraseLogError.ERASE_HANDLER_FAILED, T0.plusSeconds(5)), guard.state().lastFailure(),
                "a later erasure stops at the same entry");

        documents.failOn = null;
        eraser.erase(document("d2"));
        assertNull(guard.refresh().lastFailure(), "cleared once the entry is applied");

        // The replay given the guard reports the entry it stopped at.
        restorePositions(backup);
        documents.stored.addAll(Set.of("d1", "d2"));
        assertFalse(guard.checkAtStartup().mayTakeWork());
        documents.failOn = "d2";
        EraseReplay replay = new EraseReplay(log, position, handlers, clock, guard);
        assertEquals(2L, replay.run().stoppedAt());
        EraseGuard.State blocked = guard.refresh();
        assertEquals(EraseLogError.ERASE_LOG_RESTORE_DETECTED, blocked.code());
        assertEquals(2, blocked.lastFailure().seq());
        assertEquals("d2", blocked.lastFailure().recordId());
        assertEquals("DOC_STORE_DOWN", blocked.lastFailure().handlerCode());
        documents.failOn = null;
        assertTrue(replay.run().passed());
        EraseGuard.State lifted = guard.refresh();
        assertTrue(lifted.mayTakeWork());
        assertNull(lifted.lastFailure());
    }

    /** erase-log-51: two instances (eraser, eraser, replay) on one database and one log location at once. */
    @Test
    void twoErasersAndAReplayAtOnceApplyEveryEntryOnceAndInOrder(@TempDir Path logDir) throws Exception {
        OrderedDocuments ordered = new OrderedDocuments();
        EraseHandlers one = EraseHandlers.of(ordered);
        // Entries logged by an instance that stopped before applying them: whoever takes the lock first applies them.
        EraseLog first = new EraseLog(new FileEraseLogStore(logDir), clock);
        for (int i = 0; i < 5; i++) {
            first.record(document("p" + i));
        }
        int each = 15;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<?>> done = new ArrayList<>();
        for (String instance : List.of("a", "b")) {
            Eraser eraser = new Eraser(new EraseLog(new FileEraseLogStore(logDir), clock),
                    new PostgresErasePosition(app), one);
            done.add(pool.submit(() -> {
                go.await();
                for (int i = 0; i < each; i++) {
                    eraser.erase(document(instance + i));
                }
                return null;
            }));
        }
        EraseReplay replay = new EraseReplay(new EraseLog(new FileEraseLogStore(logDir), clock),
                new PostgresErasePosition(app), one, clock);
        List<EraseReplay.Report> reports = java.util.Collections.synchronizedList(new ArrayList<>());
        done.add(pool.submit(() -> {
            go.await();
            for (int i = 0; i < 5; i++) {
                reports.add(replay.run());
            }
            return null;
        }));
        go.countDown();
        for (java.util.concurrent.Future<?> f : done) {
            f.get(2, java.util.concurrent.TimeUnit.MINUTES);
        }
        pool.shutdown();

        long total = 5 + 2L * each;
        List<Long> expected = java.util.stream.LongStream.rangeClosed(1, total).boxed().toList();
        assertEquals(expected, ordered.applied, "every entry once, in order");
        assertEquals(0, ordered.overlaps.get(), "one at a time");
        assertEquals(total, position.read(first.logId()).appliedSeq());
        EraseLog.ChainCheck chain = first.verify();
        assertTrue(chain.intact(), chain.message());
        assertEquals(total, chain.entries());
        for (EraseReplay.Report r : reports) {
            assertTrue(r.passed(), r.describe());
        }
    }

    /** erase-log-57: a handler erasing through {@code ctx.connection()} commits with the position or not at all. */
    @Test
    void aHandlersWriteThroughThePositionsConnectionCommitsWithThePositionOrNotAtAll(@TempDir Path logDir)
            throws Exception {
        db.exec("create table app_document (id text primary key)", "insert into app_document values ('d1'), ('d2')",
                "grant select, delete on app_document to " + db.schema + "_app");
        DbDocuments inDb = new DbDocuments();
        EraseLog log = new EraseLog(new FileEraseLogStore(logDir), clock);
        Eraser eraser = new Eraser(log, position, EraseHandlers.of(inDb));
        inDb.failAfterWrite = true;
        EraseLogException e = assertThrows(EraseLogException.class, () -> eraser.erase(document("d1")));
        assertEquals("DOC_STORE_DOWN", e.handlerCode().orElseThrow());
        assertEquals(List.of("d1", "d2"), documentRows(), "the handler's delete was rolled back");
        assertEquals(0, position.read(log.logId()).appliedSeq(), "and the position did not move");
        assertEquals(1, log.head().seq());

        inDb.failAfterWrite = false;
        Eraser.Erased next = eraser.erase(document("d2"));
        assertEquals(1, next.caughtUp());
        assertEquals(List.of(), documentRows(), "both deletes committed with the position");
        assertEquals(2, position.read(log.logId()).appliedSeq());
    }

    List<String> documentRows() throws Exception {
        List<String> ids = new ArrayList<>();
        try (Connection c = db.owner.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select id from app_document order by id")) {
            while (rs.next()) {
                ids.add(rs.getString(1));
            }
        }
        return ids;
    }

    /** erase-log-67: the daily full check sees a pending entry as an erase in progress and records nothing. */
    @Test
    void theFullCheckWhileRunningNeverRecordsARestore(@TempDir Path logDir) {
        EraseLog log = new EraseLog(new FileEraseLogStore(logDir), clock);
        Eraser eraser = new Eraser(log, position, handlers);
        documents.stored.add("d1");
        documents.failOn = "d1";
        assertThrows(EraseLogException.class, () -> eraser.erase(document("d1")));
        EraseGuard guard = new EraseGuard(log, position, clock);
        EraseGuard.State s = guard.fullCheck();
        assertTrue(s.mayTakeWork(), s.message());
        assertNull(s.code());
        assertTrue(s.chainChecked());
        assertEquals(1, s.pending());
        assertEquals("In step; 1 erase(s) in progress", s.message());
        assertFalse(position.read(log.logId()).replayRequired(), "nothing recorded in the database");
        assertTrue(guard.refresh().mayTakeWork());
        assertTrue(new EraseGuard(log, position, clock).refresh().mayTakeWork(), "nor for another instance");
    }

    /** erase-log-73: a verify check that throws is {@code ERASE_VERIFY_FAILED} with its code; the system stays blocked. */
    @Test
    void aVerifyCheckThatThrowsKeepsTheSystemBlockedWithItsCode(@TempDir Path logDir) throws Exception {
        EraseLog log = new EraseLog(new FileEraseLogStore(logDir), clock);
        List<String> backup = backupPositions();
        documents.stored.add("d1");
        new Eraser(log, position, handlers).erase(document("d1"));
        restorePositions(backup);
        EraseGuard guard = new EraseGuard(log, position, clock);
        assertFalse(guard.checkAtStartup().mayTakeWork());
        EraseReplay replay = new EraseReplay(log, position, handlers, clock);

        documents.verifyFailure = new EraseHandlerException("DOC_STORE_DOWN", "the document store did not answer");
        EraseReplay.Report refused = replay.run();
        assertEquals(EraseLogError.ERASE_VERIFY_FAILED, refused.code());
        assertEquals("DOC_STORE_DOWN", refused.handlerCode());
        assertTrue(refused.describe().contains("NOT complete: ERASE_VERIFY_FAILED (DOC_STORE_DOWN)"),
                refused.describe());
        assertEquals(1, refused.appliedCount(), "the replay itself ran");
        assertFalse(guard.refresh().mayTakeWork());
        assertTrue(position.read(log.logId()).replayRequired());

        documents.verifyFailure = new IllegalStateException("the store's client broke");
        EraseReplay.Report crashed = replay.run();
        assertEquals(EraseLogError.ERASE_VERIFY_FAILED, crashed.code());
        assertEquals(EraseLogError.ERASE_HANDLER_ERROR.name(), crashed.handlerCode());
        assertFalse(crashed.message().contains("the store's client broke"), crashed.message());
        assertFalse(guard.refresh().mayTakeWork());
        assertTrue(position.read(log.logId()).replayRequired());

        documents.verifyFailure = null;
        assertTrue(replay.run().passed());
        assertTrue(guard.refresh().mayTakeWork());
    }
}
