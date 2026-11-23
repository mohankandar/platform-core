package com.documentplatform.integration;

import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.Limits;
import com.documentplatform.integration.config.WatchSource;
import com.documentplatform.integration.intake.IntakeAnswer;
import com.documentplatform.integration.intake.IntakeFile;
import com.documentplatform.integration.intake.IntakeHandler;
import com.documentplatform.integration.json.Json;
import com.documentplatform.integration.result.HealthResult;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.outbound.AddressGuard;
import com.documentplatform.outbound.Installation;
import com.documentplatform.integration.scan.ClamdScanner;
import com.documentplatform.integration.scan.ScanEvents;
import com.documentplatform.integration.scan.ScannerAddress;
import com.documentplatform.integration.scan.Scanning;
import com.documentplatform.integration.store.StoredIntegration;
import com.documentplatform.integration.testkit.Eicar;
import com.documentplatform.integration.testkit.MockFolder;
import com.documentplatform.integration.testkit.MockScanner;
import com.documentplatform.integration.watch.Drop;
import com.documentplatform.integration.watch.WatchResult;
import com.documentplatform.integration.watch.WatchedFolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B2, the watched folder against the mock folder: subfolder and base-name drops, JSON, XML and marker triggers with the
 * fixed mapping, the stability rule (slow arrival, a trigger before its files), incomplete drops, unreadable and
 * attack triggers, claims (two runners racing, a stuck claim released, a restart), the moves and problem.json, the
 * share disappearing in the middle of a pass, links, limits, and the scanner (EICAR quarantined, a failed scan held).
 */
class WatchedFolderTest {

    @TempDir
    Path tmp;

    /** A clock the test moves. */
    static final class MovingClock extends Clock {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void plus(Duration d) {
            now = now.plus(d);
        }
    }

    /** The app's side: its idempotency by drop ID; reads every file to its end. */
    static final class App implements IntakeHandler<Drop> {
        final Set<String> have = Collections.synchronizedSet(new HashSet<>());
        final List<Drop> handed = Collections.synchronizedList(new ArrayList<>());
        final Set<String> refuse = new HashSet<>();
        volatile boolean fail;

