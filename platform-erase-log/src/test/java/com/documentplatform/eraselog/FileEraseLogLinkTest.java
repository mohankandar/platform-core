package com.documentplatform.eraselog;

import com.documentplatform.eraselog.file.FileEraseLogStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P043 (SEC-1 at the erase log location): the log never writes, moves or reads through a link standing in place of one
 * of its own files, and a temporary file left as a hard link to another file is replaced, never written through. Each
 * case reproduces the mistake or attack: without the fix the file behind the link is overwritten. On Windows the
 * links are junctions (no special right needed); elsewhere symbolic links. Nothing skips.
 */
class FileEraseLogLinkTest {

    static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

    @TempDir
    Path tmp;

    /** A link in place of a file: a junction to a folder on Windows, a symbolic link to the file elsewhere. */
    private Path link(Path link, Path victimFile) throws Exception {
        if (WINDOWS) {
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(),
                    victimFile.getParent().toString()).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            assertEquals(0, p.waitFor(), "mklink /J failed");
        } else {
            Files.createSymbolicLink(link, victimFile);
        }
        return link;
    }

    private Path victim() throws Exception {
        Path v = Files.createDirectories(tmp.resolve("elsewhere")).resolve("app-settings.txt");
        Files.writeString(v, "important");
        return v;
    }

    private static void untouched(Path victim) throws Exception {
        assertEquals("important", Files.readString(victim), "the file behind the link was written");
    }

    @Test
    void aLinkInPlaceOfTheTemporaryPruningFileIsRefusedAndNothingIsWrittenThroughIt() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("log"));
        FileEraseLogStore store = new FileEraseLogStore(dir);
        for (int i = 1; i <= 3; i++) store.append(FileEraseLogStoreTest.req("rec-" + i), FileEraseLogStoreTest.T0);
        Path victim = victim();
        Path l = link(dir.resolve(FileEraseLogStore.LOG_FILE + ".pruning"), victim);

        EraseLogException e = assertThrows(EraseLogException.class, () -> store.pruneThrough(1));
        assertEquals(EraseLogError.ERASE_LOG_LINK_REFUSED, e.code());
        untouched(victim);
        Files.delete(l); // the link itself, never what it points to
        assertEquals(3, store.head().seq(), "nothing pruned");
    }

    @Test
    void aLinkInPlaceOfTheLogFileItselfIsRefusedBeforeAnyWrite() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("log"));
        FileEraseLogStore store = new FileEraseLogStore(dir);
        Path victim = victim();
        Path l = link(dir.resolve(FileEraseLogStore.LOG_FILE), victim);

        assertEquals(EraseLogError.ERASE_LOG_LINK_REFUSED, assertThrows(EraseLogException.class,
                () -> store.append(FileEraseLogStoreTest.req("rec-1"), FileEraseLogStoreTest.T0)).code());
        assertEquals(EraseLogError.ERASE_LOG_LINK_REFUSED, assertThrows(EraseLogException.class,
                () -> store.scan(e -> { })).code());
        untouched(victim);
        Files.delete(l);
    }

    @Test
    void aTemporaryFileLeftAsAHardLinkIsReplacedNeverWrittenThrough() throws Exception {
        // A hard link is no reparse point: it cannot be told from a file, so the temporary file is always made new.
        Path dir = Files.createDirectories(tmp.resolve("log"));
        FileEraseLogStore store = new FileEraseLogStore(dir);
        for (int i = 1; i <= 3; i++) store.append(FileEraseLogStoreTest.req("rec-" + i), FileEraseLogStoreTest.T0);
        Path victim = victim();
        Files.createLink(dir.resolve(FileEraseLogStore.CHECKPOINT_FILE + ".new"), victim);
        Files.createLink(dir.resolve(FileEraseLogStore.LOG_FILE + ".pruning"), Files.writeString(
                tmp.resolve("elsewhere").resolve("other.txt"), "important"));

        store.pruneThrough(1);
        untouched(victim);
        untouched(tmp.resolve("elsewhere").resolve("other.txt"));
        assertEquals(1, store.checkpoint().seq());
        assertTrue(new EraseLog(store, java.time.Clock.systemUTC()).verify().intact());
    }
}
