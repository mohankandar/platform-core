package com.documentplatform.integration;

import com.documentplatform.integration.config.CallStep;
import com.documentplatform.integration.config.ConfigCheck;
import com.documentplatform.integration.config.ConfigJson;
import com.documentplatform.integration.config.HttpMethod;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.engine.CallEngine;
import com.documentplatform.outbound.Outbound;
import com.documentplatform.integration.profile.IntegrationProfile;
import com.documentplatform.integration.profile.ProfileValidator;
import com.documentplatform.integration.result.AttemptResult;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.integration.result.PendingStatus;
import com.documentplatform.outbound.Installation;
import com.documentplatform.integration.testkit.MockDestination;
import com.documentplatform.integration.testkit.ProfileContract;
import com.documentplatform.notifications.inmemory.InMemorySecretResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static com.documentplatform.integration.ProfileFiles.arr;
import static com.documentplatform.integration.ProfileFiles.changed;
import static com.documentplatform.integration.ProfileFiles.obj;
import static org.junit.jupiter.api.Assertions.*;

/**
 * B4, "accepted, check later": a step that declares the destination may answer 202. The attempt ends ACCEPTED with a
 * pending status; {@code checkStatus} makes one call each time: still working, done, failed, or the status call's own
 * failure. The engine never waits; Retry-After (or the configured wait) sets the next check time.
 */
class AcceptedLaterTest {

    static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    static final String ASYNC = "example-async-v1";

    private MockDestination mock;
    private CallEngine engine;

    @BeforeEach
    void start() {
        mock = MockDestination.start();
        engine = new CallEngine(new Outbound(Installation.onPremises().withInternalAddresses(true)
                .withSignInNone(true)), new InMemorySecretResolver(), Clock.fixed(NOW, ZoneOffset.UTC));
        mock.expectNone();
    }

    @AfterEach
    void stop() {
        engine.close();
        mock.close();
    }

    private IntegrationConfig byLocation;

    private IntegrationConfig byLocation() {
        if (byLocation != null) return byLocation;
        CallStep send = CallStep.of("send", HttpMethod.POST, "/api/notice")
                .withJsonBody("{\"packageId\":\"{package.id}\"}")
                .acceptingLater(CallStep.Accepted.byLocation("/state", Set.of("DONE"), Set.of("FAILED"),
                        Set.of("WORKING")).reading("resultId", "/resultId"));
        byLocation = Fx.oneStep(mock.address(), new SignIn.None()).withSteps(List.of(send));
        return byLocation;
    }

    @Test
    void acceptedThenStillWorkingThenDone() {
        mock.acceptLater("POST", "/api/notice", 1, "DONE", Duration.ofSeconds(7));
        AttemptResult first = engine.attempt(Fx.stored(byLocation()), Fx.delivery(1));
        assertEquals(Outcome.ACCEPTED, first.outcome(), first.toString());
        assertEquals(IntegrationCode.INT_ACCEPTED, first.code());
        assertEquals(202, first.httpStatus());
        assertFalse(first.attemptUsed(), "accepted uses no attempt");
        PendingStatus p = first.pending();
        assertEquals(MockDestination.STATUS_PATH + "st-1", p.reference());
        assertEquals(NOW.plusSeconds(7), p.nextCheckAt(), "the destination's Retry-After");
        assertEquals("send", p.step());

        // The app keeps it as text with its case and reads it back.
        PendingStatus kept = PendingStatus.fromJson(p.toJson());
        assertEquals(p, kept);

        AttemptResult working = engine.checkStatus(Fx.stored(byLocation()), kept);
        assertEquals(Outcome.ACCEPTED, working.outcome(), working.toString());
        assertEquals(NOW.plusSeconds(7), working.pending().nextCheckAt());
        assertEquals(1, mock.statusPolls("st-1"));

        AttemptResult done = engine.checkStatus(Fx.stored(byLocation()), working.pending());
        assertEquals(Outcome.SENT, done.outcome(), done.toString());
        assertEquals(IntegrationCode.INT_SENT, done.code());
        assertEquals("res-st-1", done.values().get("send.resultId"));
        assertNull(done.pending());
        assertEquals(2, mock.statusPolls("st-1"));
        // Three calls in all: the request and two status calls. The engine never waited or asked twice.
        assertEquals(3, mock.calls().size());
    }

