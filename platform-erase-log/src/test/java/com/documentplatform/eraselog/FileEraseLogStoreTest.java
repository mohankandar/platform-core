package com.documentplatform.eraselog;

import com.documentplatform.eraselog.file.FileEraseLogStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The append-only file store: durable lines, the chain, tamper detection, cut-short lines, pruning. */
class FileEraseLogStoreTest {

    static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");

    static EraseRequest req(String recordId) {
        return EraseRequestTest.request(recordId, ErasedBy.rule("RET-7Y"), "AUD-" + recordId);
    }

    @Test
    void entriesAreNumberedChainedAndReadBackAfterReopening(@TempDir Path dir) throws Exception {
        FileEraseLogStore store = new FileEraseLogStore(dir);
        EraseEntry a = store.append(req("rec-1"), T0);
        EraseEntry b = store.append(req("rec-2"), T0.plusSeconds(1));
        assertEquals(1, a.seq());
        assertEquals(EraseEntry.GENESIS, a.prev());
        assertEquals(a.hash(), b.prev());
        assertTrue(b.hashMatches());

        FileEraseLogStore again = new FileEraseLogStore(dir);
        assertEquals(store.logId(), again.logId(), "the log's ID is made once");
        assertEquals(b.point(), again.head());
        assertEquals(List.of(b), again.after(1));
        assertEquals(List.of(a, b), again.after(0));
        EraseEntry c = again.append(req("rec-3"), T0.plusSeconds(2));
        assertEquals(3, c.seq());
        assertEquals(c.point(), store.head(), "two stores on one location share the log");
        assertTrue(new EraseLog(again, Clock.systemUTC()).verify().intact());

        String text = Files.readString(dir.resolve(FileEraseLogStore.LOG_FILE));
        assertEquals(3, text.lines().count());
        assertTrue(text.startsWith("{\"seq\":1,\"loggedAt\":\"2026-09-01T10:00:00Z\",\"entity\":"), text);
        assertTrue(text.endsWith("\n"));
    }

