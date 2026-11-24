package com.documentplatform.integration;

import com.documentplatform.integration.config.CallStep;
import com.documentplatform.integration.config.ConfigCheck;
import com.documentplatform.integration.config.ConfigJson;
import com.documentplatform.integration.config.ConfigProblem;
import com.documentplatform.integration.config.Endpoint;
import com.documentplatform.integration.config.HttpMethod;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.Kind;
import com.documentplatform.integration.config.PollSource;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.config.WatchSource;
import com.documentplatform.integration.intake.CursorState;
import com.documentplatform.integration.intake.IntakeAnswer;
import com.documentplatform.outbound.Installation;
import com.documentplatform.notifications.secret.SecretRef;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The intake kinds' configuration: save checks, the stored form, and the small value types. */
class IntakeConfigTest {

    static final UUID ENTITY = UUID.fromString("0b6c3f52-1e2d-4a5b-9c8d-7e6f5a4b3c2d");
    static final Installation ON_PREMISES = Installation.onPremises();

    static PollSource pollSource() {
        return PollSource.of(CallStep.of("list", HttpMethod.GET, "/api/items?status=ready"), "/items", "/id",
                        PollSource.Paging.cursor("/next", "cursor", "limit", 50), CallStep.of("download",
                                HttpMethod.GET, "/api/items/{item.id}/content"))
                .reading("fileName", "/file/name")
                .withFileNameValue("fileName")
                .withAcknowledge(CallStep.of("acknowledge", HttpMethod.POST, "/api/items/{item.id}/ack")
                        .withJsonBody("{\"id\":\"{item.id}\",\"file\":\"{item.fileName}\"}"));
    }

    static IntegrationConfig poll() {
        return IntegrationConfig.apiPoll(ENTITY, "DEMO", "Source", Endpoint.of("https://source.example"),
                new SignIn.BearerFixed(SecretRef.of("int/0b6c3f52-1e2d-4a5b-9c8d-7e6f5a4b3c2d/src")), pollSource());
    }

    static IntegrationConfig watch() {
        return IntegrationConfig.watchedFolder(ENTITY, "DEMO", "Inbox", WatchSource.of("\\\\files01\\inbox\\in",
                        WatchSource.Layout.SUBFOLDER, "*.json")
                .mapping("number", "/loan/number").mapping("files", "/files/*/name").withFileListValue("files"));
    }

    private static List<ConfigProblem> errors(IntegrationConfig c) {
        return ConfigCheck.check(c, ON_PREMISES).errors();
    }

    private static void refused(IntegrationConfig c, IntegrationCode code, String where) {
        List<ConfigProblem> e = errors(c);
        assertTrue(e.stream().anyMatch(p -> p.code() == code && p.where().equals(where)), e.toString());
    }

    @Test
    void aPollingConfigurationPassesAndIsStoredAsJson() {
        IntegrationConfig c = poll();
        assertEquals(List.of(), errors(c));
        assertEquals(Kind.API_POLL, c.kind());
        assertEquals(c, ConfigJson.read(ConfigJson.write(c)));
        for (PollSource.Paging g : List.of(PollSource.Paging.none(), PollSource.Paging.nextLink(null),
                PollSource.Paging.nextLink("/links/next"), PollSource.Paging.pageNumber("page", "size", 25, 1)
                        .withMaxPagesPerPass(3).withResume(PollSource.Paging.Resume.FROM_START))) {
            IntegrationConfig x = c.withPoll(c.poll().withPaging(g));
            assertEquals(List.of(), errors(x), g.toString());
            assertEquals(x, ConfigJson.read(ConfigJson.write(x)));
        }
        assertTrue(ConfigJson.changedFields(c, c.withPoll(c.poll().withPaging(PollSource.Paging.none())))
                .contains("poll.paging.mode"));
        // A source answering the list itself (no wrapping object).
        assertEquals(List.of(), errors(c.withPoll(new PollSource(c.poll().list(), "", "/id", null, null,
                PollSource.Paging.none(), c.poll().download(), null))));
    }