    @Test
    void failedIsRefusedWithTheDestinationsCode() {
        mock.acceptLater("POST", "/api/notice", 0, "FAILED", null);
        AttemptResult first = engine.attempt(Fx.stored(byLocation()), Fx.delivery(1));
        assertEquals(NOW.plus(CallStep.Accepted.DEFAULT_WAIT), first.pending().nextCheckAt(),
                "no Retry-After: the configured wait");
        AttemptResult failed = engine.checkStatus(Fx.stored(byLocation()), first.pending());
        assertEquals(Outcome.REFUSED, failed.outcome());
        assertEquals(IntegrationCode.INT_STATUS_FAILED, failed.code());
        assertEquals("MOCK-STATUS-FAILED", failed.destinationCode());
    }

    @Test
    void aRefusedStatusCallAndAnOutageAreTheUsualResults() {
        mock.acceptLater("POST", "/api/notice", 5, "DONE", Duration.ofSeconds(3));
        PendingStatus p = engine.attempt(Fx.stored(byLocation()), Fx.delivery(1)).pending();
        mock.refuse(409, "MOCK-CONFLICT");
        AttemptResult refused = engine.checkStatus(Fx.stored(byLocation()), p);
        assertEquals(Outcome.REFUSED, refused.outcome());
        assertEquals("MOCK-CONFLICT", refused.destinationCode());
        mock.outage();
        assertEquals(Outcome.OUTAGE, engine.checkStatus(Fx.stored(byLocation()), p).outcome());
        mock.busy(429, null, Duration.ofSeconds(90));
        AttemptResult busy = engine.checkStatus(Fx.stored(byLocation()), p);
        assertEquals(Outcome.BUSY, busy.outcome());
        assertEquals(Duration.ofSeconds(90), busy.retryAfter());
        mock.working();
        assertEquals(Outcome.ACCEPTED, engine.checkStatus(Fx.stored(byLocation()), p).outcome(), "still working");
    }

    @Test
    void byPointerWithAStatusPath() {
        mock.acceptLater("POST", "/api/notice", 0, "DONE", Duration.ofSeconds(2));
        CallStep send = CallStep.of("send", HttpMethod.POST, "/api/notice")
                .withJsonBody("{\"packageId\":\"{package.id}\"}")
                .acceptingLater(CallStep.Accepted.byPointer("/statusId", "/api/status/{status.reference}", "/state",
                        Set.of("DONE"), Set.of("FAILED"), Set.of("WORKING")).withDefaultWait(Duration.ofSeconds(45)));
        IntegrationConfig c = byLocation().withSteps(List.of(send));
        assertTrue(ConfigCheck.check(c, Installation.onPremises().withInternalAddresses(true).withSignInNone(true))
                .ok());
        AttemptResult first = engine.attempt(Fx.stored(c), Fx.delivery(1));
        assertEquals("st-1", first.pending().reference());
        assertEquals(Outcome.SENT, engine.checkStatus(Fx.stored(c), first.pending()).outcome());
        // The stored form keeps it.
        assertEquals(c, ConfigJson.read(ConfigJson.write(c)));
    }

    @Test
    void a202WithoutTheReferenceIsUnclear() {
        mock.acceptLater("POST", "/api/notice", 0, "DONE", null);
        CallStep send = CallStep.of("send", HttpMethod.POST, "/api/notice")
                .withJsonBody("{\"packageId\":\"{package.id}\"}")
                .acceptingLater(CallStep.Accepted.byPointer("/missing", "/api/status/{status.reference}", "/state",
                        Set.of("DONE"), Set.of("FAILED"), Set.of()));
        AttemptResult r = engine.attempt(Fx.stored(byLocation().withSteps(List.of(send))), Fx.delivery(1));
        assertEquals(Outcome.UNCLEAR, r.outcome());
        assertEquals(IntegrationCode.INT_STATUS_REFERENCE_MISSING, r.code());
    }

