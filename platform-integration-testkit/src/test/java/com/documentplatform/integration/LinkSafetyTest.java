package com.documentplatform.integration;

import com.documentplatform.integration.config.FolderTarget;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.Limits;
import com.documentplatform.integration.config.WatchSource;
import com.documentplatform.integration.engine.Delivery;
import com.documentplatform.integration.folder.ExportInfo;
import com.documentplatform.integration.folder.FolderExport;
import com.documentplatform.integration.folder.LinkRefusedException;
import com.documentplatform.integration.folder.LocalFiles;
import com.documentplatform.integration.intake.IntakeAnswer;
import com.documentplatform.integration.intake.IntakeFile;
import com.documentplatform.integration.result.AttemptResult;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.integration.scan.ClamdScanner;
import com.documentplatform.integration.scan.ScanEvents;
import com.documentplatform.integration.scan.ScannerAddress;
import com.documentplatform.integration.scan.Scanning;
import com.documentplatform.integration.store.StoredIntegration;
import com.documentplatform.integration.testkit.MockFolder;
import com.documentplatform.integration.testkit.MockScanner;
import com.documentplatform.integration.watch.Drop;
import com.documentplatform.integration.watch.WatchFiles;
import com.documentplatform.integration.watch.WatchResult;
import com.documentplatform.integration.watch.WatchedFolder;
import com.documentplatform.outbound.AddressGuard;
import com.documentplatform.outbound.Installation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P043 (security findings SEC-1, SEC-9, SEC-52): the platform never deletes, moves or walks through a link (a
 * symbolic link, or a Windows junction, which needs no special right to make), and the bytes handed to the app are the
 * bytes the virus scanner read. Every case reproduces the attack: without the fix, the file behind the link is deleted
 * or the swapped file reaches the app. On Windows the links are junctions; elsewhere symbolic links. Nothing skips.
 */
class LinkSafetyTest {

    static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

    @TempDir
    Path tmp;

