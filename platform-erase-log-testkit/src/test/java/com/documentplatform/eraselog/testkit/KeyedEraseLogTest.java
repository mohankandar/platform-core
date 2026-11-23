package com.documentplatform.eraselog.testkit;

import com.documentplatform.eraselog.ChainKey;
import com.documentplatform.eraselog.ChainPoint;
import com.documentplatform.eraselog.EraseEntry;
import com.documentplatform.eraselog.EraseGuard;
import com.documentplatform.eraselog.EraseHandlers;
import com.documentplatform.eraselog.EraseLog;
import com.documentplatform.eraselog.EraseLogError;
import com.documentplatform.eraselog.EraseLogException;
import com.documentplatform.eraselog.EraseReplay;
import com.documentplatform.eraselog.EraseRequest;
import com.documentplatform.eraselog.ErasePosition;
import com.documentplatform.eraselog.Eraser;
import com.documentplatform.eraselog.Seal;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static com.documentplatform.eraselog.testkit.EraseLogInMemoryTest.CLOCK;
import static com.documentplatform.eraselog.testkit.EraseLogInMemoryTest.req;
import static org.junit.jupiter.api.Assertions.*;

/**
 * P044, FIX-B: the keyed erase log (SEC-3) and a restore while running (SEC-4). Each test reproduces the review's
 * attack or mistake: without the fix a forged entry is erased by the next erase or the replay, and a database restored
 * under running instances keeps taking work.
 */
class KeyedEraseLogTest {