    @Test
    void aLocationOnAnotherServerIsNeverFollowed() {
        // integration-module-120: the status reference stays on the base origin (scheme, host and port).
        try (MockDestination other = MockDestination.start()) {
            other.expectNone().acceptLater("POST", "/never", 0, "DONE", null);
            mock.acceptLater("POST", "/api/notice", 0, "DONE", null);
            for (URI elsewhere : List.of(other.address(), URI.create("http://status.example"),
                    URI.create("https://127.0.0.1:" + mock.port()))) {
                mock.acceptLaterLocation(elsewhere);
                AttemptResult r = engine.attempt(Fx.stored(byLocation()), Fx.delivery(1));
                assertEquals(Outcome.UNCLEAR, r.outcome(), elsewhere + " " + r);
                assertEquals(IntegrationCode.INT_STATUS_REFERENCE_MISSING, r.code());
                assertNull(r.pending(), "nothing to check later at another server");
            }
            // A stored pending status whose reference was changed to another server: no call is made there.
            mock.acceptLaterLocation(null);
            PendingStatus p = engine.attempt(Fx.stored(byLocation()), Fx.delivery(1)).pending();
            PendingStatus moved = new PendingStatus(p.integrationId(), p.configVersion(), p.step(),
                    other.address() + p.reference(), p.nextCheckAt(), p.correlationId(), p.values());
            int before = mock.calls().size();
            AttemptResult r = engine.checkStatus(Fx.stored(byLocation()), moved);
            assertEquals(Outcome.UNCLEAR, r.outcome(), r.toString());
            assertEquals(IntegrationCode.INT_STATUS_REFERENCE_MISSING, r.code());
            assertEquals(before, mock.calls().size());
            assertTrue(other.received().isEmpty(), "no call was made to the other server");
        }
    }

    @Test
    void withoutTheDeclarationA202IsSentAsBefore() {
        mock.acceptLater("POST", "/api/notice", 0, "DONE", null);
        IntegrationConfig plain = Fx.oneStep(mock.address(), new SignIn.None());
        AttemptResult r = engine.attempt(Fx.stored(plain), Fx.delivery(1));
        assertEquals(Outcome.SENT, r.outcome());
        assertNull(r.pending());
    }

    @Test
    void theConfigurationCheck() {
        Installation inst = Installation.onPremises().withInternalAddresses(true).withSignInNone(true);
        CallStep.Accepted a = CallStep.Accepted.byLocation("/state", Set.of("DONE"), Set.of("FAILED"), Set.of());
        // Only on the last step.
        IntegrationConfig notLast = byLocation().withSteps(List.of(
                CallStep.of("first", HttpMethod.POST, "/a").acceptingLater(a), CallStep.of("second", HttpMethod.POST,
                        "/b")));
        assertTrue(codes(notLast, inst).contains(IntegrationCode.INT_CONFIG_ACCEPTED_INVALID));
        // The same value in two lists; no failed values.
        IntegrationConfig overlap = byLocation().withSteps(List.of(CallStep.of("s", HttpMethod.POST, "/a")
                .acceptingLater(CallStep.Accepted.byLocation("/state", Set.of("X"), Set.of("X"), Set.of()))));
        assertTrue(codes(overlap, inst).contains(IntegrationCode.INT_CONFIG_ACCEPTED_INVALID));
        IntegrationConfig noFailed = byLocation().withSteps(List.of(CallStep.of("s", HttpMethod.POST, "/a")
                .acceptingLater(CallStep.Accepted.byLocation("/state", Set.of("DONE"), Set.of(), Set.of()))));
        assertTrue(codes(noFailed, inst).contains(IntegrationCode.INT_CONFIG_ACCEPTED_INVALID));
        // A status path without the reference; the reference placeholder in an ordinary step.
        IntegrationConfig noRef = byLocation().withSteps(List.of(CallStep.of("s", HttpMethod.POST, "/a")
                .acceptingLater(CallStep.Accepted.byPointer("/id", "/status", "/state", Set.of("D"), Set.of("F"),
                        Set.of()))));
        assertTrue(codes(noRef, inst).contains(IntegrationCode.INT_CONFIG_ACCEPTED_INVALID));
        IntegrationConfig misplaced = byLocation().withSteps(List.of(CallStep.of("s", HttpMethod.POST,
                "/a/{status.reference}")));
        assertTrue(codes(misplaced, inst).contains(IntegrationCode.INT_CONFIG_PLACEHOLDER_PLACE));
        // A rule cannot give ACCEPTED.
        assertThrows(IllegalArgumentException.class, () -> new com.documentplatform.integration.config.Rule("202",
                null, null, null, Outcome.ACCEPTED, null, null, null));
    }

