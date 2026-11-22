package com.documentplatform.eraselog.testkit;

import com.documentplatform.eraselog.EraseEntry;
import com.documentplatform.eraselog.EraseGuard;
import com.documentplatform.eraselog.EraseHandler;
import com.documentplatform.eraselog.EraseHandlers;
import com.documentplatform.eraselog.EraseLog;
import com.documentplatform.eraselog.EraseLogError;
import com.documentplatform.eraselog.EraseLogException;
import com.documentplatform.eraselog.EraseReplay;
import com.documentplatform.eraselog.EraseRequest;
import com.documentplatform.eraselog.EraseRetention;
import com.documentplatform.eraselog.ErasedBy;
import com.documentplatform.eraselog.Eraser;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The same rules with the in-memory store and position, as the apps' tests use them. */
class EraseLogInMemoryTest {

    static final UUID ENTITY = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-22T10:00:00Z"), ZoneOffset.UTC);

    final InMemoryEraseLogStore store = new InMemoryEraseLogStore();
    final InMemoryErasePosition position = new InMemoryErasePosition();
    final RecordingEraseHandler documents = new RecordingEraseHandler("document");
    final RecordingEraseHandler copies = new RecordingEraseHandler("redacted-copy");
    final EraseHandlers handlers = EraseHandlers.of(documents, copies);
    final EraseLog log = new EraseLog(store, CLOCK);
    final Eraser eraser = new Eraser(log, position, handlers);

    static EraseRequest req(String kind, String id) {
        return new EraseRequest(ENTITY, "PRODUCT_A", "case", id, kind, CLOCK.instant(), ErasedBy.rule("RET-7Y"),
                "AUD-" + id);
    }

    @Test
    void aRestoreIsBlockedUntilTheReplayVerifiesAndTwiceIsSafe() {
        documents.store("d1").store("d2");
        copies.store("d1");
        var backup = position.snapshot();
        Set<String> docs = documents.present();
        Set<String> copiesBackup = copies.present();
        eraser.erase(req("document", "d1"));
        eraser.erase(req("redacted-copy", "d1"));
        position.restore(backup);
        documents.reset(docs);
        copies.reset(copiesBackup);

        EraseGuard guard = new EraseGuard(log, position, CLOCK);
        assertEquals(EraseLogError.ERASE_LOG_RESTORE_DETECTED, guard.checkAtStartup().code());
        EraseReplay replay = new EraseReplay(log, position, handlers, CLOCK);
        EraseReplay.Report r = replay.run();
        assertTrue(r.passed(), r.describe());
        assertEquals(2, r.appliedCount());
        assertEquals(Set.of("d2"), documents.present());
        assertEquals(Set.of(), copies.present());
        assertTrue(guard.refresh().mayTakeWork());
        assertTrue(replay.run().passed());
        assertEquals(0, replay.run().appliedCount());
    }

    @Test
    void aChangedOrMissingEntryIsDetectedAndBlocksWork() {
        for (int i = 1; i <= 3; i++) {
            documents.store("d" + i);
            eraser.erase(req("document", "d" + i));
        }
        assertTrue(log.verify().intact());
        store.replace(2, e -> new EraseEntry(e.logId(), e.seq(), e.loggedAt(), req("document", "d9"), e.prev(),
                e.hash()));
        assertEquals(EraseLogError.ERASE_LOG_CHAIN_BROKEN, log.verify().code());
        EraseGuard guard = new EraseGuard(log, position, CLOCK);
        assertEquals(EraseLogError.ERASE_LOG_CHAIN_BROKEN, guard.checkAtStartup().code());
        assertFalse(guard.refresh().mayTakeWork(), "only a full check clears a broken chain");
        assertEquals(EraseLogError.ERASE_LOG_CHAIN_BROKEN, new EraseReplay(log, position, handlers, CLOCK).run()
                .code());
        assertThrows(EraseLogException.class, () -> log.prune(CLOCK.instant().plus(Duration.ofDays(400)),
                EraseRetention.DEFAULT, 3), "a broken chain is not pruned");

        InMemoryEraseLogStore other = new InMemoryEraseLogStore();
        EraseLog second = new EraseLog(other, CLOCK);
        second.record(req("document", "x1"));
        second.record(req("document", "x2"));
        other.remove(1);
        assertEquals(EraseLogError.ERASE_LOG_CHAIN_BROKEN, second.verify().code());
    }