    @Test
    void aPollingConfigurationIsCheckedWhenSaved() {
        IntegrationConfig c = poll();
        PollSource p = c.poll();
        refused(c.withPoll(null), IntegrationCode.INT_CONFIG_POLL_INVALID, "poll");
        refused(c.withPoll(new PollSource(p.list(), "items", "/id", null, null, p.paging(), p.download(), null)),
                IntegrationCode.INT_CONFIG_POLL_INVALID, "poll.itemsPointer");
        refused(c.withPoll(p.withFileNameValue("other")), IntegrationCode.INT_CONFIG_POLL_INVALID,
                "poll.fileNameValue");
        // The list call may not use item values; the per-item calls only the values read.
        refused(c.withPoll(new PollSource(CallStep.of("list", HttpMethod.GET, "/api/{item.id}"), "/items", "/id", null,
                null, p.paging(), p.download(), null)), IntegrationCode.INT_CONFIG_PLACEHOLDER_PLACE, "poll.list.path");
        refused(c.withPoll(new PollSource(p.list(), "/items", "/id", null, null, p.paging(),
                CallStep.of("download", HttpMethod.GET, "/api/{item.unknown}"), null)),
                IntegrationCode.INT_CONFIG_PLACEHOLDER_PLACE, "poll.download.path");
        refused(c.withPoll(new PollSource(p.list(), "/items", "/id", null, null, p.paging(),
                CallStep.of("download", HttpMethod.GET, "/api/{value.x}"), null)),
                IntegrationCode.INT_CONFIG_PLACEHOLDER_PLACE, "poll.download.path");
        // Placeholders in the query string are refused as anywhere else; so is a body on a GET.
        refused(c.withPoll(new PollSource(p.list(), "/items", "/id", null, null, p.paging(),
                CallStep.of("download", HttpMethod.GET, "/api/x?id={item.id}"), null)),
                IntegrationCode.INT_CONFIG_PLACEHOLDER_IN_QUERY, "poll.download.path");
        refused(c.withPoll(new PollSource(CallStep.of("list", HttpMethod.GET, "/api").withJsonBody("{}"), "/items",
                "/id", null, null, p.paging(), p.download(), null)), IntegrationCode.INT_CONFIG_POLL_INVALID,
                "poll.list");
        refused(c.withPoll(p.withAcknowledge(CallStep.of("acknowledge", HttpMethod.POST, "/ack")
                .withHeader("Authorization", "x"))), IntegrationCode.INT_CONFIG_HEADER_RESERVED,
                "poll.acknowledge.headers.Authorization");
        // Paging in range, with its parts.
        for (PollSource.Paging bad : List.of(PollSource.Paging.cursor("/next", null, null, 10),
                PollSource.Paging.cursor("/next", "c", null, 0), PollSource.Paging.pageNumber("p", "p", 10, 1),
                PollSource.Paging.pageNumber("p", null, 10, 2), PollSource.Paging.none().withMaxPagesPerPass(2),
                PollSource.Paging.nextLink(null).withMaxPagesPerPass(1001),
                PollSource.Paging.cursor("/next", "c d", null, 10))) {
            refused(c.withPoll(p.withPaging(bad)), IntegrationCode.INT_CONFIG_POLL_INVALID, "poll.paging");
        }
        // Polling has no outbound steps; push has no poll source and no item placeholders.
        refused(c.withSteps(List.of(CallStep.of("x", HttpMethod.GET, "/x"))), IntegrationCode.INT_CONFIG_STEPS_INVALID,
                "steps");
        IntegrationConfig push = IntegrationConfig.apiPush(ENTITY, "DEMO", "Push", Endpoint.of("https://d.example"),
                new SignIn.BearerFixed(SecretRef.of("t"))).withSteps(List.of(CallStep.of("send", HttpMethod.POST,
                "/x/{item.id}")));
        refused(push, IntegrationCode.INT_CONFIG_PLACEHOLDER_PLACE, "steps[0].path");
        refused(push.withSteps(List.of(CallStep.of("send", HttpMethod.POST, "/x"))).withPoll(p),
                IntegrationCode.INT_CONFIG_POLL_INVALID, "poll");
        // The sign-in and address rules are the outbound ones.
        refused(c.withEndpoint(Endpoint.of("http://source.example")), IntegrationCode.INT_CONFIG_ADDRESS_NOT_SECURE,
                "endpoint.baseAddress");
    }

