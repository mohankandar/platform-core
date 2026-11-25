package com.documentplatform.eraselog;

import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.testkit.InMemoryKeyService;
import com.documentplatform.eraselog.crypto.KeyServiceChainKey;
import com.documentplatform.eraselog.file.FileEraseLogStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static com.documentplatform.eraselog.FileEraseLogStoreTest.T0;
import static com.documentplatform.eraselog.FileEraseLogStoreTest.req;
import static org.junit.jupiter.api.Assertions.*;

/**
 * P044, FIX-B on the real file store and key service: the keyed chain (SEC-3) with the built key
 * ({@link KeyServiceChainKey}), the seal and the applied high-water mark kept with the log (SEC-4), and the schema's
 * guard on a recorded restore (SEC-49, schema V2).
 */
class KeyedFileEraseLogTest {

    @TempDir
    Path dir;

    final CustomerId customer = CustomerId.fromTenantId("TEN-TEST-0001");
    final InMemoryKeyService keys = new InMemoryKeyService();

    KeyedFileEraseLogTest() {
        keys.createKeys(customer);
    }

    EraseLog keyed(Path at) {
        return new EraseLog(new FileEraseLogStore(at), Clock.systemUTC(), new KeyServiceChainKey(keys, customer));
    }

    @Test
    void entriesWrittenThroughTheFolderWithoutTheKeyAreFoundAndNamed() {
        EraseLog log = keyed(dir);
        log.record(req("rec-1"));
        log.record(req("rec-2"));
        assertTrue(log.verify().intact(), log.verify().message());
        assertTrue(Files.exists(dir.resolve(FileEraseLogStore.SEAL_FILE)));
        String line = assertDoesNotThrow(() -> Files.readAllLines(dir.resolve(FileEraseLogStore.LOG_FILE)).get(0));
        assertTrue(line.contains("\"kv\":") && line.contains("\"mac\":"), line);

        // Someone who can write the log's folder appends a correctly chained entry, without the key.
        new FileEraseLogStore(dir).append(req("victim"), T0);
        EraseLog.ChainCheck chain = keyed(dir).verify();
        assertEquals(EraseLogError.ERASE_LOG_CHAIN_BROKEN, chain.code());
        assertTrue(chain.message().startsWith("entry 3 has no valid MAC"), chain.message());
        assertEquals(2, chain.head().seq(), "the last good entry");
    }

    /** P055 (LV-24.7): a new log has nothing to verify and nothing pending; its first entry seals it. */
    @Test
    void aNewEmptyLogWorksAndIsSealedByItsFirstEntry() {
        EraseLog log = keyed(dir);
        assertTrue(log.verify().intact(), log.verify().message());
        assertEquals(0, log.verify().entries());
        assertTrue(log.pending(ChainPoint.START).isEmpty());
        assertTrue(log.sealRecord().isEmpty(), "reading seals nothing");
        log.record(req("rec-1"));
        assertTrue(log.sealRecord().isPresent());
        assertTrue(log.verify().intact());
        assertEquals(1, log.pending(ChainPoint.START).size());
    }

    @Test
    void aLogWithEntriesAndNoSealIsStillRefused() {
        new FileEraseLogStore(dir).append(req("old"), T0);
        EraseLog log = keyed(dir);
        assertEquals(EraseLogError.ERASE_LOG_NOT_SEALED, log.verify().code());
        assertEquals(EraseLogError.ERASE_LOG_NOT_SEALED,
                assertThrows(EraseLogException.class, () -> log.pending(ChainPoint.START)).code());
        assertEquals(EraseLogError.ERASE_LOG_NOT_SEALED,
                assertThrows(EraseLogException.class, () -> log.record(req("rec-1"))).code());
    }

