package com.documentplatform.integration;

import com.documentplatform.integration.callback.CallbackEntry;
import com.documentplatform.integration.callback.CallbackSignature;
import com.documentplatform.integration.callback.CallbackState;
import com.documentplatform.integration.callback.CallbackVerifier;
import com.documentplatform.integration.callback.Callbacks;
import com.documentplatform.integration.config.ConfigCheck;
import com.documentplatform.integration.config.ConfigJson;
import com.documentplatform.integration.config.Endpoint;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.engine.CallEngine;
import com.documentplatform.integration.engine.TestAction;
import com.documentplatform.outbound.Outbound;
import com.documentplatform.integration.result.AttemptResult;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.outbound.Installation;
import com.documentplatform.integration.store.ConfigChange;
import com.documentplatform.integration.store.IntegrationAudit;
import com.documentplatform.integration.store.StoredIntegration;
import com.documentplatform.integration.testkit.InMemoryCallbackOutbox;
import com.documentplatform.integration.testkit.InMemoryIntegrationStore;
import com.documentplatform.notifications.inmemory.InMemorySecretResolver;
import com.documentplatform.notifications.secret.SecretRef;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B3, signed callbacks: the signature vector, sending through the outbox (retries, Retry-After, given up, resent,
 * claims with a lease), the receiver's check of each callback with {@link CallbackVerifier}, and its refusals. The
 * Postgres outbox (restart safety) is in platform-integration's {@code PostgresCallbackOutboxTest}.
 */
class CallbackTest {

    static final SecretRef SECRET = SecretRef.of("int/callback-secret");
    static final String BODY = "{\"case\":\"C-1\",\"state\":\"DONE\"}";

    /** A clock the test moves. */
    static final class MovingClock extends Clock {
        Instant now = Instant.parse("2026-11-22T10:00:00Z");

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
    }

    /** A receiver that checks every callback with the verifier and answers what the test says. */
    static final class Receiver implements AutoCloseable {
        final HttpServer server;
        final List<String> secrets = new ArrayList<>(List.of("whsec-test-1"));
        final CallbackVerifier.SeenIds seen = CallbackVerifier.SeenIds.inMemory(1000);
        final List<CallbackVerifier.Result> results = Collections.synchronizedList(new ArrayList<>());
        final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
        final AtomicReference<Integer> status = new AtomicReference<>(200);
        final AtomicReference<String> retryAfter = new AtomicReference<>();
        final MovingClock clock;