        @Override
        public IntakeAnswer handle(Drop d) {
            handed.add(d);
            if (fail) throw new IllegalStateException("the app's store is down");
            if (refuse.contains(d.dropId())) return IntakeAnswer.refused("APP-UNKNOWN-LEVEL");
            for (IntakeFile f : d.files()) {
                try (InputStream in = f.open()) {
                    in.readAllBytes();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return have.add(d.dropId()) ? IntakeAnswer.TAKEN : IntakeAnswer.ALREADY_TAKEN;
        }
    }

    private final MovingClock clock = new MovingClock();
    private MockFolder folder;
    private Instant old;

    @BeforeEach
    void start() {
        folder = MockFolder.watched(tmp);
        old = clock.now.minus(Duration.ofMinutes(2));
    }

    private StoredIntegration config(WatchSource w) {
        return Fx.stored(IntegrationConfig.watchedFolder(Fx.ENTITY, Fx.PRODUCT, "Inbox", w)
                .withLimits(Limits.DEFAULT.withMaxDocuments(5).withMaxFileBytes(10_000)));
    }

    private StoredIntegration jsonDrops() {
        return config(WatchSource.of(folder.folder(), WatchSource.Layout.SUBFOLDER, "*.json")
                .mapping("contract", "/loan/number").mapping("level", "/loan/level").mapping("files", "/files/*/name")
                .withFileListValue("files"));
    }

    private WatchedFolder watcher() {
        return watcher(Scanning.OFF);
    }

    private WatchedFolder watcher(Scanning scanning) {
        return new WatchedFolder(folder, Installation.onPremises(), scanning, clock);
    }

    private void jsonDrop(String name, Instant at, String... files) {
        StringBuilder list = new StringBuilder();
        for (String f : files) {
            folder.write(name + "/" + f, "%PDF-1.4 " + name + " " + f, at);
            if (!list.isEmpty()) list.append(',');
            list.append("{\"name\":").append(Json.quote(f)).append('}');
        }
        folder.write(name + "/manifest.json", "{\"loan\":{\"number\":\"L-" + name + "\",\"level\":3},\"files\":["
                + list + "]}", at);
    }

    private String problem(String area, String drop) {
        List<String> names = folder.entries(area).stream().filter(n -> n.startsWith(drop + "-")).toList();
        assertEquals(1, names.size(), folder.entries(area).toString());
        return new String(folder.read(area + "/" + names.get(0), WatchSource.PROBLEM_FILE), StandardCharsets.UTF_8);
    }

    @Test
    void aSubfolderDropWithAJsonTriggerIsTakenAndArchived() throws Exception {
        StoredIntegration s = jsonDrops();
        jsonDrop("drop-1", old, "a.pdf", "b.pdf");
        folder.write("drop-1/extra.pdf", "%PDF extra", old); // not in the list: reported, not decided
        App app = new App();
        WatchResult r = watcher().pass(s, app);
        assertTrue(r.ok(), r.toString());
        assertEquals(1, r.taken());
        Drop d = app.handed.get(0);
        assertEquals("drop-1", d.dropId());
        assertEquals(List.of("a.pdf", "b.pdf", "extra.pdf"), d.files().stream().map(IntakeFile::name).toList());
        assertEquals("L-drop-1", d.value("contract"));
        assertEquals("3", d.value("level"), "a value without meaning here: the app decides what a level is");
        assertEquals(List.of("a.pdf", "b.pdf"), d.values().get("files"));
        assertEquals(List.of("extra.pdf"), d.unlisted());
        assertEquals(List.of(), d.missing());
        assertEquals("manifest.json", d.trigger().name());
        assertEquals(WatchSource.TriggerType.JSON, d.trigger().type());
        assertTrue(d.trigger().text().contains("L-drop-1"), "the raw trigger is there too");
        assertEquals(PollingTest.sha("%PDF-1.4 drop-1 a.pdf".getBytes()), d.files().get(0).sha256());
        // Archived under its claim; nothing left in the root or in _processing.
        assertEquals(List.of("_archive", "_problem", "_processing", "_quarantine"), folder.entries());
        assertTrue(folder.entries("_processing").isEmpty());
        String archived = folder.entries("_archive").get(0);
        assertTrue(archived.matches("drop-1-[0-9a-f]{12}"), archived);
        assertEquals(List.of("a.pdf", "b.pdf", "extra.pdf", "manifest.json"), folder.entries("_archive/" + archived));
        // Nothing more to do.
        assertEquals(0, watcher().pass(s, app).taken());
    }

    @Test
    void namesInTheListWithoutAFileAreReported() {
        StoredIntegration s = jsonDrops();
        folder.write("drop-2/a.pdf", "x", old);
        folder.write("drop-2/manifest.json", "{\"files\":[{\"name\":\"a.pdf\"},{\"name\":\"B.PDF\"},{\"name\":\"c.pdf\"}]}",
                old);
        folder.write("drop-2/b.pdf", "y", old);
        App app = new App();
        watcher().pass(s, app);
        Drop d = app.handed.get(0);
        assertEquals(List.of("c.pdf"), d.missing(), "names compare without case, as on Windows");
        assertEquals(List.of(), d.unlisted());
        assertNull(d.value("contract"));
    }

    @Test
    void baseNameDropsWithXmlAndMarkerTriggers() {
        StoredIntegration xml = config(WatchSource.of(folder.folder(), WatchSource.Layout.BASE_NAME, "<base>.xml")
                .mapping("contract", "/drop/loan/@number").mapping("first", "/drop/files/file[1]"));
        folder.write("loan-17.pdf", "%PDF one", old).write("loan-17.p2.pdf", "%PDF two", old)
                .write("loan-17.xml", "<drop><loan number=\"C-17\"/><files><file>loan-17.pdf</file></files></drop>",
                        old)
                .write("loan-18.pdf", "%PDF waiting", old);
        App app = new App();
        WatchResult r = watcher().pass(xml, app);
        assertEquals(1, r.taken(), r.toString());
        Drop d = app.handed.get(0);
        assertEquals("loan-17", d.dropId());
        assertEquals("C-17", d.value("contract"));
        assertEquals("loan-17.pdf", d.value("first"));
        assertEquals(List.of("loan-17.p2.pdf", "loan-17.pdf"), d.files().stream().map(IntakeFile::name).toList());
        assertEquals(List.of("_archive", "_problem", "_processing", "_quarantine", "loan-18.pdf"), folder.entries());

        StoredIntegration marker = config(WatchSource.of(folder.folder(), WatchSource.Layout.BASE_NAME, "<base>.done"));
        folder.write("loan-18.done", new byte[0], old);
        App app2 = new App();
        assertEquals(1, watcher().pass(marker, app2).taken());
        Drop m = app2.handed.get(0);
        assertTrue(m.trigger().empty());
        assertEquals(WatchSource.TriggerType.MARKER, m.trigger().type());
        assertEquals(Map.of(), m.values());
    }

    @Test
    void aDropIsTakenOnlyOnceItsFilesAreStable() {
        StoredIntegration s = jsonDrops();
        App app = new App();
        // Arriving slowly: the files are still being written.
        jsonDrop("slow", clock.now, "a.pdf");
        assertEquals(0, watcher().pass(s, app).taken());
        WatchedFolder w = watcher();
        assertEquals(0, w.pass(s, app).taken(), "seen now");
        clock.plus(Duration.ofSeconds(20));
        folder.write("slow/b.pdf", "%PDF more", clock.now); // another file came: the wait starts again
        assertEquals(0, w.pass(s, app).taken());
        clock.plus(Duration.ofSeconds(20));
        assertEquals(0, w.pass(s, app).taken(), "20 s unchanged is not 30 s");
        clock.plus(Duration.ofSeconds(15));
        assertEquals(1, w.pass(s, app).taken(), "unchanged for the stable time");
        assertEquals(List.of("a.pdf", "b.pdf"), app.handed.get(0).files().stream().map(IntakeFile::name).toList());
    }

    @Test
    void aTriggerWrittenBeforeItsFilesWaitsForThem() {
        StoredIntegration s = jsonDrops();
        folder.write("early/manifest.json", "{\"files\":[{\"name\":\"a.pdf\"}]}", old);
        folder.write("early/a.pdf", "%PDF part", clock.now); // still being written after the trigger
        App app = new App();
        WatchedFolder w = watcher();
        assertEquals(0, w.pass(s, app).taken());
        clock.plus(Duration.ofSeconds(31));
        assertEquals(1, w.pass(s, app).taken());
        assertEquals(List.of(), app.handed.get(0).missing());
    }

    @Test
    void filesWithoutATriggerAreReportedOnceAndLeftAlone() {
        StoredIntegration s = jsonDrops();
        folder.write("orphan/a.pdf", "%PDF", clock.now.minus(Duration.ofMinutes(61)));
        folder.write("orphan/b.pdf", "%PDF", clock.now.minus(Duration.ofMinutes(30)));
        folder.write("young/a.pdf", "%PDF", clock.now.minus(Duration.ofMinutes(10)));
        WatchedFolder w = watcher();
        WatchResult r = w.pass(s, new App());
        assertEquals(1, r.incomplete().size());
        WatchResult.Incomplete i = r.incomplete().get(0);
        assertEquals("orphan", i.dropId());
        assertEquals(Duration.ofMinutes(61), i.age());
        assertEquals(List.of("a.pdf", "b.pdf"), i.files());
        assertTrue(w.pass(s, new App()).incomplete().isEmpty(), "reported once");
        assertTrue(folder.has("orphan/a.pdf") && folder.has("young/a.pdf"), "nothing moved");
    }

    @Test
    void unreadableAndAttackTriggersGoToProblemWithACodeNeverContent() {
        StoredIntegration json = jsonDrops();
        folder.write("bad-json/a.pdf", "%PDF", old).write("bad-json/manifest.json", "{\"loan\": SECRET-planted", old);
        folder.write("too-big/manifest.json", "{\"pad\":\"" + "x".repeat(2_000_000) + "\"}", old);
        App app = new App();
        WatchResult r = watcher().pass(json, app);
        assertTrue(app.handed.isEmpty());
        assertEquals(2, r.problems().size(), r.toString());
        assertTrue(r.problems().stream().allMatch(p -> p.code() == IntegrationCode.INT_WATCH_TRIGGER_UNREADABLE));
        String note = problem(WatchSource.PROBLEM, "bad-json");
        assertTrue(note.contains("\"code\":\"INT_WATCH_TRIGGER_UNREADABLE\""), note);
        assertTrue(note.contains("\"format\":\"platform-watch-problem/1\""));
        assertFalse(note.contains("SECRET-planted"), "never the trigger's content");

        StoredIntegration xml = config(WatchSource.of(folder.folder(), WatchSource.Layout.SUBFOLDER, "*.xml")
                .mapping("x", "/d/x"));
        folder.write("laughs/t.xml", "<?xml version=\"1.0\"?><!DOCTYPE l [<!ENTITY a \"aaaa\"><!ENTITY b \"&a;&a;&a;\">]>"
                + "<d><x>&b;</x></d>", old);
        folder.write("xxe/t.xml", "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                + "<d><x>&e;</x></d>", old);
        folder.write("malformed/t.xml", "<d><x></d>", old);
        WatchResult rx = watcher().pass(xml, app);
        assertEquals(3, rx.problems().size(), rx.toString());
        assertTrue(app.handed.isEmpty());
        assertTrue(problem(WatchSource.PROBLEM, "xxe").contains("INT_WATCH_TRIGGER_UNREADABLE"));
    }

    @Test
    void theAppsAnswerDecidesTheMove() {
        StoredIntegration s = jsonDrops();
        jsonDrop("refused", old, "a.pdf");
        jsonDrop("again", old, "a.pdf");
        App app = new App();
        app.refuse.add("refused");
        app.have.add("again");
        WatchResult r = watcher().pass(s, app);
        assertEquals(List.of(new WatchResult.Refusal("refused", "APP-UNKNOWN-LEVEL")), r.refused());
        assertEquals(1, r.alreadyTaken());
        assertEquals(0, r.taken());
        String note = problem(WatchSource.PROBLEM, "refused");
        assertTrue(note.contains("\"code\":\"INT_WATCH_REFUSED\"") && note.contains("\"appCode\":\"APP-UNKNOWN-LEVEL\""));
        assertTrue(folder.entries(WatchSource.ARCHIVE).get(0).startsWith("again-"));
    }

    @Test
    void twoRunnersRacingExactlyOneClaims() throws Exception {
        StoredIntegration s = jsonDrops();
        for (int i = 1; i <= 4; i++) jsonDrop("race-" + i, old, "a.pdf");
        folder.slowMoves(Duration.ofMillis(150)); // both list before either claims
        App app = new App();
        ExecutorService two = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<WatchResult>> runs = new ArrayList<>();
        for (int k = 0; k < 2; k++) {
            WatchedFolder w = watcher(); // two app instances
            runs.add(two.submit(() -> {
                go.await();
                return w.pass(s, app);
            }));
        }
        go.countDown();
        WatchResult a = runs.get(0).get();
        WatchResult b = runs.get(1).get();
        two.shutdown();
        assertEquals(4, a.taken() + b.taken(), a + " / " + b);
        assertEquals(4, a.claimsLost() + b.claimsLost(), "each drop was lost by the other runner");
        assertEquals(4, app.handed.size(), "never handed over twice");
        assertEquals(4, folder.entries(WatchSource.ARCHIVE).size());
    }

    @Test
    void aStuckClaimIsReleasedAndTheDropTakenAgainSafely() {
        StoredIntegration s = jsonDrops();
        jsonDrop("stuck", old, "a.pdf");
        App app = new App();
        // An instance claims the drop and dies before it moves it on: the handler took it, then the process stopped.
        WatchedFolder dying = watcher();
        IntakeHandler<Drop> dies = d -> {
            app.handle(d);
            folder.readOnly(true); // nothing can be moved any more
            return IntakeAnswer.TAKEN;
        };
        WatchResult first = dying.pass(s, dies);
        folder.readOnly(false);
        assertEquals(List.of(new WatchResult.Problem("stuck", IntegrationCode.INT_WATCH_MOVE_FAILED)), first.problems());
        assertEquals(1, folder.entries(WatchSource.PROCESSING).size());
        // Before the claim time nothing happens; after it the drop is put back and handed over again.
        clock.plus(Duration.ofMinutes(10));
        assertEquals(0, watcher().pass(s, app).released());
        clock.plus(Duration.ofMinutes(21));
        WatchResult later = watcher().pass(s, app);
        assertEquals(1, later.released());
        assertEquals(1, later.alreadyTaken(), "the app's idempotency: never taken twice");
        assertEquals(1, app.have.size());
        assertTrue(folder.entries(WatchSource.PROCESSING).isEmpty());
        assertEquals(1, folder.entries(WatchSource.ARCHIVE).size());
    }

    @Test
    void theShareDisappearingInTheMiddleOfAPass() {
        StoredIntegration s = jsonDrops();
        jsonDrop("one", old, "a.pdf");
        jsonDrop("two", old, "a.pdf");
        folder.unreachableAfter(12);
        App app = new App();
        WatchResult r = watcher().pass(s, app);
        assertFalse(r.ok());
        assertEquals(Outcome.CANT_CONNECT, r.failure().outcome());
        assertEquals(IntegrationCode.INT_WATCH_SHARE_UNREACHABLE, r.failure().code());
        folder.working();
        clock.plus(Duration.ofMinutes(31));
        WatchResult back = watcher().pass(s, app);
        assertTrue(back.ok(), back.toString());
        assertEquals(2, app.have.size(), "both taken in the end; a claim the cut left behind was put back");
        assertEquals(2, folder.entries(WatchSource.ARCHIVE).size());
        assertTrue(folder.entries(WatchSource.PROCESSING).isEmpty());
    }

    @Test
    void theRootMissingWithoutRightsOrUnreachable() {
        StoredIntegration s = jsonDrops();
        folder.missing(true);
        assertEquals(IntegrationCode.INT_WATCH_ROOT_NOT_FOUND, watcher().pass(s, new App()).failure().code());
        assertEquals(IntegrationCode.INT_WATCH_ROOT_NOT_FOUND, watcher().health(s).code());
        folder.working().unreachable(true);
        assertEquals(IntegrationCode.INT_WATCH_SHARE_UNREACHABLE, watcher().pass(s, new App()).failure().code());
        HealthResult h = watcher().health(s);
        assertEquals(Outcome.CANT_CONNECT, h.outcome());
        folder.working();
        assertTrue(watcher().health(s).healthy());
        assertTrue(folder.entries(WatchSource.PROCESSING).isEmpty(), "the probe file was removed");
        StoredIntegration fresh = config(WatchSource.of(tmp.resolve("new-inbox").toString(),
                WatchSource.Layout.SUBFOLDER, "*.json"));
        assertEquals(IntegrationCode.INT_WATCH_ROOT_NOT_FOUND, watcher().pass(fresh, new App()).failure().code());
        try {
            Files.createDirectories(tmp.resolve("ro-inbox"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        MockFolder readOnly = MockFolder.watched(tmp.resolve("ro"));
        readOnly.readOnly(true);
        StoredIntegration ro = config(WatchSource.of(readOnly.folder(), WatchSource.Layout.SUBFOLDER, "*.json"));
        assertEquals(IntegrationCode.INT_WATCH_NO_PERMISSION, new WatchedFolder(readOnly, Installation.onPremises(),
                Scanning.OFF, clock).pass(ro, new App()).failure().code());
        // Saved for one installation, run on the hosted service: never run.
        assertEquals(IntegrationCode.INT_CONFIG_INVALID, new WatchedFolder(folder, Installation.hostedService(),
                Scanning.OFF, clock).pass(s, new App()).failure().code());
    }

    @Test
    void limitsTwoTriggersAndALinkMakeADropInvalid() throws Exception {
        StoredIntegration s = jsonDrops();
        jsonDrop("many", old, "1.pdf", "2.pdf", "3.pdf", "4.pdf", "5.pdf", "6.pdf");
        jsonDrop("large", old, "a.pdf");
        folder.write("large/big.pdf", new byte[20_000], old);
        jsonDrop("double", old, "a.pdf");
        folder.write("double/second.json", "{}", old);
        WatchResult r = watcher().pass(s, new App());
        assertEquals(3, r.problems().size(), r.toString());
        assertTrue(r.problems().stream().allMatch(p -> p.code() == IntegrationCode.INT_WATCH_DROP_INVALID));

        jsonDrop("linked", old, "a.pdf");
        Path outside = Files.writeString(tmp.resolve("outside-secret.txt"), "SECRET-planted");
        boolean made;
        try {
            Files.createSymbolicLink(folder.path("linked/b.pdf"), outside);
            made = true;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            made = false; // Windows without the right to make links
        }
        assumeTrue(made, "this account may not create symbolic links");
        clock.plus(Duration.ofMinutes(1)); // the link itself has the time it was made: let the drop settle
        App app = new App();
        WatchResult l = watcher().pass(s, app);
        assertEquals(List.of(new WatchResult.Problem("linked", IntegrationCode.INT_WATCH_DROP_INVALID)), l.problems());
        assertTrue(app.handed.isEmpty(), "a link is never followed");
    }

    @Test
    void aFailingHandlerPutsTheDropBack() {
        StoredIntegration s = jsonDrops();
        jsonDrop("retry", old, "a.pdf");
        App app = new App();
        app.fail = true;
        WatchResult r = watcher().pass(s, app);
        assertEquals(IntegrationCode.INT_WATCH_HANDLER_FAILED, r.failure().code());
        assertTrue(folder.has("retry/manifest.json"), "back where it was found");
        assertTrue(folder.entries(WatchSource.PROCESSING).isEmpty());
        app.fail = false;
        assertEquals(1, watcher().pass(s, app).taken());
    }

    @Test
    void aFileChangedWhileTheAppReadsItGoesToProblem() {
        // integration-module-129 / 155: the app's stream checks itself; the drop is not put back to be taken again.
        StoredIntegration s = jsonDrops();
        jsonDrop("changing", old, "a.pdf");
        App app = new App();
        WatchResult r = watcher().pass(s, d -> {
            String claim = folder.entries(WatchSource.PROCESSING).get(0);
            IntakeFile f = d.files().get(0);
            try (InputStream in = f.open()) {
                in.readNBytes(4);
                folder.write(WatchSource.PROCESSING + "/" + claim + "/" + f.name(),
                        "%PDF-1.4 rewritten by the customer's process while the app read it", old);
                in.readAllBytes();
            } catch (IOException e) {
                assertInstanceOf(IntakeFile.Changed.class, e);
                throw new UncheckedIOException(e);
            }
            return app.handle(d);
        });
        assertTrue(r.ok(), r.toString());
        assertEquals(0, r.taken());
        assertEquals(List.of(new WatchResult.Problem("changing", IntegrationCode.INT_WATCH_FILE_CHANGED)),
                r.problems());
        assertTrue(problem(WatchSource.PROBLEM, "changing").contains("INT_WATCH_FILE_CHANGED"));
        assertTrue(folder.entries(WatchSource.PROCESSING).isEmpty());
        assertFalse(folder.has("changing/manifest.json"), "not put back");
        assertTrue(app.have.isEmpty());
    }

    @Test
    void theDropsPerPassLimit() {
        StoredIntegration s = config(WatchSource.of(folder.folder(), WatchSource.Layout.SUBFOLDER, "*.json")
                .withMaxDropsPerPass(2));
        for (int i = 1; i <= 3; i++) jsonDrop("d" + i, old, "a.pdf");
        WatchResult r = watcher().pass(s, new App());
        assertEquals(2, r.taken());
        assertTrue(r.more());
        assertEquals(1, watcher().pass(s, new App()).taken());
    }

    @Test
    void eicarIsQuarantinedAndNeverReachesTheHandler() {
        assumeTrue(Eicar.staysOnDisk(tmp), "a virus scanner on this machine removes the test file; CI runs this");
        StoredIntegration s = jsonDrops();
        jsonDrop("clean", old, "a.pdf");
        jsonDrop("infected", old, "a.pdf");
        folder.write("infected/b.pdf", Eicar.bytes(), old);
        try (MockScanner m = MockScanner.clamd()) {
            List<ScanEvents.Infected> events = new ArrayList<>();
            Scanning scanning = new Scanning(new ClamdScanner(new ScannerAddress(m.host(), m.port(),
                    Duration.ofSeconds(5)), new AddressGuard(true)), new ScanEvents() {
                @Override
                public void infected(Infected e) {
                    events.add(e);
                }
            });
            App app = new App();
            WatchResult r = watcher(scanning).pass(s, app);
            assertEquals(1, r.taken(), r.toString());
            assertEquals(List.of("clean"), app.handed.stream().map(Drop::dropId).toList());
            assertEquals(List.of(new WatchResult.Quarantined("infected", "b.pdf", MockScanner.EICAR_NAME)),
                    r.quarantined());
            String note = problem(WatchSource.QUARANTINE, "infected");
            assertTrue(note.contains("INT_SCAN_INFECTED") && note.contains(MockScanner.EICAR_NAME));
            assertEquals(1, events.size());
            assertEquals("watch", events.get(0).source());
            assertEquals("b.pdf", events.get(0).fileName());
        }
    }

    @Test
    void aFailedScanHoldsTheDropForTheNextPass() {
        StoredIntegration s = jsonDrops();
        jsonDrop("held", old, "a.pdf");
        try (MockScanner m = MockScanner.icap()) {
            Scanning scanning = new Scanning(new com.documentplatform.integration.scan.IcapScanner(new ScannerAddress(
                    m.host(), m.port(), Duration.ofSeconds(2)), MockScanner.ICAP_SERVICE, new AddressGuard(true)),
                    ScanEvents.NONE);
            App app = new App();
            m.down(true);
            WatchResult r = watcher(scanning).pass(s, app);
            assertEquals(IntegrationCode.INT_SCAN_UNREACHABLE, r.failure().code());
            assertEquals(List.of(new WatchResult.Problem("held", IntegrationCode.INT_SCAN_UNREACHABLE)), r.held());
            assertTrue(app.handed.isEmpty(), "never let through");
            assertTrue(folder.has("held/a.pdf"), "back in the root");
            m.down(false);
            assertEquals(1, watcher(scanning).pass(s, app).taken());
            assertEquals(2, m.scans(), "the file and the trigger");
        }
    }
}
