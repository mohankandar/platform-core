package com.documentplatform.integration;

import com.documentplatform.integration.config.CallStep;
import com.documentplatform.integration.config.Endpoint;
import com.documentplatform.integration.config.HttpMethod;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.Limits;
import com.documentplatform.integration.config.PollSource;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.engine.CallEngine;
import com.documentplatform.integration.engine.PollEngine;
import com.documentplatform.outbound.Outbound;
import com.documentplatform.integration.intake.CursorState;
import com.documentplatform.integration.intake.PollResult;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.outbound.AddressGuard;
import com.documentplatform.outbound.Installation;
import com.documentplatform.integration.scan.ClamdScanner;
import com.documentplatform.integration.scan.FileScanner;
import com.documentplatform.integration.scan.IcapScanner;
import com.documentplatform.integration.scan.ScanEvents;
import com.documentplatform.integration.scan.ScanResult;
import com.documentplatform.integration.scan.ScannerAddress;
import com.documentplatform.integration.scan.Scanning;
import com.documentplatform.integration.store.StoredIntegration;
import com.documentplatform.integration.testkit.Eicar;
import com.documentplatform.integration.testkit.MockScanner;
import com.documentplatform.integration.testkit.MockSource;
import com.documentplatform.notifications.inmemory.InMemorySecretResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B3, the virus-scan hook: the clamd and ICAP adapters against the mock scanner (clean, EICAR, slow, down, a garbled
 * answer, health, the address rules), and polling with a scanner: EICAR never reaches the handler and is not
 * acknowledged; a scan that failed stops the pass and the item is scanned again at the next one.
 */
class ScannerTest {

    @TempDir
    Path work;

    private final List<MockScanner> mocks = new ArrayList<>();
    private MockSource source;
    private CallEngine calls;

    @AfterEach
    void close() {
        mocks.forEach(MockScanner::close);
        if (source != null) source.close();
        if (calls != null) calls.close();
    }

    private MockScanner mock(MockScanner m) {
        mocks.add(m);
        return m;
    }

    private static FileScanner scanner(MockScanner m, Duration timeout) {
        ScannerAddress a = new ScannerAddress(m.host(), m.port(), timeout);
        AddressGuard onPremises = new AddressGuard(true);
        return m.protocol() == MockScanner.Protocol.CLAMD ? new ClamdScanner(a, onPremises)
                : new IcapScanner(a, MockScanner.ICAP_SERVICE, onPremises);
    }

    private static ScanResult scan(FileScanner s, byte[] content) {
        return s.scan(new ByteArrayInputStream(content), content.length);
    }

    @Test
    void theTestFileIsBuiltAtRunTime() {
        byte[] e = Eicar.bytes();
        assertEquals(68, e.length);
        String s = new String(e, StandardCharsets.US_ASCII);
        assertTrue(s.startsWith("X5O!") && s.contains("-STANDARD-ANTIVIRUS-TEST-FILE!"));
        // The published SHA-256 of the test file.
        assertEquals("275a021bbfb6489e54d471899f7db9d1663fc695ec2fe2a2c4538aabf651fd0f", PollingTest.sha(e));
    }

    @Test
    void bothAdaptersFindEicarAndPassACleanFile() {
        for (MockScanner m : List.of(mock(MockScanner.clamd()), mock(MockScanner.icap()))) {
            FileScanner s = scanner(m, Duration.ofSeconds(5));
            assertEquals(ScanResult.clean(), scan(s, "%PDF-1.4 a clean document".getBytes()), m.toString());
            ScanResult eicar = scan(s, Eicar.bytes());
            assertEquals(ScanResult.Verdict.INFECTED, eicar.verdict(), m.toString());
            assertEquals(MockScanner.EICAR_NAME, eicar.threat());
            assertEquals(IntegrationCode.INT_SCAN_INFECTED, eicar.code());
            // A large file goes in chunks; the test file inside it is found.
            byte[] big = new byte[300_000];
            System.arraycopy(Eicar.bytes(), 0, big, 200_000, 68);
            assertEquals(ScanResult.Verdict.INFECTED, scan(s, big).verdict());
            assertEquals(ScanResult.clean(), scan(s, new byte[0]), "an empty file");
            m.infected(true);
            assertEquals(ScanResult.Verdict.INFECTED, scan(s, "anything".getBytes()).verdict());
            assertTrue(s.health().up());
            assertEquals(IntegrationCode.INT_HEALTHY, s.health().code());
            assertEquals(5, m.scans());
        }
    }