        Receiver(MovingClock clock) throws IOException {
            this.clock = clock;
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", ex -> {
                byte[] body = ex.getRequestBody().readAllBytes();
                CallbackVerifier.Result r = CallbackVerifier.verify(ex.getRequestHeaders()::getFirst, body,
                        List.copyOf(secrets), seen, clock.instant());
                results.add(r);
                bodies.add(new String(body, StandardCharsets.UTF_8));
                int s = r.ok() || r.failure() == IntegrationCode.INT_CALLBACK_REPLAYED ? status.get() : 401;
                if (retryAfter.get() != null) ex.getResponseHeaders().add("Retry-After", retryAfter.get());
                byte[] answer = "{}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(s, answer.length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(answer);
                }
            });
            server.start();
        }

        URI address() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hooks/cases");
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private final MovingClock clock = new MovingClock();
    private final InMemorySecretResolver secrets = new InMemorySecretResolver();
    private final InMemoryIntegrationStore store = new InMemoryIntegrationStore();
    private final InMemoryCallbackOutbox outbox = new InMemoryCallbackOutbox();
    private Receiver receiver;
    private CallEngine engine;
    private Callbacks callbacks;
    private StoredIntegration integration;

    @BeforeEach
    void start() throws IOException {
        receiver = new Receiver(clock);
        secrets.put(SECRET, "whsec-test-1");
        engine = new CallEngine(new Outbound(Installation.onPremises().withInternalAddresses(true)), secrets, clock);
        callbacks = new Callbacks(outbox, store, engine, clock);
        IntegrationConfig c = IntegrationConfig.callback(Fx.ENTITY, Fx.PRODUCT, "Case callbacks",
                new Endpoint(receiver.address(), false), new SignIn.None(), SECRET);
        integration = store.create(c, new ConfigChange(c.id(), c.entity(), c.product(), 1, "test", clock.instant(),
                ConfigJson.fields(c), List.of()));
    }

    @AfterEach
    void stop() {
        engine.close();
        receiver.close();
    }

    // ---- The signature ----

    @Test
    void theSignatureVector() {
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        // HMAC-SHA256("whsec-test-1", "1700000000." + body), lower-case hex (computed with openssl dgst -hmac).
        assertEquals("c374d8b6f6e2240e381b93831661f2782ad111ecee63e50f09fbdd061ad70464",
                CallbackSignature.v1("whsec-test-1", 1700000000L, body));
        assertEquals("497d387712895633901069f2a612ef6920569725ce5c8e11dfb75bae6fc2b3a8",
                CallbackSignature.v1("whsec-test-2", 1700000000L, body));
        // v2 (P044): HMAC-SHA256("whsec-test-1", "1700000000.cb-example-1." + body), computed with openssl dgst -hmac.
        assertEquals("2039c9328fff815a89ac1cfdf5f70a4410f2d733fda64ef106f7f2e8f00ed2e1",
                CallbackSignature.v2("whsec-test-1", 1700000000L, "cb-example-1", body));
        assertEquals("t=1700000000,v2=2039c9328fff815a89ac1cfdf5f70a4410f2d733fda64ef106f7f2e8f00ed2e1",
                CallbackSignature.header("whsec-test-1", 1700000000L, "cb-example-1", body));
    }

    @Test
    void aCapturedCallbackSentAgainUnderANewIdIsRefused() {
        // P044, SEC-8: the review's attack. With v1 the ID was not signed, so every resend under a fresh ID passed the
        // signature, the window and the repeat check.
        Instant at = Instant.ofEpochSecond(1700000000L);
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        CallbackVerifier.SeenIds seen = CallbackVerifier.SeenIds.inMemory(100);
        List<String> one = List.of("whsec-test-1");
        String signed = CallbackSignature.header("whsec-test-1", at.getEpochSecond(), "cb-A", body);
        assertTrue(check(Map.of(CallbackSignature.SIGNATURE_HEADER, signed, CallbackSignature.ID_HEADER, "cb-A"), BODY,
                one, seen, at).ok());
        assertEquals(IntegrationCode.INT_CALLBACK_SIGNATURE, check(Map.of(CallbackSignature.SIGNATURE_HEADER, signed,
                CallbackSignature.ID_HEADER, "cb-B"), BODY, one, seen, at).failure(), "resent under a new ID");

        // An old v1 callback: refused by default.
        String v1 = "t=" + at.getEpochSecond() + ",v1=" + CallbackSignature.v1("whsec-test-1", at.getEpochSecond(), body);
        assertEquals(IntegrationCode.INT_CALLBACK_SIGNATURE, check(Map.of(CallbackSignature.SIGNATURE_HEADER, v1,
                CallbackSignature.ID_HEADER, "cb-C"), BODY, one, seen, at).failure());
        // Accepted when the installation turns v1 on; then a copy under a new ID is a repeat (keyed on the signature).
        CallbackVerifier.Policy withV1 = new CallbackVerifier.Policy(true, null);
        assertTrue(withV1.verify(Map.of(CallbackSignature.SIGNATURE_HEADER, v1, CallbackSignature.ID_HEADER,
                "cb-C")::get, body, one, seen, at).ok());
        assertEquals(IntegrationCode.INT_CALLBACK_REPLAYED, withV1.verify(Map.of(CallbackSignature.SIGNATURE_HEADER, v1,
                CallbackSignature.ID_HEADER, "cb-D")::get, body, one, seen, at).failure());
    }

    // ---- Sending ----

    @Test
    void aCallbackGoesOutSignedAndTheReceiverAcceptsIt() {
        UUID id = callbacks.enqueue(integration.id(), BODY);
        assertEquals(1, callbacks.deliverDue(10));
        assertEquals(CallbackState.SENT, callbacks.find(id).orElseThrow().state());
        assertEquals(1, receiver.results.size());
        assertTrue(receiver.results.get(0).ok(), receiver.results.toString());
        assertEquals(id.toString(), receiver.results.get(0).callbackId());
        assertEquals(BODY, receiver.bodies.get(0));
        assertEquals(0, callbacks.deliverDue(10), "nothing left");
    }

    @Test
    void retriesWithBackOffAndRetryAfterThenGivesUpAndIsResent() {
        receiver.status.set(503);
        UUID id = callbacks.enqueue(integration.id(), BODY);
        Instant created = clock.now;
        receiver.retryAfter.set("120");
        callbacks.deliverDue(10);
        CallbackEntry e = callbacks.find(id).orElseThrow();
        assertEquals(CallbackState.PENDING, e.state());
        assertEquals(1, e.attempts());
        assertEquals("OUTAGE", e.lastOutcome());
        assertEquals(created.plusSeconds(120), e.nextAttemptAt(), "the receiver's Retry-After");

        assertEquals(0, callbacks.deliverDue(10), "not due yet: the engine never waits, the outbox keeps the time");
        clock.now = e.nextAttemptAt();
        receiver.retryAfter.set(null);
        callbacks.deliverDue(10);
        e = callbacks.find(id).orElseThrow();
        assertEquals(2, e.attempts());
        assertEquals(clock.now.plus(Duration.ofMinutes(2)), e.nextAttemptAt(), "back-off: 1, 2, 4 ... minutes");

        // A Retry-After longer than the longest delay is cut to it.
        clock.now = e.nextAttemptAt();
        receiver.retryAfter.set(Long.toString(Duration.ofHours(10).toSeconds()));
        callbacks.deliverDue(10);
        e = callbacks.find(id).orElseThrow();
        assertEquals(clock.now.plus(Duration.ofHours(6)), e.nextAttemptAt());

        // 24 hours after it was created: given up, listed, and the app can resend it.
        clock.now = created.plus(Duration.ofHours(23)).plus(Duration.ofMinutes(55)); // the next try would pass 24 h
        receiver.retryAfter.set(null);
        callbacks.deliverDue(10);
        e = callbacks.find(id).orElseThrow();
        assertEquals(CallbackState.GAVE_UP, e.state(), e.toString());
        assertEquals(List.of(id), callbacks.gaveUp(Fx.ENTITY, Fx.PRODUCT, 10).stream().map(CallbackEntry::id).toList());

        receiver.status.set(200);
        callbacks.resend(id);
        assertEquals(CallbackState.PENDING, callbacks.find(id).orElseThrow().state());
        callbacks.deliverDue(10);
        assertEquals(CallbackState.SENT, callbacks.find(id).orElseThrow().state());
        // Every attempt carried the same callback ID: the receiver could ignore repeats by it.
        assertTrue(receiver.results.stream().allMatch(r -> id.toString().equals(r.callbackId())));
        Callbacks.Refused notGivenUp = assertThrows(Callbacks.Refused.class, () -> callbacks.resend(id));
        assertEquals(IntegrationCode.INT_CALLBACK_NOT_FOUND, notGivenUp.code());
    }

    @Test
    void aRefusalGivesUpAtOnce() {
        receiver.status.set(400);
        UUID id = callbacks.enqueue(integration.id(), BODY);
        callbacks.deliverDue(10);
        CallbackEntry e = callbacks.find(id).orElseThrow();
        assertEquals(CallbackState.GAVE_UP, e.state());
        assertEquals("REFUSED", e.lastOutcome());
        assertEquals(400, e.lastStatus());
    }

    @Test
    void aClaimIsALeaseSoOneSenderHasItAtATime() {
        UUID id = callbacks.enqueue(integration.id(), BODY);
        // A sender claims it and stops before recording anything (a restart).
        List<CallbackEntry> claimed = outbox.claimDue(clock.now, clock.now.plus(Callbacks.DEFAULT_LEASE), 10);
        assertEquals(1, claimed.size());
        assertEquals(0, callbacks.deliverDue(10), "still held by the first sender");
        assertTrue(receiver.results.isEmpty());
        clock.now = clock.now.plus(Callbacks.DEFAULT_LEASE).plusSeconds(1);
        assertEquals(1, callbacks.deliverDue(10), "the lease ran out: claimed again");
        assertEquals(CallbackState.SENT, callbacks.find(id).orElseThrow().state());
        assertEquals(1, receiver.results.size(), "sent once");
    }

    @Test
    void enqueueChecksTheBodyAndTheKind() {
        for (String bad : List.of("[1,2]", "not json", "\"text\"")) {
            Callbacks.Refused r = assertThrows(Callbacks.Refused.class, () -> callbacks.enqueue(integration.id(), bad));
            assertEquals(IntegrationCode.INT_CALLBACK_BODY_INVALID, r.code());
        }
        String big = "{\"x\":\"" + "a".repeat(Callbacks.MAX_BODY_BYTES) + "\"}";
        assertEquals(IntegrationCode.INT_CALLBACK_BODY_INVALID, assertThrows(Callbacks.Refused.class,
                () -> callbacks.enqueue(integration.id(), big)).code());
        IntegrationConfig push = Fx.oneStep(receiver.address(), new SignIn.BearerFixed(SecretRef.of("int/t")));
        StoredIntegration p = store.create(push, new ConfigChange(push.id(), push.entity(), push.product(), 1, "t",
                clock.instant(), ConfigJson.fields(push), List.of()));
        assertEquals(IntegrationCode.INT_KIND_NOT_SUPPORTED, assertThrows(Callbacks.Refused.class,
                () -> callbacks.enqueue(p.id(), BODY)).code());
        assertEquals(IntegrationCode.INT_CONFIG_NOT_FOUND, assertThrows(Callbacks.Refused.class,
                () -> callbacks.enqueue(UUID.randomUUID(), BODY)).code());
    }

    @Test
    void aMissingSecretIsNotConfiguredAndRetried() {
        InMemorySecretResolver none = new InMemorySecretResolver();
        try (CallEngine e = new CallEngine(new Outbound(Installation.onPremises().withInternalAddresses(true)), none,
                clock)) {
            AttemptResult r = e.sendCallback(integration, UUID.randomUUID(), BODY);
            assertEquals(Outcome.NOT_CONFIGURED, r.outcome());
            assertEquals(IntegrationCode.INT_SECRET_NOT_FOUND, r.code());
        }
        assertTrue(receiver.results.isEmpty());
    }

    @Test
    void theTestActionSendsASignedTestCallback() {
        AttemptResult r = new TestAction(engine, IntegrationAudit.NONE).run(integration, "it-admin");
        assertEquals(Outcome.SENT, r.outcome(), r.toString());
        assertTrue(r.test());
        assertTrue(receiver.results.get(0).ok());
        assertEquals(TestAction.TEST_CALLBACK, receiver.bodies.get(0));
    }

    // ---- Rotating the secret ----

    @Test
    void duringASecretChangeTheReceiverAcceptsBoth() {
        receiver.secrets.add("whsec-test-2"); // step 1: the receiver takes the old and the new one
        callbacks.enqueue(integration.id(), BODY);
        callbacks.deliverDue(10);
        secrets.put(SECRET, "whsec-test-2"); // step 2: the sender signs with the new one
        callbacks.enqueue(integration.id(), BODY);
        callbacks.deliverDue(10);
        receiver.secrets.remove("whsec-test-1"); // step 3: the receiver drops the old one
        callbacks.enqueue(integration.id(), BODY);
        callbacks.deliverDue(10);
        assertEquals(3, receiver.results.size());
        assertTrue(receiver.results.stream().allMatch(CallbackVerifier.Result::ok), receiver.results.toString());
    }

    // ---- The verifier's refusals ----

    private static CallbackVerifier.Result check(Map<String, String> headers, String body, List<String> secrets,
                                                 CallbackVerifier.SeenIds seen, Instant now) {
        return CallbackVerifier.verify(headers::get, body.getBytes(StandardCharsets.UTF_8), secrets, seen, now);
    }

    @Test
    void theVerifierRefusesWhatIsNotRight() {
        Instant at = Instant.ofEpochSecond(1700000000L);
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        java.util.function.Function<String, String> good = id -> CallbackSignature.header("whsec-test-1",
                at.getEpochSecond(), id, body);
        CallbackVerifier.SeenIds seen = CallbackVerifier.SeenIds.inMemory(100);
        List<String> one = List.of("whsec-test-1");

        Map<String, String> ok = Map.of(CallbackSignature.SIGNATURE_HEADER, good.apply("cb-1"),
                CallbackSignature.ID_HEADER, "cb-1");
        assertTrue(check(ok, BODY, one, seen, at.plusSeconds(10)).ok());
        // The same ID again.
        assertEquals(IntegrationCode.INT_CALLBACK_REPLAYED, check(ok, BODY, one, seen, at.plusSeconds(20)).failure());
        // Another body, another secret, a changed signature, none, malformed.
        assertEquals(IntegrationCode.INT_CALLBACK_SIGNATURE, check(Map.of(CallbackSignature.SIGNATURE_HEADER,
                good.apply("cb-2"), CallbackSignature.ID_HEADER, "cb-2"), BODY.replace("DONE", "FAILED"), one, seen, at)
                .failure());
        assertEquals(IntegrationCode.INT_CALLBACK_SIGNATURE, check(Map.of(CallbackSignature.SIGNATURE_HEADER,
                good.apply("cb-3"), CallbackSignature.ID_HEADER, "cb-3"), BODY, List.of("other"), seen, at).failure());
        String four = good.apply("cb-4");
        assertEquals(IntegrationCode.INT_CALLBACK_SIGNATURE, check(Map.of(CallbackSignature.SIGNATURE_HEADER,
                four.substring(0, four.length() - 1) + "0", CallbackSignature.ID_HEADER, "cb-4"), BODY, one, seen, at)
                .failure());
        assertEquals(IntegrationCode.INT_CALLBACK_SIGNATURE, check(Map.of(CallbackSignature.ID_HEADER, "cb-5"), BODY,
                one, seen, at).failure());
        assertEquals(IntegrationCode.INT_CALLBACK_SIGNATURE, check(Map.of(CallbackSignature.SIGNATURE_HEADER,
                "v2=abc", CallbackSignature.ID_HEADER, "cb-6"), BODY, one, seen, at).failure());
        assertEquals(IntegrationCode.INT_CALLBACK_SIGNATURE, check(Map.of(CallbackSignature.SIGNATURE_HEADER,
                good.apply("cb-6")), BODY, one, seen, at).failure(), "no callback ID");
        // Too old and too far ahead: outside 5 minutes either way.
        Map<String, String> late = Map.of(CallbackSignature.SIGNATURE_HEADER, good.apply("cb-7"),
                CallbackSignature.ID_HEADER, "cb-7");
        assertEquals(IntegrationCode.INT_CALLBACK_EXPIRED, check(late, BODY, one, seen, at.plusSeconds(301))
                .failure());
        assertEquals(IntegrationCode.INT_CALLBACK_EXPIRED, check(late, BODY, one, seen, at.minusSeconds(301))
                .failure());
        // A refused callback is not recorded as seen: the genuine one still goes through.
        assertTrue(check(late, BODY, one, seen, at.plusSeconds(299)).ok());
        // During a change: either secret.
        String withNew = CallbackSignature.header("whsec-test-2", at.getEpochSecond(), "cb-8", body);
        assertTrue(check(Map.of(CallbackSignature.SIGNATURE_HEADER, withNew, CallbackSignature.ID_HEADER, "cb-8"),
                BODY, List.of("whsec-test-1", "whsec-test-2"), seen, at).ok());
    }

    // ---- Configuration ----

    @Test
    void theConfiguration() {
        Installation hosted = Installation.hostedService();
        IntegrationConfig c = IntegrationConfig.callback(Fx.ENTITY, Fx.PRODUCT, "Hooks",
                Endpoint.of("https://hooks.example/cases"), new SignIn.None(), SECRET);
        assertTrue(ConfigCheck.check(c, hosted).ok(), "NONE is fine for a callback: the signature proves the sender");
        assertEquals(c, ConfigJson.read(ConfigJson.write(c)));
        assertTrue(ConfigJson.fields(c).contains("callback.signingSecret"));
        assertTrue(ConfigCheck.check(c.withCallback(null), hosted).errors().stream()
                .anyMatch(p -> p.code() == IntegrationCode.INT_CONFIG_CALLBACK_INVALID));
        IntegrationConfig push = Fx.oneStep(URI.create("https://api.example"),
                new SignIn.BearerFixed(SecretRef.of("int/t"))).withCallback(c.callback());
        assertTrue(ConfigCheck.check(push, hosted).errors().stream()
                .anyMatch(p -> p.code() == IntegrationCode.INT_CONFIG_CALLBACK_INVALID));
        // The same safety rules: a metadata address is refused.
        IntegrationConfig metadata = c.withEndpoint(Endpoint.of("https://169.254.169.254/hooks"));
        assertTrue(ConfigCheck.check(metadata, hosted).errors().stream()
                .anyMatch(p -> p.code() == IntegrationCode.INT_CONFIG_ADDRESS_INVALID));
    }
}