    private static List<IntegrationCode> codes(IntegrationConfig c, Installation inst) {
        return ConfigCheck.check(c, inst).errors().stream().map(p -> p.code()).toList();
    }

    // ---- A profile using it ----

    @Test
    void aProfileWithAcceptedLaterIsValidAndFitsItsSpec() {
        assertTrue(ProfileValidator.validate(ProfileFiles.text(ASYNC + ".profile.json")).valid(),
                ProfileValidator.validate(ProfileFiles.text(ASYNC + ".profile.json")).toString());
        IntegrationProfile p = ProfileFiles.profile(ASYNC);
        CallStep submit = p.steps().get(0);
        assertNotNull(submit.accepted());
        assertEquals("/jobId", submit.accepted().referencePointer());
        assertEquals(Duration.ofSeconds(60), submit.accepted().defaultWait());
        assertEquals(List.of(), ProfileContract.checkAgainstSpec(p, ProfileFiles.spec(ASYNC)).stream()
                .map(ProfileContract.Problem::toString).toList());
    }

    @Test
    void theContractKitCatchesASpecWithoutTheStatus() {
        // The 202 answer loses the job ID.
        List<String> noRef = ProfileContract.checkAgainstSpec(ProfileFiles.profile(ASYNC),
                com.documentplatform.integration.testkit.openapi.OpenApiSpec.parse(changed(ASYNC + ".openapi.json",
                        s -> {
                            var post = obj(obj(obj(s.get("paths")).get("/v1/jobs")).get("post"));
                            var schema = obj(obj(obj(obj(obj(post.get("responses")).get("202")).get("content"))
                                    .get("application/json")).get("schema"));
                            obj(schema.get("properties")).remove("jobId");
                        }))).stream()
                .map(ProfileContract.Problem::toString).toList();
        assertTrue(noRef.stream().anyMatch(x -> x.contains("status reference (/jobId) is not in the 202 answer")),
                noRef.toString());
        // The status operation moves.
        List<String> moved = ProfileContract.checkAgainstSpec(ProfileFiles.profile(ASYNC),
                com.documentplatform.integration.testkit.openapi.OpenApiSpec.parse(changed(ASYNC + ".openapi.json",
                        s -> {
                            var paths = obj(s.get("paths"));
                            paths.put("/v2/jobs/{jobId}", paths.remove("/v1/jobs/{jobId}"));
                        }))).stream().map(ProfileContract.Problem::toString).toList();
        assertTrue(moved.stream().anyMatch(x -> x.contains("is not an operation of the spec")), moved.toString());
        // A new status error code without a rule.
        List<String> code = ProfileContract.checkAgainstSpec(ProfileFiles.profile(ASYNC),
                com.documentplatform.integration.testkit.openapi.OpenApiSpec.parse(changed(ASYNC + ".openapi.json",
                        s -> arr(obj(obj(obj(obj(obj(s.get("paths")).get("/v1/jobs/{jobId}")).get("get"))
                                .get("responses")).get("404")).get("x-error-codes")).add("OTHER-GONE")))).stream()
                .map(ProfileContract.Problem::toString).toList();
        assertTrue(code.stream().anyMatch(x -> x.contains("404 OTHER-GONE has no rule")), code.toString());
    }
}