    /** A link to a folder: a junction on Windows, a symbolic link elsewhere. */
    static void folderLink(Path link, Path target) throws Exception {
        if (WINDOWS) {
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            assertEquals(0, p.waitFor(), "mklink /J failed");
        } else {
            Files.createSymbolicLink(link, target);
        }
        assertTrue(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS));
    }

    /** A folder holding one file that must survive. */
    private Path victim() throws IOException {
        Path v = Files.createDirectories(tmp.resolve("victim"));
        Files.writeString(v.resolve("keep.txt"), "important");
        return v;
    }

    private void survived(Path victim) {
        assertTrue(Files.exists(victim.resolve("keep.txt")), "the file behind the link was deleted");
    }

    // ---- LocalFiles ----

    @Test
    void deletingALinkIsRefusedAndNothingBehindItIsTouched() throws Exception {
        Path victim = victim();
        Path work = Files.createDirectories(tmp.resolve("export"));
        Path link = work.resolve("P1.partial");
        folderLink(link, victim);

        assertThrows(LinkRefusedException.class, () -> LocalFiles.INSTANCE.delete(link));
        survived(victim);
        assertTrue(LocalFiles.INSTANCE.link(link));
        assertFalse(LocalFiles.INSTANCE.link(work));
    }

    @Test
    void aLinkInsideAFolderRefusesTheWholeDeleteBeforeAnythingGoes() throws Exception {
        Path victim = victim();
        Path partial = Files.createDirectories(tmp.resolve("export").resolve("P2.partial"));
        Files.writeString(partial.resolve("a.pdf"), "ours");
        Path sub = Files.createDirectories(partial.resolve("z-sub"));
        folderLink(sub.resolve("inner"), victim);

        assertThrows(LinkRefusedException.class, () -> LocalFiles.INSTANCE.delete(partial));
        survived(victim);
        assertTrue(Files.exists(partial.resolve("a.pdf")), "refused before anything was deleted");
    }

    @Test
    void movingOrRenamingALinkIsRefused() throws Exception {
        Path victim = victim();
        Path link = tmp.resolve("drop");
        folderLink(link, victim);
        assertThrows(LinkRefusedException.class, () -> LocalFiles.INSTANCE.move(link, tmp.resolve("moved")));
        assertThrows(LinkRefusedException.class, () -> LocalFiles.INSTANCE.rename(link, tmp.resolve("renamed")));
        survived(victim);
    }

    @Test
    void ordinaryFoldersAreStillDeletedWhole() throws Exception {
        Path p = Files.createDirectories(tmp.resolve("x.partial").resolve("a").resolve("b"));
        Files.writeString(p.resolve("f.txt"), "x");
        LocalFiles.INSTANCE.delete(tmp.resolve("x.partial"));
        assertFalse(Files.exists(tmp.resolve("x.partial")));
    }

    // ---- folder export ----

    private static StoredIntegration export(Path folder) {
        IntegrationConfig c = IntegrationConfig.folderExport(FolderExportTest.ENTITY, "PRODUCT_A", "Share export",
                FolderTarget.of(folder.toString())).withLimits(Limits.DEFAULT.withReadTimeout(Duration.ofSeconds(10)));
        return new StoredIntegration(c, 1, "admin", Instant.now());
    }

    private static Delivery delivery(String packageId) {
        return Delivery.of(packageId, List.of(Delivery.Document.of("doc-1", "a.pdf", "application/pdf",
                "%PDF-1.4 a".getBytes(StandardCharsets.UTF_8))), Map.of());
    }

    @Test
    void aPlantedPartialLinkIsRefusedAndNothingBehindItIsDeleted() throws Exception {
        // SEC-1 as proven in P042: a link named <package>.partial was followed by the clean-up before each attempt.
        Path victim = victim();
        MockFolder folder = MockFolder.in(tmp.resolve("share"));
        Path target = Path.of(folder.folder());
        folderLink(target.resolve("PKG-1.partial"), victim);

        AttemptResult r = new FolderExport(Installation.onPremises(), folder, Clock.systemUTC())
                .attempt(export(target), delivery("PKG-1"), ExportInfo.of(FolderExportTest.SLOT));
        assertEquals(IntegrationCode.INT_FOLDER_LINK_REFUSED, r.code(), r.toString());
        assertEquals(Outcome.NOT_CONFIGURED, r.outcome());
        survived(victim);
        assertFalse(Files.exists(target.resolve("PKG-1")), "no final folder");
    }

    @Test
    void aLinkInPlaceOfTheFinalPackageFolderIsNeitherReadNorReplaced() throws Exception {
        Path victim = victim();
        MockFolder folder = MockFolder.in(tmp.resolve("share"));
        Path target = Path.of(folder.folder());
        folderLink(target.resolve("PKG-2"), victim);

        AttemptResult r = new FolderExport(Installation.onPremises(), folder, Clock.systemUTC())
                .attempt(export(target), delivery("PKG-2"), ExportInfo.of(FolderExportTest.SLOT));
        assertEquals(IntegrationCode.INT_FOLDER_LINK_REFUSED, r.code(), r.toString());
        survived(victim);
    }

    // ---- watched folder ----

    private static StoredIntegration watched(MockFolder folder) {
        return Fx.stored(IntegrationConfig.watchedFolder(Fx.ENTITY, Fx.PRODUCT, "Inbox",
                WatchSource.of(folder.folder(), WatchSource.Layout.SUBFOLDER, "*.json"))
                .withLimits(Limits.DEFAULT.withMaxDocuments(5).withMaxFileBytes(10_000)));
    }

    private static void drop(MockFolder folder, String name, String content, Instant at) {
        folder.write(name + "/a.pdf", content, at);
        folder.write(name + "/manifest.json", "{}", at);
    }

    /** The app: reads every file to its end and keeps what it was given. */
    static final class App implements com.documentplatform.integration.intake.IntakeHandler<Drop> {
        final List<String> contents = new ArrayList<>();

        @Override
        public IntakeAnswer handle(Drop d) {
            for (IntakeFile f : d.files()) {
                try (InputStream in = f.open()) {
                    contents.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
            return IntakeAnswer.TAKEN;
        }
    }

    @Test
    void anArchiveFolderReplacedByALinkStopsThePassAndNothingIsMovedOut() throws Exception {
        Path victim = victim();
        MockFolder folder = MockFolder.watched(tmp.resolve("inbox"));
        Path root = Path.of(folder.folder());
        folderLink(root.resolve(WatchSource.ARCHIVE), victim);
        Instant old = Instant.now().minus(Duration.ofMinutes(5));
        drop(folder, "d1", "%PDF ok", old);

        App app = new App();
        WatchResult r = new WatchedFolder(folder, Installation.onPremises(), Scanning.OFF, Clock.systemUTC())
                .pass(watched(folder), app);
        assertEquals(IntegrationCode.INT_WATCH_LINK_REFUSED, r.failure().code(), r.toString());
        assertTrue(app.contents.isEmpty());
        survived(victim);
        try (var s = Files.list(victim)) {
            assertEquals(1, s.count(), "nothing was moved behind the link");
        }
    }

    @Test
    void aClaimMarkerSwappedForALinkIsNeverFollowedWhenTheDropIsFinished() throws Exception {
        // SEC-1, second way in: .platform-claim in _processing/<drop>-<fence>/ replaced by a link while the app works.
        Path victim = victim();
        MockFolder folder = MockFolder.watched(tmp.resolve("inbox"));
        Instant old = Instant.now().minus(Duration.ofMinutes(5));
        drop(folder, "d2", "%PDF ok", old);

        WatchResult r = new WatchedFolder(folder, Installation.onPremises(), Scanning.OFF, Clock.systemUTC())
                .pass(watched(folder), d -> {
                    try {
                        Path processing = Path.of(folder.folder()).resolve(WatchSource.PROCESSING);
                        Path claim;
                        try (var s = Files.list(processing)) {
                            claim = s.findFirst().orElseThrow();
                        }
                        Path marker = claim.resolve(".platform-claim");
                        Files.delete(marker);
                        folderLink(marker, victim);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    return IntakeAnswer.TAKEN;
                });
        assertEquals(List.of(new WatchResult.Problem("d2", IntegrationCode.INT_WATCH_LINK_REFUSED)), r.problems(),
                r.toString());
        survived(victim);
    }

    // ---- SEC-9: the scanned bytes are the handed-over bytes ----

    /** A watched folder whose second read of one file (the scanner's) gives other bytes than the first and third. */
    static final class SwappingFiles implements WatchFiles {
        private final MockFolder real;
        private final String fileName;
        private final byte[] forTheScanner;
        private final Map<Path, AtomicInteger> opens = new ConcurrentHashMap<>();

        SwappingFiles(MockFolder real, String fileName, byte[] forTheScanner) {
            this.real = real;
            this.fileName = fileName;
            this.forTheScanner = forTheScanner;
        }

        @Override
        public InputStream open(Path p) throws IOException {
            if (p.getFileName().toString().equals(fileName)
                    && opens.computeIfAbsent(p, k -> new AtomicInteger()).incrementAndGet() == 2) {
                return new ByteArrayInputStream(forTheScanner); // the clean file the sender put back for the scan
            }
            return real.open(p);
        }

        // ---- everything else as the real folder ----
        @Override public List<Entry> list(Path folder) throws IOException { return real.list(folder); }
        @Override public void move(Path from, Path to) throws IOException { real.move(from, to); }
        @Override public Path realPath(Path p) throws IOException { return real.realPath(p); }
        @Override public Path resolve(String folder) throws IOException { return real.resolve(folder); }
        @Override public boolean reachable(Path target) { return real.reachable(target); }
        @Override public boolean isDirectory(Path p) { return real.isDirectory(p); }
        @Override public boolean exists(Path p) { return real.exists(p); }
        @Override public void createDirectory(Path p) throws IOException { real.createDirectory(p); }
        @Override public OutputStream create(Path p) throws IOException { return real.create(p); }
        @Override public void rename(Path from, Path to) throws IOException { real.rename(from, to); }
        @Override public void delete(Path p) throws IOException { real.delete(p); }
        @Override public long usableSpace(Path p) throws IOException { return real.usableSpace(p); }
    }

    @Test
    void aFileSwappedBetweenTheHashAndTheScanNeverReachesTheApp() throws Exception {
        MockFolder folder = MockFolder.watched(tmp.resolve("inbox"));
        Instant old = Instant.now().minus(Duration.ofMinutes(5));
        String bad = "%PDF the sender's real file, which the scanner never saw";
        drop(folder, "d3", bad, old);
        SwappingFiles swapping = new SwappingFiles(folder, "a.pdf",
                "%PDF a clean file shown to the scanner only".getBytes(StandardCharsets.UTF_8));
        try (MockScanner m = MockScanner.clamd()) {
            Scanning scanning = new Scanning(new ClamdScanner(new ScannerAddress(m.host(), m.port(),
                    Duration.ofSeconds(5)), new AddressGuard(true)), ScanEvents.NONE);
            App app = new App();
            WatchResult r = new WatchedFolder(swapping, Installation.onPremises(), scanning, Clock.systemUTC())
                    .pass(watched(folder), app);
            assertEquals(List.of(), app.contents, "the unscanned bytes were handed over");
            assertEquals(List.of(new WatchResult.Problem("d3", IntegrationCode.INT_WATCH_FILE_CHANGED)), r.problems(),
                    r.toString());
            assertEquals(0, r.taken());
        }
    }

    @Test
    void anUnchangedFileIsScannedAndHandedOver() throws Exception {
        MockFolder folder = MockFolder.watched(tmp.resolve("inbox"));
        Instant old = Instant.now().minus(Duration.ofMinutes(5));
        drop(folder, "d4", "%PDF fine", old);
        try (MockScanner m = MockScanner.clamd()) {
            Scanning scanning = new Scanning(new ClamdScanner(new ScannerAddress(m.host(), m.port(),
                    Duration.ofSeconds(5)), new AddressGuard(true)), ScanEvents.NONE);
            App app = new App();
            WatchResult r = new WatchedFolder(folder, Installation.onPremises(), scanning, Clock.systemUTC())
                    .pass(watched(folder), app);
            assertEquals(1, r.taken(), r.toString());
            assertEquals(List.of("%PDF fine"), app.contents);
        }
    }

    // ---- SEC-52 ----

    @Test
    void windowsDeviceNamesAreRefusedInEveryForm() {
        for (String n : List.of("CON", "con.txt", "CON .txt", "Nul", "COM1.pdf", "LPT9", "CONIN$", "conout$.log",
                "CLOCK$", "COM¹", "lpt³.txt", "COM0", "aux .pdf")) {
            assertFalse(FolderTarget.validName(n), n);
            assertTrue(FolderTarget.safeName(n).startsWith("_"), n + " -> " + FolderTarget.safeName(n));
        }
        for (String n : List.of("CONTRACT.pdf", "console.txt", "COM10.pdf", "auxiliary", "nul-report")) {
            assertTrue(FolderTarget.validName(n), n);
        }
    }
}