    @Test
    void severalWritersAtOnceKeepOneChain(@TempDir Path dir) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> done = new ArrayList<>();
        for (int w = 0; w < 4; w++) {
            int writer = w;
            FileEraseLogStore store = new FileEraseLogStore(dir); // one store per "instance"
            done.add(pool.submit(() -> {
                for (int i = 0; i < 25; i++) {
                    store.append(req("w" + writer + "-" + i), Instant.now());
                }
            }));
        }
        for (Future<?> f : done) {
            f.get();
        }
        pool.shutdown();
        EraseLog.ChainCheck chain = new EraseLog(new FileEraseLogStore(dir), Clock.systemUTC()).verify();
        assertTrue(chain.intact(), chain.message());
        assertEquals(100, chain.entries());
        assertEquals(100, chain.head().seq());
    }

    @Test
    void aChangedOrRemovedLineBreaksTheChain(@TempDir Path dir) throws Exception {
        FileEraseLogStore store = new FileEraseLogStore(dir);
        for (int i = 1; i <= 4; i++) {
            store.append(req("rec-" + i), T0.plusSeconds(i));
        }
        Path file = dir.resolve(FileEraseLogStore.LOG_FILE);
        String original = Files.readString(file);
        EraseLog log = new EraseLog(store, Clock.systemUTC());

        Files.writeString(file, original.replace("\"recordId\":\"rec-2\"", "\"recordId\":\"rec-9\""));
        EraseLog.ChainCheck changed = log.verify();
        assertEquals(EraseLogError.ERASE_LOG_CHAIN_BROKEN, changed.code());
        assertTrue(changed.message().contains("entry 2 was changed"), changed.message());

        List<String> lines = new ArrayList<>(original.lines().toList());
        lines.remove(2);
        Files.writeString(file, String.join("\n", lines) + "\n");
        assertEquals(EraseLogError.ERASE_LOG_CHAIN_BROKEN, log.verify().code());
        assertTrue(log.verify().message().contains("entry 3 is missing"), log.verify().message());

        lines = new ArrayList<>(original.lines().toList());
        java.util.Collections.swap(lines, 1, 2);
        Files.writeString(file, String.join("\n", lines) + "\n");
        assertEquals(EraseLogError.ERASE_LOG_CHAIN_BROKEN, log.verify().code(), "reordered");

        // A value slipped in by hand is not even read.
        Files.writeString(file, original.replaceFirst("\"auditId\"", "\"value\":\"Philippa\",\"auditId\""));
        EraseLog.ChainCheck slipped = log.verify();
        assertEquals(EraseLogError.ERASE_LOG_FORMAT, slipped.code());
        assertTrue(slipped.message().contains("unknown field value"), slipped.message());
        assertFalse(slipped.message().contains("Philippa"));
    }

    @Test
    void aLineCutShortByACrashIsIgnoredAndRemovedBeforeTheNextWrite(@TempDir Path dir) throws Exception {
        FileEraseLogStore store = new FileEraseLogStore(dir);
        EraseEntry a = store.append(req("rec-1"), T0);
        Path file = dir.resolve(FileEraseLogStore.LOG_FILE);
        Files.writeString(file, "{\"seq\":2,\"loggedAt\":\"2026-09-", StandardOpenOption.APPEND);
        assertEquals(a.point(), store.head(), "never confirmed, so not there");
        assertTrue(new EraseLog(store, Clock.systemUTC()).verify().intact());
        EraseEntry b = store.append(req("rec-2"), T0.plusSeconds(1));
        assertEquals(2, b.seq());
        assertEquals(a.hash(), b.prev());
        assertTrue(new EraseLog(store, Clock.systemUTC()).verify().intact());
        assertEquals(2, Files.readString(file).lines().count());
    }

    @Test
    void pruningKeepsTheChainVerifiableFromTheCheckpoint(@TempDir Path dir) {
        Clock clock = Clock.fixed(T0.plus(Duration.ofDays(100)), ZoneOffset.UTC);
        FileEraseLogStore store = new FileEraseLogStore(dir);
        EraseLog log = new EraseLog(store, clock);
        // Entries 1-3 are 70 days old, 4 is 60 days old, 5 is new.
        for (int i = 1; i <= 3; i++) {
            store.append(req("rec-" + i), T0.plus(Duration.ofDays(30)).plusSeconds(i));
        }
        store.append(req("rec-4"), T0.plus(Duration.ofDays(40)));
        store.append(req("rec-5"), T0.plus(Duration.ofDays(99)));

        assertEquals(2, log.prune(clock.instant(), EraseRetention.DEFAULT, 2), "only what the database applied");
        assertEquals(2, store.checkpoint().seq());
        assertEquals(1, log.prune(clock.instant(), EraseRetention.DEFAULT, 5), "65 days: entry 3 goes, 4 stays");
        assertEquals(3, store.checkpoint().seq());
        assertEquals(List.of(4L, 5L), store.after(0).stream().map(EraseEntry::seq).toList());
        EraseLog.ChainCheck chain = log.verify();
        assertTrue(chain.intact(), chain.message());
        assertEquals(2, chain.entries());
        assertEquals(0, log.prune(clock.instant(), EraseRetention.DEFAULT, 5));

        FileEraseLogStore reopened = new FileEraseLogStore(dir);
        EraseEntry six = reopened.append(req("rec-6"), clock.instant());
        assertEquals(6, six.seq());
        assertTrue(new EraseLog(reopened, clock).verify().intact());
        assertEquals(0, new EraseLog(reopened, clock).prune(clock.instant(), new EraseRetention(90), 6),
                "90 + 30 days: nothing is old enough");
    }

    @Test
    void aCrashBetweenTheCheckpointAndTheShorterLogIsHarmless(@TempDir Path dir) throws Exception {
        FileEraseLogStore store = new FileEraseLogStore(dir);
        for (int i = 1; i <= 3; i++) {
            store.append(req("rec-" + i), T0.plusSeconds(i));
        }
        Path file = dir.resolve(FileEraseLogStore.LOG_FILE);
        String full = Files.readString(file);
        store.pruneThrough(2);
        Files.writeString(file, full); // the old, longer log is still in place
        EraseLog log = new EraseLog(store, Clock.systemUTC());
        assertTrue(log.verify().intact(), log.verify().message());
        assertEquals(3, store.head().seq());
        assertEquals(List.of(3L), store.after(0).stream().map(EraseEntry::seq).toList());
        assertEquals(4, store.append(req("rec-4"), T0.plusSeconds(4)).seq());
    }

    @Test
    void theLocationMustBeADirectoryAndTheIdFileMayNotGoMissing(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("not-a-dir"), "x");
        assertEquals(EraseLogError.ERASE_LOG_SETTINGS_INVALID,
                assertThrows(EraseLogException.class, () -> new FileEraseLogStore(file)).code());
        Path log = dir.resolve("log");
        new FileEraseLogStore(log).append(req("rec-1"), T0);
        Files.delete(log.resolve(FileEraseLogStore.ID_FILE));
        EraseLogException e = assertThrows(EraseLogException.class, () -> new FileEraseLogStore(log));
        assertEquals(EraseLogError.ERASE_LOG_FORMAT, e.code());
        assertTrue(e.getMessage().contains("restore that file"), e.getMessage());
        assertTrue(Files.readString(log.resolve(FileEraseLogStore.LOG_FILE), StandardCharsets.UTF_8)
                .contains("rec-1"), "nothing lost");
    }
}