    @Test
    void aWatchedFolderConfigurationPassesAndIsStoredAsJson() {
        IntegrationConfig c = watch();
        assertEquals(List.of(), errors(c));
        assertEquals(WatchSource.TriggerType.JSON, c.watch().triggerType());
        assertEquals(c, ConfigJson.read(ConfigJson.write(c)));
        IntegrationConfig xml = c.withWatch(WatchSource.of("D:\\inbox", WatchSource.Layout.BASE_NAME, "<base>.xml")
                .mapping("number", "/drop/loan/@number").mapping("files", "/drop/files/file[1]/name")
                .withStableFor(Duration.ofSeconds(5)).withIncompleteAfter(Duration.ofMinutes(10))
                .withClaimTimeout(Duration.ofMinutes(5)).withMaxDropsPerPass(10).withMaxTriggerBytes(4096));
        assertEquals(List.of(), errors(xml));
        assertEquals(WatchSource.TriggerType.XML, xml.watch().triggerType());
        assertEquals(xml, ConfigJson.read(ConfigJson.write(xml)));
        IntegrationConfig marker = c.withWatch(WatchSource.of("/srv/inbox", WatchSource.Layout.BASE_NAME,
                "<base>.done"));
        assertEquals(List.of(), errors(marker));
        assertEquals(WatchSource.TriggerType.MARKER, marker.watch().triggerType());
    }

    @Test
    void aWatchedFolderConfigurationIsCheckedWhenSaved() {
        IntegrationConfig c = watch();
        WatchSource w = c.watch();
        refused(c.withWatch(null), IntegrationCode.INT_CONFIG_WATCH_INVALID, "watch");
        refused(c.withWatch(WatchSource.of("inbox", WatchSource.Layout.SUBFOLDER, "*.json")),
                IntegrationCode.INT_CONFIG_WATCH_INVALID, "watch.root");
        refused(c.withWatch(WatchSource.of("D:\\in\\..\\x", WatchSource.Layout.SUBFOLDER, "*.json")),
                IntegrationCode.INT_CONFIG_WATCH_INVALID, "watch.root");
        for (String bad : List.of("a/b.json", "_x.json", "<base><base>.done", "x>y", "")) {
            refused(c.withWatch(WatchSource.of("D:\\in", WatchSource.Layout.SUBFOLDER, bad)),
                    IntegrationCode.INT_CONFIG_WATCH_INVALID, "watch.trigger");
        }
        refused(c.withWatch(WatchSource.of("D:\\in", WatchSource.Layout.BASE_NAME, "trigger")),
                IntegrationCode.INT_CONFIG_WATCH_INVALID, "watch.trigger");
        refused(c.withWatch(w.withStableFor(Duration.ZERO)), IntegrationCode.INT_CONFIG_WATCH_INVALID, "watch.stableFor");
        refused(c.withWatch(w.withIncompleteAfter(Duration.ofSeconds(20))), IntegrationCode.INT_CONFIG_WATCH_INVALID,
                "watch.incompleteAfter");
        refused(c.withWatch(w.withClaimTimeout(Duration.ofDays(2))), IntegrationCode.INT_CONFIG_WATCH_INVALID,
                "watch.claimTimeout");
        refused(c.withWatch(w.withMaxDropsPerPass(0)), IntegrationCode.INT_CONFIG_WATCH_INVALID, "watch.maxDropsPerPass");
        refused(c.withWatch(w.withMaxTriggerBytes(5L * 1024 * 1024)), IntegrationCode.INT_CONFIG_WATCH_INVALID,
                "watch.maxTriggerBytes");
        refused(c.withWatch(w.mapping("bad", "loan")), IntegrationCode.INT_CONFIG_WATCH_INVALID, "watch.mapping.bad");
        refused(c.withWatch(w.withTriggerType(WatchSource.TriggerType.XML).mapping("bad", "/a/b[0]")),
                IntegrationCode.INT_CONFIG_WATCH_INVALID, "watch.mapping.bad");
        refused(c.withWatch(w.withTriggerType(WatchSource.TriggerType.MARKER)), IntegrationCode.INT_CONFIG_WATCH_INVALID,
                "watch.mapping");
        refused(c.withWatch(w.withFileListValue("nothing")), IntegrationCode.INT_CONFIG_WATCH_INVALID,
                "watch.fileListValue");
        // No sign-in bundle, address or health call: the folder's rights are the service account's.
        refused(c.withSignIn(new SignIn.BearerFixed(SecretRef.of("x"))), IntegrationCode.INT_CONFIG_INVALID, "signIn");
        refused(c.withEndpoint(Endpoint.of("https://x.example")), IntegrationCode.INT_CONFIG_INVALID, "endpoint");
        // Never on the hosted service.
        assertTrue(ConfigCheck.check(c, Installation.hostedService()).errors().stream()
                .anyMatch(p -> p.code() == IntegrationCode.INT_CONFIG_FOLDER_HOSTED));
        // Another kind carries no watch source.
        assertTrue(errors(poll().withWatch(w)).stream().anyMatch(p -> p.code() == IntegrationCode.INT_CONFIG_WATCH_INVALID));
    }