    @Test
    void anEmptiedLogThatWasUsedIsNotNew() throws Exception {
        keyed(dir).record(req("rec-1"));
        new FileEraseLogStore(dir).recordApplied(1);
        Files.delete(dir.resolve(FileEraseLogStore.LOG_FILE));
        Files.delete(dir.resolve(FileEraseLogStore.SEAL_FILE));
        assertEquals(EraseLogError.ERASE_LOG_NOT_SEALED, keyed(dir).verify().code());
        assertEquals(EraseLogError.ERASE_LOG_NOT_SEALED,
                assertThrows(EraseLogException.class, () -> keyed(dir).pending(ChainPoint.START)).code());
    }

    /** P055 (LV-22): the log keeps its times as text, whole; a nanosecond clock breaks nothing. */
    @Test
    void aNanosecondClockKeepsTheChainIntact() {
        Clock nanos = Clock.fixed(Instant.parse("2026-11-20T10:00:00.123456789Z"), java.time.ZoneOffset.UTC);
        for (int i = 1; i <= 3; i++) {
            new EraseLog(new FileEraseLogStore(dir), nanos, new KeyServiceChainKey(keys, customer)).record(req("rec-" + i));
        }
        EraseLog reopened = keyed(dir);
        assertTrue(reopened.verify().intact(), reopened.verify().message());
        assertEquals(nanos.instant(), reopened.after(0).get(0).loggedAt());
    }

    @Test
    void theSealIsKeptWithTheLogAndAChangedSealStopsWork() throws Exception {
        keyed(dir).record(req("rec-1"));
        EraseLog reopened = keyed(dir);
        assertTrue(reopened.verify().intact());
        Path seal = dir.resolve(FileEraseLogStore.SEAL_FILE);
        Files.writeString(seal, Files.readString(seal).replace("\"from\":1", "\"from\":2"));
        assertEquals(EraseLogError.ERASE_LOG_NOT_SEALED, keyed(dir).verify().code());
        assertEquals(EraseLogError.ERASE_LOG_NOT_SEALED,
                assertThrows(EraseLogException.class, () -> keyed(dir).record(req("rec-2"))).code());
    }

    @Test
    void anotherCustomersKeyDoesNotOpenTheSeal() {
        keyed(dir).record(req("rec-1"));
        CustomerId other = CustomerId.fromTenantId("TEN-TEST-0002");
        keys.createKeys(other);
        EraseLog wrong = new EraseLog(new FileEraseLogStore(dir), Clock.systemUTC(), new KeyServiceChainKey(keys, other));
        assertEquals(EraseLogError.ERASE_LOG_NOT_SEALED, wrong.verify().code());
    }

    @Test
    void aKeyServiceThatDoesNotAnswerStopsTheWriteWithItsOwnCode() {
        EraseLog log = keyed(dir);
        log.record(req("rec-1"));
        keys.down(true);
        assertEquals(EraseLogError.ERASE_LOG_KEY_UNAVAILABLE,
                assertThrows(EraseLogException.class, () -> log.record(req("rec-2"))).code());
        keys.down(false);
        assertEquals(1, log.head().seq(), "nothing written without its MAC");
    }

    @Test
    void theAppliedHighWaterMarkIsKeptWithTheLogAndNeverGoesDown() {
        FileEraseLogStore store = new FileEraseLogStore(dir);
        assertEquals(0, store.appliedHighWater());
        store.recordApplied(3);
        store.recordApplied(2);
        assertEquals(3, new FileEraseLogStore(dir).appliedHighWater(), "kept across a restart, never lowered");
    }

    @Test
    void aRecordedRestoreIsClearedOnlyByAVerifiedReplay() throws SQLException {
        Pg db = Pg.migrated();
        UUID log = UUID.randomUUID();
        db.exec("insert into erl_position (log_id, applied_seq, applied_hash, replay_required) values ('" + log
                + "', 1, '" + EraseEntry.GENESIS + "', true)");
        try (Connection c = db.owner.getConnection(); Statement s = c.createStatement()) {
            SQLException e = assertThrows(SQLException.class,
                    () -> s.execute("update erl_position set replay_required = false"));
            assertTrue(e.getMessage().contains("verified erase replay"), e.getMessage());
        }
        db.exec("update erl_position set replay_required = false, verified_at = now()"); // what the replay writes
    }
}