    @Test
    void anUnreachableLogOrDatabaseBlocksWorkWithItsCode() {
        store.down(true);
        EraseGuard guard = new EraseGuard(log, position, CLOCK);
        assertEquals(EraseLogError.ERASE_LOG_UNREACHABLE, guard.checkAtStartup().code());
        assertEquals(EraseLogError.ERASE_LOG_UNREACHABLE, guard.refresh().code());
        assertEquals(EraseLogError.ERASE_LOG_UNREACHABLE, assertThrows(EraseLogException.class,
                () -> eraser.erase(req("document", "d1"))).code());
        store.down(false);
        position.down(true);
        assertEquals(EraseLogError.ERASE_POSITION_FAILED, guard.checkAtStartup().code());
        position.down(false);
        assertTrue(guard.checkAtStartup().mayTakeWork());
        assertFalse(new EraseGuard(log, position, CLOCK).mayTakeWork(), "not checked yet: no work");
    }

    @Test
    void aKindWithoutHandlerOrARefusalLogsNothing() {
        assertEquals(EraseLogError.ERASE_HANDLER_MISSING, assertThrows(EraseLogException.class,
                () -> eraser.erase(req("export-copy", "e1"))).code());
        documents.refuse("DOC_LEGAL_HOLD");
        EraseLogException refused = assertThrows(EraseLogException.class, () -> eraser.erase(req("document", "d1")));
        assertEquals("DOC_LEGAL_HOLD", refused.handlerCode().orElseThrow());
        assertEquals(0, store.size());
        assertThrows(IllegalArgumentException.class, () -> EraseHandlers.of(documents,
                new RecordingEraseHandler("document")), "one handler per kind");

        // An entry whose kind lost its handler (app changed) stops the replay with its code.
        documents.refuse(null);
        documents.store("d1");
        eraser.erase(req("document", "d1"));
        position.restore(java.util.Map.of());
        EraseReplay.Report r = new EraseReplay(log, position, EraseHandlers.of(copies), CLOCK).run();
        assertEquals(EraseLogError.ERASE_HANDLER_MISSING, r.code());
    }

    @Test
    void aCrashRightAfterTheLogWriteIsFinishedByTheReplay() {
        documents.store("d1").crash(true);
        EraseLogException e = assertThrows(EraseLogException.class, () -> eraser.erase(req("document", "d1")));
        assertEquals(EraseLogError.ERASE_HANDLER_FAILED, e.code());
        assertEquals(1, store.size());
        documents.crash(false);
        EraseGuard guard = new EraseGuard(log, position, CLOCK);
        assertEquals(EraseLogError.ERASE_LOG_RESTORE_DETECTED, guard.checkAtStartup().code());
        assertTrue(new EraseReplay(log, position, handlers, CLOCK).run().passed());
        assertEquals(Set.of(), documents.present());
        assertEquals(EraseHandler.Applied.ALREADY_GONE, eraser.erase(req("document", "d1")).applied());
        assertTrue(guard.refresh().mayTakeWork());
    }

    @Test
    void backupRetentionIsThirtyToNinetyDaysAndTheLogKeepsThirtyMore() {
        assertEquals(65, EraseRetention.DEFAULT.logRetentionDays());
        assertEquals(Duration.ofDays(120), new EraseRetention(90).logRetention());
        assertEquals(EraseLogError.ERASE_LOG_SETTINGS_INVALID, assertThrows(EraseLogException.class,
                () -> new EraseRetention(29)).code());
        assertEquals(EraseLogError.ERASE_LOG_SETTINGS_INVALID, assertThrows(EraseLogException.class,
                () -> new EraseRetention(91)).code());
    }
}