    @Test
    void anIcap200WithoutAThreatHeaderIsInfected() {
        // integration-module-157: the scanner changed the response (it blocked the file) without naming the threat.
        MockScanner m = mock(MockScanner.icap()).withoutThreatHeader(true);
        FileScanner s = scanner(m, Duration.ofSeconds(5));
        ScanResult blocked = scan(s, Eicar.bytes());
        assertEquals(ScanResult.Verdict.INFECTED, blocked.verdict(), blocked.toString());
        assertEquals("unknown", blocked.threat());
        assertEquals(IntegrationCode.INT_SCAN_INFECTED, blocked.code());
        m.infected(true);
        assertEquals(ScanResult.Verdict.INFECTED, scan(s, "%PDF-1.4 anything".getBytes()).verdict());
        m.infected(false);
        assertEquals(ScanResult.clean(), scan(s, "%PDF-1.4 a clean document".getBytes()), "a 204 is still clean");
    }

    @Test
    void slowDownAndGarbledAreScanFailures() {
        for (MockScanner m : List.of(mock(MockScanner.clamd()), mock(MockScanner.icap()))) {
            FileScanner s = scanner(m, Duration.ofSeconds(1));
            m.slow(Duration.ofSeconds(3));
            assertEquals(ScanResult.failed(IntegrationCode.INT_SCAN_UNREACHABLE), scan(s, "x".getBytes()), m.toString());
            m.working().garbled(true);
            assertEquals(ScanResult.failed(IntegrationCode.INT_SCAN_FAILED), scan(s, "x".getBytes()), m.toString());
            assertFalse(s.health().up());
            assertEquals(IntegrationCode.INT_SCAN_FAILED, s.health().code());
            m.working().down(true);
            assertEquals(ScanResult.failed(IntegrationCode.INT_SCAN_UNREACHABLE), scan(s, "x".getBytes()), m.toString());
            assertEquals(IntegrationCode.INT_SCAN_UNREACHABLE, s.health().code());
            m.down(false);
            assertEquals(ScanResult.clean(), scan(s, "x".getBytes()), "up again on the same port");
        }
    }