    @Test
    void triggerPatterns() {
        WatchSource json = WatchSource.of("D:\\in", WatchSource.Layout.SUBFOLDER, "*.json");
        assertTrue(json.isTrigger("manifest.json", "drop-1"));
        assertTrue(json.isTrigger("MANIFEST.JSON", "drop-1"));
        assertFalse(json.isTrigger("manifest.json.tmp", "drop-1"));
        WatchSource done = WatchSource.of("D:\\in", WatchSource.Layout.BASE_NAME, "<base>.done");
        assertTrue(done.isTrigger("loan-17.done", "loan-17"));
        assertFalse(done.isTrigger("loan-18.done", "loan-17"));
        assertFalse(done.isTrigger("loan-17.pdf", "loan-17"));
        WatchSource exact = WatchSource.of("D:\\in", WatchSource.Layout.SUBFOLDER, "ready?.lck");
        assertTrue(exact.isTrigger("ready1.lck", "x"));
        assertFalse(exact.isTrigger("ready.lck", "x"));
        assertEquals("WatchSource[share, SUBFOLDER, *.json]", watch().watch().toString(), "the path is not shown");
    }

    @Test
    void answersAndCursorStates() {
        assertTrue(IntakeAnswer.TAKEN.has());
        assertTrue(IntakeAnswer.ALREADY_TAKEN.has());
        assertFalse(IntakeAnswer.refused("APP-17").has());
        assertThrows(IllegalArgumentException.class, () -> IntakeAnswer.refused("not a code"));
        assertThrows(IllegalArgumentException.class, () -> IntakeAnswer.refused(null));
        assertThrows(IllegalArgumentException.class, () -> new IntakeAnswer(IntakeAnswer.Kind.TAKEN, "X"));
        assertTrue(CursorState.START.atStart());
        assertEquals(CursorState.START, CursorState.of(null));
        assertEquals("CursorState[set]", CursorState.of("abc").toString(), "the cursor is kept out of logs");
        assertThrows(IllegalArgumentException.class, () -> CursorState.of(""));
        assertThrows(IllegalArgumentException.class, () -> CursorState.of("a\nb"));
        assertThrows(IllegalArgumentException.class, () -> CursorState.of("x".repeat(CursorState.MAX + 1)));
    }
}
