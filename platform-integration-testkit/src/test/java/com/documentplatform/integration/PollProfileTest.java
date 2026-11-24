package com.documentplatform.integration;

import com.documentplatform.integration.config.ConfigCheck;
import com.documentplatform.integration.config.Endpoint;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.Kind;
import com.documentplatform.integration.config.PollSource;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.engine.CallEngine;
import com.documentplatform.integration.engine.PollEngine;
import com.documentplatform.outbound.Outbound;
import com.documentplatform.integration.intake.CursorState;
import com.documentplatform.integration.intake.PollResult;
import com.documentplatform.integration.profile.IntegrationProfile;
import com.documentplatform.integration.profile.ProfileProblem;
import com.documentplatform.integration.profile.ProfileValidator;
import com.documentplatform.outbound.Installation;
import com.documentplatform.integration.scan.Scanning;
import com.documentplatform.integration.testkit.MockSource;
import com.documentplatform.notifications.inmemory.InMemorySecretResolver;
import com.documentplatform.notifications.secret.SecretRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** API_POLL in a profile (format /1, additions only): checked, completed, and run against the mock of the profile. */
class PollProfileTest {

    static final String PICKUP = "example-pickup-v1";

    @TempDir
    Path work;

    @Test
    void aPollingProfileIsCheckedCompletedAndRunsAgainstItsMock() {
        IntegrationProfile p = ProfileFiles.profile(PICKUP);
        assertEquals(Kind.API_POLL, p.kind());
        assertTrue(p.steps().isEmpty());
        assertEquals(PollSource.Paging.Mode.CURSOR, p.poll().paging().mode());
        assertEquals(List.of(PollSource.LIST, PollSource.DOWNLOAD, PollSource.ACKNOWLEDGE),
                List.of(p.poll().list().name(), p.poll().download().name(), p.poll().acknowledge().name()));
        try (MockSource source = MockSource.forProfile(p)) {
            for (int i = 1; i <= 5; i++) {
                source.add("pk-" + i, ("%PDF-1.4 pickup " + i).getBytes(), Map.of("fileName", "pickup-" + i + ".pdf",
                        "sender", "S-" + i));
            }
            IntegrationConfig c = p.complete(new IntegrationProfile.Completion(Fx.ENTITY, "DEMO", "Pickups",
                    new Endpoint(source.address(), true), new SignIn.ApiKey("X-Api-Key", SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/key")),
                    Map.of(), null, Map.of()));
            assertEquals(Kind.API_POLL, c.kind());
            assertEquals(PICKUP, c.profile());
            assertEquals(p.poll(), c.poll());
            Installation onPremises = Installation.onPremises().withInternalAddresses(true);
            assertEquals(List.of(), ConfigCheck.check(c, onPremises).errors());
            InMemorySecretResolver secrets = new InMemorySecretResolver().put(SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/key"), "k-1");
            try (CallEngine calls = new CallEngine(new Outbound(onPremises), secrets, Clock.systemUTC())) {
                PollingTest.App app = new PollingTest.App();
                PollResult r = new PollEngine(calls, Scanning.OFF, work).pass(Fx.stored(c), CursorState.START, app);
                assertTrue(r.ok(), r.toString());
                assertEquals(5, r.taken());
                assertEquals(3, r.pages());
                assertEquals(5, source.acknowledged().size());
                assertEquals("S-2", app.values.get("pk-2").get("sender"));
                assertTrue(calls.health(Fx.stored(c)).healthy());
            }
        }
    }

    @Test
    void theFormatChecksForPolling() {
        String ok = ProfileFiles.text(PICKUP + ".profile.json");
        assertTrue(ProfileValidator.validate(ok).valid(), ProfileValidator.validate(ok).problems().toString());
        // API_POLL needs poll, has no steps and is inbound; API_PUSH has no poll.
        assertCode(ProfileFiles.changed(PICKUP + ".profile.json", m -> m.remove("poll")), IntegrationCode.INT_PROFILE_STEPS,
                "/poll");
        assertCode(ProfileFiles.changed(PICKUP + ".profile.json", m -> m.put("direction", "OUTBOUND")),
                IntegrationCode.INT_PROFILE_STEPS, "/direction");
        assertCode(ProfileFiles.changed(PICKUP + ".profile.json", m -> m.put("steps", List.of(Map.of("name", "x",
                "method", "GET", "path", "/x", "body", "NONE", "repeat", "ONCE")))), IntegrationCode.INT_PROFILE_STEPS, "");
        assertCode(ProfileFiles.changed(ProfileFiles.ACME + ".profile.json", m -> m.put("poll", poll(m))),
                IntegrationCode.INT_PROFILE_STEPS, "/poll");
        // The save checks, mapped to profile codes with pointers.
        assertCode(ProfileFiles.changed(PICKUP + ".profile.json", m -> step(m, "download").put("path", "/v1/{value.x}")),
                IntegrationCode.INT_PROFILE_TEMPLATE, "/poll/download/path");
        assertCode(ProfileFiles.changed(PICKUP + ".profile.json", m -> step(m, "list").put("path", "/v1/{item.id}")),
                IntegrationCode.INT_PROFILE_TEMPLATE, "/poll/list/path");
        assertCode(ProfileFiles.changed(PICKUP + ".profile.json", m -> paging(m).put("param", null)),
                IntegrationCode.INT_PROFILE_SCHEMA, "/poll/paging/param");
        assertCode(ProfileFiles.changed(PICKUP + ".profile.json", m -> paging(m).remove("param")),
                IntegrationCode.INT_PROFILE_STEPS, "/poll/paging");
        assertCode(ProfileFiles.changed(PICKUP + ".profile.json", m -> paging(m).put("pageSize", 5000L)),
                IntegrationCode.INT_PROFILE_SCHEMA, "/poll/paging/pageSize");
        // Still no installation data.
        assertCode(ProfileFiles.changed(PICKUP + ".profile.json", m -> step(m, "download").put("path",
                "/v1/x?next=https://elsewhere.example")), IntegrationCode.INT_PROFILE_INSTALLATION_DATA,
                "/poll/download/path");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> step(Map<String, Object> m, String name) {
        return (Map<String, Object>) ((Map<String, Object>) m.get("poll")).get(name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> paging(Map<String, Object> m) {
        return (Map<String, Object>) ((Map<String, Object>) m.get("poll")).get("paging");
    }

    private static Object poll(Map<String, Object> m) {
        return ProfileFiles.deepCopy(new java.util.LinkedHashMap<>(Map.of("list", Map.of("method", "GET", "path", "/x"),
                "itemsPointer", "/items", "idPointer", "/id", "paging", Map.of("mode", "NONE"), "download",
                Map.of("method", "GET", "path", "/x/{item.id}"))));
    }

    private static void assertCode(String text, IntegrationCode code, String pointer) {
        List<ProfileProblem> problems = ProfileValidator.validate(text).problems();
        assertTrue(problems.stream().anyMatch(p -> p.code() == code && p.pointer().equals(pointer)),
                code + " at " + pointer + " in " + problems);
    }
}