    /** A key with a fixed secret: HMAC-SHA256, version 1 (the built key is the key service's fingerprint key). */
    static ChainKey key(String secret) {
        return new ChainKey() {
            @Override
            public int version() {
                return 1;
            }

            @Override
            public byte[] mac(int version, byte[] input) {
                try {
                    Mac m = Mac.getInstance("HmacSHA256");
                    m.init(new SecretKeySpec((secret + version).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                    return m.doFinal(input);
                } catch (java.security.GeneralSecurityException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    final ChainKey key = key("the log's key");
    final InMemoryEraseLogStore store = new InMemoryEraseLogStore();
    final InMemoryErasePosition position = new InMemoryErasePosition();
    final RecordingEraseHandler docs = new RecordingEraseHandler("document").store("a").store("b").store("victim");
    final EraseLog log = new EraseLog(store, CLOCK, key);

    Eraser eraser() {
        return new Eraser(log, position, EraseHandlers.of(docs));
    }

    static EraseRequest doc(String id) {
        return req("document", id);
    }

    // ---- SEC-3: forging ----

    @Test
    void aNewKeyedLogIsSealedAtItsFirstEntryAndEveryEntryCarriesAMac() {
        eraser().erase(doc("a"));
        Seal seal = log.sealRecord().orElseThrow();
        assertEquals(1, seal.from());
        assertEquals(EraseEntry.GENESIS, seal.anchor());
        EraseEntry e = log.after(0).get(0);
        assertTrue(e.keyed() && e.macMatches(key));
        assertTrue(log.verify().intact(), log.verify().message());
    }

    @Test
    void anEntryForgedWithoutTheKeyIsNeverErasedByTheNextErase() {
        eraser().erase(doc("a"));
        store.forge(doc("victim"), CLOCK.instant(), null); // the hash chain is right; no MAC

        EraseLogException e = assertThrows(EraseLogException.class, () -> eraser().erase(doc("b")));
        assertEquals(EraseLogError.ERASE_LOG_CHAIN_BROKEN, e.code());
        assertTrue(e.reason().contains("entry 2"), e.reason());
        assertTrue(docs.present().contains("victim"), "the forged erasure was acted on");
        EraseLog.ChainCheck chain = log.verify();
        assertFalse(chain.intact());
        assertTrue(chain.message().startsWith("entry 2 has no valid MAC"), chain.message());
    }

    @Test
    void anEntryForgedWithAnotherKeyIsRefusedToo() {
        eraser().erase(doc("a"));
        store.forge(doc("victim"), CLOCK.instant(), key("someone else's key"));
        assertThrows(EraseLogException.class, () -> eraser().erase(doc("b")));
        assertTrue(docs.present().contains("victim"));
    }

    @Test
    void theReplayAfterARestoreNeverActsOnAForgedEntry() {
        Map<UUID, ErasePosition.Position> backup = position.snapshot();
        eraser().erase(doc("a"));
        position.restore(backup);
        store.forge(doc("victim"), CLOCK.instant(), null);

        EraseReplay.Report r = new EraseReplay(log, position, EraseHandlers.of(docs), CLOCK).run();
        assertFalse(r.passed());
        assertEquals(EraseLogError.ERASE_LOG_CHAIN_BROKEN, r.code());
        assertTrue(docs.present().contains("victim"), "the replay erased a forged entry");
    }

    @Test
    void aRemovedOrChangedSealStopsWork() {
        eraser().erase(doc("a"));
        store.dropSeal();
        assertEquals(EraseLogError.ERASE_LOG_NOT_SEALED,
                assertThrows(EraseLogException.class, () -> eraser().erase(doc("b"))).code());
        assertEquals(EraseLogError.ERASE_LOG_NOT_SEALED, log.verify().code());
        assertTrue(docs.present().contains("b"));
    }

    // ---- SEC-3: the migration of an existing log ----

    @Test
    void anExistingLogIsSealedOnceExplicitlyAndItsOldEntriesAreFixedByTheSeal() {
        // Written before this release: unkeyed.
        EraseLog old = new EraseLog(store, CLOCK);
        new Eraser(old, position, EraseHandlers.of(docs)).erase(doc("a"));
        new Eraser(old, position, EraseHandlers.of(docs)).erase(doc("b"));

        // Keyed now: no work until the explicit migration ran.
        assertEquals(EraseLogError.ERASE_LOG_NOT_SEALED, log.verify().code());
        assertEquals(EraseLogError.ERASE_LOG_NOT_SEALED,
                assertThrows(EraseLogException.class, () -> eraser().erase(doc("victim"))).code());

        Seal seal = log.seal();
        assertEquals(3, seal.from(), "keyed from the next entry on");
        assertEquals(seal, log.seal(), "safe to run again");
        assertEquals(2, store.size(), "no entry rewritten");
        assertTrue(log.verify().intact(), log.verify().message());

        // Someone rewrites the old, unkeyed entries into a different but consistent chain before any keyed entry
        // follows them: only the seal can catch it, and it does; no work is taken.
        EraseEntry e1 = EraseEntry.link(store.logId(), ChainPoint.START, CLOCK.instant(), doc("other-1"));
        EraseEntry e2 = EraseEntry.link(store.logId(), e1.point(), CLOCK.instant(), doc("other-2"));
        EraseEntry kept1 = log.after(0).get(0);
        EraseEntry kept2 = log.after(1).get(0);
        store.replace(1, x -> e1);
        store.replace(2, x -> e2);
        EraseLog.ChainCheck chain = log.verify();
        assertEquals(EraseLogError.ERASE_LOG_CHAIN_BROKEN, chain.code());
        assertTrue(chain.message().contains("sealed"), chain.message());
        assertThrows(EraseLogException.class, () -> eraser().erase(doc("victim")));

        // Put back: the next entry is keyed.
        store.replace(1, x -> kept1);
        store.replace(2, x -> kept2);
        eraser().erase(doc("victim"));
        assertTrue(log.after(2).get(0).macMatches(key));
        assertTrue(log.verify().intact());
    }

    @Test
    void aBrokenChainIsNeverSealed() {
        EraseLog old = new EraseLog(store, CLOCK);
        new Eraser(old, position, EraseHandlers.of(docs)).erase(doc("a"));
        store.replace(1, e -> new EraseEntry(e.logId(), e.seq(), e.loggedAt(), doc("changed"), e.prev(), e.hash()));
        assertEquals(EraseLogError.ERASE_LOG_CHAIN_BROKEN, assertThrows(EraseLogException.class, log::seal).code());
        assertTrue(store.seal().isEmpty());
    }

    // ---- SEC-4: a restore while running ----

    @Test
    void aDatabaseRestoredWhileTheAppKeepsRunningIsNoticedByTheRecheckAndTheNextErase() {
        EraseGuard guard = new EraseGuard(log, position, CLOCK);
        eraser().erase(doc("a"));
        Map<UUID, ErasePosition.Position> backup = position.snapshot();
        eraser().erase(doc("b"));
        eraser().erase(doc("victim"));
        assertTrue(guard.checkAtStartup().mayTakeWork());

        position.restore(backup); // restored under the running instances, no restart
        EraseGuard.State s = guard.refresh();
        assertFalse(s.mayTakeWork(), "the restored database kept taking work");
        assertEquals(EraseLogError.ERASE_LOG_RESTORE_DETECTED, s.code());
        assertTrue(position.read(log.logId()).replayRequired(), "recorded for every instance");
        assertEquals(EraseLogError.ERASE_LOG_RESTORE_DETECTED,
                assertThrows(EraseLogException.class, () -> eraser().erase(doc("x"))).code());

        docs.reset(java.util.Set.of("b", "victim")); // what the restored backup brought back
        EraseReplay.Report r = new EraseReplay(log, position, EraseHandlers.of(docs), CLOCK).run();
        assertTrue(r.passed(), r.describe());
        assertTrue(guard.refresh().mayTakeWork());
        assertFalse(docs.present().contains("victim"));
    }

    @Test
    void theEraseItselfSeesARestoreWithoutAnyRecheck() {
        eraser().erase(doc("a"));
        Map<UUID, ErasePosition.Position> backup = position.snapshot();
        eraser().erase(doc("b"));
        position.restore(backup);
        assertEquals(EraseLogError.ERASE_LOG_RESTORE_DETECTED,
                assertThrows(EraseLogException.class, () -> eraser().erase(doc("victim"))).code());
        assertTrue(position.read(log.logId()).replayRequired());
    }

    @Test
    void aHandlerThatFailedIsAnEraseInProgressNotARestore() {
        EraseGuard guard = new EraseGuard(log, position, CLOCK);
        eraser().erase(doc("a"));
        docs.failWith("DOC_STORE_DOWN");
        assertThrows(EraseLogException.class, () -> eraser().erase(doc("b")));
        EraseGuard.State s = guard.refresh();
        assertTrue(s.mayTakeWork(), s.message());
        assertEquals(1, s.pending());
        docs.failWith(null);
        assertEquals(1, eraser().erase(doc("victim")).caughtUp(), "the next erase finishes the earlier one");
    }
}
