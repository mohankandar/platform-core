package com.documentplatform.integration;

import com.documentplatform.integration.config.CallStep;
import com.documentplatform.integration.config.Endpoint;
import com.documentplatform.integration.config.HealthCheck;
import com.documentplatform.integration.config.HttpMethod;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.Limits;
import com.documentplatform.integration.config.PollSource;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.engine.CallEngine;
import com.documentplatform.integration.engine.PollEngine;
import com.documentplatform.outbound.Outbound;
import com.documentplatform.integration.intake.CursorState;
import com.documentplatform.integration.intake.IntakeAnswer;
import com.documentplatform.integration.intake.IntakeHandler;
import com.documentplatform.integration.intake.PollResult;
import com.documentplatform.integration.intake.PolledItem;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.outbound.Installation;
import com.documentplatform.integration.scan.Scanning;
import com.documentplatform.integration.store.StoredIntegration;
import com.documentplatform.integration.testkit.MockSource;
import com.documentplatform.notifications.inmemory.InMemorySecretResolver;
import com.documentplatform.notifications.secret.SecretRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B1, API polling against the mock source API: every paging mode, the page limit and the cursor kept between
 * passes, acknowledge only after the app took an item, and every switch (empty, a broken page, slow, busy, down, a
 * refused download, a failing acknowledge, duplicates across pages, sign-in refused), idempotency and a restart in
 * the middle of a pass.
 */
class PollingTest {

    @TempDir
    Path work;