    @Test
    void theScannerAddressFollowsTheOutboundRules() {
        MockScanner m = mock(MockScanner.clamd());
        // Hosted (no internal addresses): the loopback scanner is refused before anything is sent.
        FileScanner hosted = new ClamdScanner(new ScannerAddress(m.host(), m.port(), Duration.ofSeconds(2)),
                new AddressGuard(false));
        assertEquals(ScanResult.failed(IntegrationCode.INT_ADDRESS_BLOCKED), scan(hosted, "x".getBytes()));
        FileScanner metadata = new ClamdScanner(new ScannerAddress("169.254.169.254", 3310, Duration.ofSeconds(2)),
                new AddressGuard(true));
        assertEquals(ScanResult.failed(IntegrationCode.INT_ADDRESS_BLOCKED), scan(metadata, "x".getBytes()));
        assertEquals(0, m.scans());
        assertThrows(IllegalArgumentException.class, () -> new ScannerAddress("a b", 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ScannerAddress("h", 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new IcapScanner(new ScannerAddress("h", 1,
                Duration.ofSeconds(1)), "a b", new AddressGuard(true)));
        assertEquals("ScannerAddress[3310]", new ScannerAddress("scanner.corp", 3310, Duration.ofSeconds(1)).toString());
        assertEquals(ScanResult.Verdict.INFECTED, ScanResult.infected("Bad\nName;x").verdict());
        assertEquals("Bad_Name_x", ScanResult.infected("Bad\nName;x").threat(), "made safe");
    }

    // ---- Polling with a scanner ----

    private StoredIntegration pollSource() {
        PollSource s = PollSource.of(CallStep.of("list", HttpMethod.GET, "/items"), "/items", "/id",
                PollSource.Paging.none(), CallStep.of("download", HttpMethod.GET, "/items/{item.id}"))
                .withAcknowledge(CallStep.of("acknowledge", HttpMethod.DELETE, "/items/{item.id}"));
        source = MockSource.start(s);
        calls = new CallEngine(new Outbound(Installation.onPremises().withInternalAddresses(true)),
                new InMemorySecretResolver(), Clock.systemUTC());
        return Fx.stored(IntegrationConfig.apiPoll(Fx.ENTITY, Fx.PRODUCT, "Source", new Endpoint(source.address(), true),
                new SignIn.None(), s).withLimits(Limits.DEFAULT.withReadTimeout(Duration.ofSeconds(3))));
    }

    @Test
    void eicarFromASourceNeverReachesTheHandlerAndIsNotAcknowledged() {
        assumeTrue(Eicar.staysOnDisk(work), "a virus scanner on this machine removes the test file; CI runs this");
        StoredIntegration st = pollSourceWithNone();
        source.add("clean-1", "%PDF-1.4 fine".getBytes()).add("bad-1", Eicar.bytes()).add("clean-2", "%PDF ok".getBytes());
        List<ScanEvents.Infected> events = new ArrayList<>();
        for (MockScanner m : List.of(mock(MockScanner.clamd()), mock(MockScanner.icap()))) {
            source.clearRecords();
            events.clear();
            PollingTest.App app = new PollingTest.App();
            Scanning scanning = new Scanning(scanner(m, Duration.ofSeconds(5)), new ScanEvents() {
                @Override
                public void infected(Infected e) {
                    events.add(e);
                }
            });
            PollResult r = new PollEngine(calls, scanning, work).pass(st, CursorState.START, app);
            assertTrue(r.ok(), r.toString());
            assertEquals(List.of("clean-1", "clean-2"), app.handed, "the infected file never reached the handler");
            assertEquals(List.of(new PollResult.Quarantined("bad-1", MockScanner.EICAR_NAME)), r.quarantined());
            assertEquals(List.of("clean-1", "clean-2"), source.acknowledged());
            assertEquals(1, events.size());
            ScanEvents.Infected e = events.get(0);
            assertEquals(st.config().id(), e.integrationId());
            assertEquals("poll", e.source());
            assertEquals("bad-1", e.sourceId());
            assertEquals(MockScanner.EICAR_NAME, e.threat());
            assertEquals(3, m.scans());
        }
    }

    @Test
    void aScanThatFailedHoldsTheItemUntilTheNextPass() {
        StoredIntegration st = pollSourceWithNone();
        source.add("one", "%PDF-1.4 one".getBytes()).add("two", "%PDF-1.4 two".getBytes());
        MockScanner m = mock(MockScanner.clamd());
        Scanning scanning = new Scanning(scanner(m, Duration.ofSeconds(2)), ScanEvents.NONE);
        PollingTest.App app = new PollingTest.App();
        m.down(true);
        PollResult held = new PollEngine(calls, scanning, work).pass(st, CursorState.START, app);
        assertEquals(Outcome.CANT_CONNECT, held.failure().outcome());
        assertEquals(IntegrationCode.INT_SCAN_UNREACHABLE, held.failure().code());
        assertEquals("scan", held.failure().step());
        assertEquals("one", held.failure().sourceItemId());
        assertTrue(app.handed.isEmpty(), "never let through");
        assertTrue(source.acknowledged().isEmpty());
        m.down(false).garbled(true);
        PollResult garbled = new PollEngine(calls, scanning, work).pass(st, held.nextState(), app);
        assertEquals(Outcome.OUTAGE, garbled.failure().outcome());
        assertEquals(IntegrationCode.INT_SCAN_FAILED, garbled.failure().code());
        m.working();
        PollResult next = new PollEngine(calls, scanning, work).pass(st, garbled.nextState(), app);
        assertTrue(next.ok());
        assertEquals(2, next.taken());
        assertTrue(scanning.health().up());
    }

    private StoredIntegration pollSourceWithNone() {
        StoredIntegration s = pollSource();
        // Sign-in NONE needs the installation's switch: a separate engine for it.
        calls.close();
        calls = new CallEngine(new Outbound(Installation.onPremises().withInternalAddresses(true).withSignInNone(true)),
                new InMemorySecretResolver(), Clock.systemUTC());
        return s;
    }
}