    private final InMemorySecretResolver secrets = new InMemorySecretResolver().put(SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/src"), "tok-1");
    private final Installation installation = Installation.onPremises().withInternalAddresses(true);
    private final CallEngine calls = new CallEngine(new Outbound(installation), secrets, Clock.systemUTC());
    private MockSource source;

    @AfterEach
    void close() {
        calls.close();
        if (source != null) source.close();
    }

    /** The app's side: its own idempotency by the source item ID, reading each file to its end. */
    static final class App implements IntakeHandler<PolledItem> {
        final Set<String> have = new HashSet<>();
        final List<String> handed = new ArrayList<>();
        final Map<String, String> sha = new LinkedHashMap<>();
        final Map<String, Map<String, String>> values = new LinkedHashMap<>();
        final Set<String> refuse = new HashSet<>();
        final Set<String> failOn = new HashSet<>();

        @Override
        public IntakeAnswer handle(PolledItem item) {
            handed.add(item.sourceItemId());
            if (failOn.remove(item.sourceItemId())) throw new IllegalStateException("the app's store is down");
            if (refuse.contains(item.sourceItemId())) return IntakeAnswer.refused("APP-NOT-OURS");
            try (InputStream in = item.file().open()) {
                byte[] all = in.readAllBytes();
                sha.put(item.sourceItemId(), PollingTest.sha(all));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            values.put(item.sourceItemId(), item.values());
            return have.add(item.sourceItemId()) ? IntakeAnswer.TAKEN : IntakeAnswer.ALREADY_TAKEN;
        }
    }

    static String sha(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static PollSource source(PollSource.Paging paging) {
        return PollSource.of(CallStep.of("list", HttpMethod.GET, "/api/items?state=ready"), "/data/items", "/id",
                        paging, CallStep.of("download", HttpMethod.GET, "/api/items/{item.id}/content"))
                .reading("fileName", "/file/name").reading("kind", "/meta/kind").withFileNameValue("fileName")
                .withAcknowledge(CallStep.of("acknowledge", HttpMethod.POST, "/api/items/{item.id}/ack")
                        .withJsonBody("{\"id\":\"{item.id}\"}"));
    }

    private StoredIntegration start(PollSource s, int items) {
        source = MockSource.start(s);
        for (int i = 1; i <= items; i++) {
            source.add("item-" + i, ("%PDF-1.4 document " + i).getBytes(StandardCharsets.UTF_8),
                    Map.of("fileName", "file-" + i + ".pdf", "kind", "contract"));
        }
        IntegrationConfig c = IntegrationConfig.apiPoll(Fx.ENTITY, Fx.PRODUCT, "Source",
                        new Endpoint(source.address(), true), new SignIn.BearerFixed(SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/src")), s)
                .withErrorCodePointer("/code")
                .withHealth(HealthCheck.get(MockSource.HEALTH_PATH, false))
                .withLimits(Limits.DEFAULT.withReadTimeout(Duration.ofSeconds(2)));
        return Fx.stored(c);
    }

    private PollEngine engine() {
        return new PollEngine(calls, Scanning.OFF, work);
    }

    private long leftovers() {
        try (Stream<Path> s = Files.list(work)) {
            return s.filter(p -> p.getFileName().toString().startsWith("intake-")).count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void cursorPagingTakesEveryItemAndAcknowledgesAfterTheApp() {
        StoredIntegration s = start(source(PollSource.Paging.cursor("/paging/next", "cursor", "limit", 2)), 5);
        App app = new App();
        PollResult r = engine().pass(s, CursorState.START, app);
        assertTrue(r.ok(), r.toString());
        assertEquals(3, r.pages());
        assertEquals(5, r.taken());
        assertEquals(5, r.acknowledged());
        assertFalse(r.more());
        assertEquals(List.of("item-1", "item-2", "item-3", "item-4", "item-5"), app.handed);
        assertEquals(app.handed, source.acknowledged());
        for (MockSource.Item it : source.items()) assertEquals(sha(it.content()), app.sha.get(it.id()));
        assertEquals(Map.of("fileName", "file-1.pdf", "kind", "contract"), app.values.get("item-1"));
        assertEquals(List.of("state=ready&limit=2", "state=ready&cursor=c2&limit=2", "state=ready&cursor=c4&limit=2"),
                source.listQueries(), "the paging parameters are added to the configured query");
        // The end: the next pass reads the last page again; what the app has is ALREADY_TAKEN.
        assertEquals("c4", r.nextState().position());
        PollResult again = engine().pass(s, r.nextState(), app);
        assertEquals(0, again.taken());
        assertEquals(1, again.alreadyTaken());
        assertEquals(1, again.acknowledged());
        assertEquals(0, leftovers(), "no download is left in the work folder");
    }

    @Test
    void nextLinkInTheAnswerOrTheLinkHeaderAndPageNumbers() {
        for (PollSource.Paging g : List.of(PollSource.Paging.nextLink("/paging/next"), PollSource.Paging.nextLink(null),
                PollSource.Paging.pageNumber("page", "size", 2, 1), PollSource.Paging.pageNumber("page", null, 2, 0))) {
            StoredIntegration s = start(source(g.withMaxPagesPerPass(10)), 5);
            if (g.mode() == PollSource.Paging.Mode.PAGE_NUMBER) source.pageSize(2);
            if (g.mode() == PollSource.Paging.Mode.NEXT_LINK) source.pageSize(2);
            App app = new App();
            PollResult r = engine().pass(s, CursorState.START, app);
            assertTrue(r.ok(), g + " " + r);
            assertEquals(5, r.taken(), g.toString());
            assertEquals(3, r.pages(), g.toString());
            assertEquals(5, source.acknowledged().size());
            source.close();
        }
        source = null;
    }

    @Test
    void aNextLinkToAnotherServerStopsThePassWithoutCallingIt() {
        // integration-module-134: NEXT_LINK paging follows links on the base origin only.
        for (PollSource.Paging g : List.of(PollSource.Paging.nextLink("/paging/next"),
                PollSource.Paging.nextLink(null))) {
            PollSource ps = source(g.withMaxPagesPerPass(10));
            try (MockSource other = MockSource.start(ps)) {
                other.add("other-1", "x".getBytes());
                for (URI elsewhere : List.of(other.address(), URI.create("http://items.example"))) {
                    StoredIntegration s = start(ps, 5);
                    source.pageSize(2).nextLinkTo(elsewhere);
                    App app = new App();
                    PollResult r = engine().pass(s, CursorState.START, app);
                    assertFalse(r.ok(), g + " " + r);
                    assertEquals(Outcome.UNCLEAR, r.failure().outcome());
                    assertEquals(IntegrationCode.INT_POLL_NEXT_PAGE_INVALID, r.failure().code());
                    assertEquals(PollSource.LIST, r.failure().step());
                    assertEquals(2, r.taken(), "the first page is handled");
                    assertEquals(1, source.listCalls());
                    assertEquals(0, other.listCalls(), "no call to the other server");
                    // A stored position on another server is not followed either.
                    PollResult stored = engine().pass(s, CursorState.of(other.address() + "/api/items?offset=2"),
                            app);
                    assertEquals(IntegrationCode.INT_POLL_NEXT_PAGE_INVALID, stored.failure().code());
                    assertEquals(0, other.listCalls());
                    assertEquals(1, source.listCalls());
                    source.close();
                }
            }
        }
        source = null;
    }

    @Test
    void oneListCallWithoutPaging() {
        StoredIntegration s = start(source(PollSource.Paging.none()), 3);
        PollResult r = engine().pass(s, CursorState.START, new App());
        assertEquals(3, r.taken());
        assertEquals(1, r.pages());
        assertEquals(List.of("state=ready"), source.listQueries());
    }

    @Test
    void thePageLimitAndTheCursorKeptByTheApp() {
        StoredIntegration s = start(source(PollSource.Paging.cursor("/paging/next", "cursor", null, 2)
                .withMaxPagesPerPass(2)), 7);
        source.pageSize(2);
        App app = new App();
        PollResult first = engine().pass(s, CursorState.START, app);
        assertTrue(first.more());
        assertEquals(4, first.taken());
        assertEquals("c4", first.nextState().position());
        PollResult second = engine().pass(s, first.nextState(), app);
        assertFalse(second.more());
        assertEquals(3, second.taken());
        assertEquals(7, app.have.size());
        assertEquals(7, app.handed.size(), "nothing handed over twice across the two passes");
    }

    @Test
    void anEmptyListAndAnEmptyPageWithANextCursor() {
        StoredIntegration s = start(source(PollSource.Paging.cursor("/paging/next", "cursor", null, 2)), 0);
        PollResult r = engine().pass(s, CursorState.of("c9"), new App());
        assertTrue(r.ok());
        assertEquals(0, r.taken());
        assertEquals("c9", r.nextState().position(), "nothing new: the position stays");
        source.add("late-1", "x".getBytes());
        source.empty(true);
        PollResult empty = engine().pass(s, CursorState.START, new App());
        assertEquals(0, empty.taken());
        assertEquals(1, empty.pages());
    }

    @Test
    void aBrokenPageStopsThePassAtTheLastFullyHandledPage() {
        StoredIntegration s = start(source(PollSource.Paging.cursor("/paging/next", "cursor", null, 2)), 6);
        source.pageSize(2).brokenPage(1);
        App app = new App();
        PollResult r = engine().pass(s, CursorState.START, app);
        assertFalse(r.ok());
        assertEquals(Outcome.UNCLEAR, r.failure().outcome());
        assertEquals(IntegrationCode.INT_POLL_ITEMS_MISSING, r.failure().code());
        assertEquals(PollSource.LIST, r.failure().step());
        assertEquals(2, r.taken());
        assertEquals("c2", r.nextState().position(), "the next pass starts after the first page");
        PollResult rest = engine().pass(s, r.nextState(), app);
        assertTrue(rest.ok(), rest.toString());
        assertEquals(4, rest.taken());
    }

    @Test
    void slowBusyAndDownAreClassified() {
        StoredIntegration s = start(source(PollSource.Paging.none()), 2);
        source.slow(Duration.ofSeconds(4));
        PollResult slow = engine().pass(s, CursorState.START, new App());
        assertEquals(Outcome.CANT_CONNECT, slow.failure().outcome());
        assertEquals(IntegrationCode.INT_TIMEOUT, slow.failure().code());
        source.working().busy(Duration.ofSeconds(30));
        PollResult busy = engine().pass(s, CursorState.START, new App());
        assertEquals(Outcome.BUSY, busy.failure().outcome());
        assertEquals(Duration.ofSeconds(30), busy.failure().retryAfter());
        assertEquals("MOCK-SRC-BUSY", busy.failure().destinationCode());
        source.working().down(true);
        PollResult down = engine().pass(s, CursorState.START, new App());
        assertEquals(Outcome.OUTAGE, down.failure().outcome());
        assertEquals(CursorState.START, down.nextState());
        assertFalse(calls.health(s).healthy(), "the source's health check");
        source.working();
        assertTrue(calls.health(s).healthy());
        source.expectBearer("another-token");
        PollResult signIn = engine().pass(s, CursorState.START, new App());
        assertEquals(Outcome.SIGN_IN_FAILED, signIn.failure().outcome());
        assertEquals(0, leftovers());
    }

    @Test
    void aRefusedDownloadIsReportedAndTheOthersAreTaken() {
        StoredIntegration s = start(source(PollSource.Paging.none()), 3);
        source.refuseDownload("item-2", 410, "MOCK-SRC-GONE");
        App app = new App();
        PollResult r = engine().pass(s, CursorState.START, app);
        assertTrue(r.ok());
        assertEquals(2, r.taken());
        assertEquals(List.of(new PollResult.ItemProblem("item-2", IntegrationCode.INT_REFUSED, "MOCK-SRC-GONE", 410)),
                r.problems());
        assertEquals(List.of("item-1", "item-3"), source.acknowledged(), "a refused download is not acknowledged");
        assertFalse(app.handed.contains("item-2"));
    }

    @Test
    void aFileOverTheLimitIsLeftAlone() {
        StoredIntegration s = start(source(PollSource.Paging.none()), 2);
        source.add("big", new byte[64 * 1024]);
        StoredIntegration limited = Fx.stored(s.config().withLimits(s.config().limits().withMaxFileBytes(1000)));
        PollResult r = engine().pass(limited, CursorState.START, new App());
        assertEquals(2, r.taken());
        assertTrue(r.ok(), r.toString());
        assertEquals(IntegrationCode.INT_FILE_TOO_LARGE, r.problems().get(0).code());
        assertEquals("big", r.problems().get(0).sourceItemId());
        assertFalse(source.acknowledged().contains("big"));
        assertEquals(0, leftovers());
    }

    @Test
    void aFailingAcknowledgeStopsThePassAndTheNextPassFinishesIt() {
        StoredIntegration s = start(source(PollSource.Paging.none()), 3);
        source.failAcknowledge("item-2");
        App app = new App();
        PollResult r = engine().pass(s, CursorState.START, app);
        assertEquals(Outcome.OUTAGE, r.failure().outcome());
        assertEquals(PollSource.ACKNOWLEDGE, r.failure().step());
        assertEquals("item-2", r.failure().sourceItemId());
        assertEquals(List.of("item-1"), source.acknowledged());
        source.working();
        PollResult again = engine().pass(s, r.nextState(), app);
        assertTrue(again.ok());
        assertEquals(2, again.alreadyTaken(), "item-1 and item-2 the app already has");
        assertEquals(1, again.taken());
        assertEquals(List.of("item-1", "item-2", "item-3"), source.acknowledged());
        assertEquals(3, app.have.size());
    }

    @Test
    void duplicatesAcrossPagesAreNeverTakenTwice() {
        StoredIntegration s = start(source(PollSource.Paging.cursor("/paging/next", "cursor", null, 2)), 4);
        source.pageSize(2).duplicateOnNextPage("item-2");
        App app = new App();
        PollResult r = engine().pass(s, CursorState.START, app);
        assertTrue(r.ok());
        assertEquals(4, r.taken());
        assertEquals(1, r.alreadyTaken());
        assertEquals(List.of("item-1", "item-2", "item-2", "item-3", "item-4"), app.handed);
    }

    @Test
    void aRefusalIsNotAcknowledged() {
        StoredIntegration s = start(source(PollSource.Paging.none()), 2);
        App app = new App();
        app.refuse.add("item-1");
        PollResult r = engine().pass(s, CursorState.START, app);
        assertEquals(List.of(new PollResult.Refusal("item-1", "APP-NOT-OURS")), r.refused());
        assertEquals(List.of("item-2"), source.acknowledged());
    }

    @Test
    void aRestartInTheMiddleOfAPassIsSafe() {
        StoredIntegration s = start(source(PollSource.Paging.cursor("/paging/next", "cursor", null, 2)), 6);
        source.pageSize(2);
        App app = new App();
        app.failOn.add("item-4"); // the app (or the process) fails in the middle of page 2
        PollResult r = engine().pass(s, CursorState.START, app);
        assertEquals(IntegrationCode.INT_POLL_HANDLER_FAILED, r.failure().code());
        assertEquals("handler", r.failure().step());
        assertEquals("c2", r.nextState().position());
        assertFalse(source.acknowledged().contains("item-4"));
        // A new engine (a restarted app) carries on from the state the app stored.
        PollResult rest = new PollEngine(calls, Scanning.OFF, work).pass(s, r.nextState(), app);
        assertTrue(rest.ok(), rest.toString());
        assertEquals(1, rest.alreadyTaken(), "item-3 was taken before the failure");
        assertEquals(3, rest.taken());
        assertEquals(6, app.have.size());
        assertEquals(List.of("item-1", "item-2", "item-3", "item-3", "item-4", "item-5", "item-6"),
                source.acknowledged(), "acknowledges repeat, takes never do");
    }

    @Test
    void aQueueReadFromTheStartEveryPass() {
        StoredIntegration s = start(source(PollSource.Paging.pageNumber("page", null, 2, 1).withMaxPagesPerPass(1)
                .withResume(PollSource.Paging.Resume.FROM_START)), 5);
        source.pageSize(2).removeOnAcknowledge(true);
        App app = new App();
        CursorState state = CursorState.START;
        for (int pass = 0; pass < 3; pass++) {
            PollResult r = engine().pass(s, state, app);
            assertEquals(CursorState.START, r.nextState());
            state = r.nextState();
        }
        assertEquals(5, app.have.size());
        assertTrue(source.items().isEmpty());
    }

    @Test
    void anItemWithoutAnIdIsReportedAndLeftAlone() {
        PollSource src = new PollSource(CallStep.of("list", HttpMethod.GET, "/api/items"), "/data/items", "/id",
                null, null, PollSource.Paging.none(), CallStep.of("download", HttpMethod.GET, "/api/{item.id}"), null);
        StoredIntegration s = start(src, 2);
        source.withoutIds(true);
        PollResult r = engine().pass(s, CursorState.START, new App());
        assertTrue(r.ok());
        assertEquals(2, r.problems().size());
        assertTrue(r.problems().stream().allMatch(p -> p.code() == IntegrationCode.INT_POLL_ITEM_ID_MISSING
                && p.sourceItemId() == null));
        assertTrue(source.downloads().isEmpty());
    }

    @Test
    void offOrAnotherKindRunsNothing() {
        StoredIntegration s = start(source(PollSource.Paging.none()), 1);
        PollResult off = engine().pass(Fx.stored(s.config().withEnabled(false)), CursorState.START, new App());
        assertEquals(IntegrationCode.INT_DISABLED, off.failure().code());
        PollResult push = engine().pass(Fx.stored(Fx.oneStep(source.address(), new SignIn.None())), CursorState.START,
                new App());
        assertEquals(IntegrationCode.INT_KIND_NOT_SUPPORTED, push.failure().code());
        assertEquals(0, source.listCalls());
    }
}
