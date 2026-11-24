package com.documentplatform.integration;

import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.Limits;
import com.documentplatform.integration.config.Rule;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.engine.Delivery;
import com.documentplatform.integration.engine.TestAction;
import com.documentplatform.integration.result.AttemptResult;
import com.documentplatform.integration.result.HealthResult;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.integration.store.StoredIntegration;
import com.documentplatform.integration.testkit.MockDestination;
import com.documentplatform.notifications.secret.SecretRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Every switch of the mock destination gives the right result class. */
class MockSwitchesTest {

    private Fx fx;
    private StoredIntegration integration;

    @BeforeEach
    void setUp() {
        fx = new Fx();
        fx.secrets.put(SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/bearer"), "tok-1");
        fx.mock.expectBearer("tok-1");
        integration = Fx.stored(Fx.twoStep(fx.mock.address(), new SignIn.BearerFixed(SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/bearer"))));
    }

    @AfterEach
    void close() {
        fx.close();
    }

    private AttemptResult send() {
        return fx.engine.attempt(integration, Fx.delivery(2));
    }

    @Test
    void working() {
        AttemptResult r = send();
        assertEquals(Outcome.SENT, r.outcome());
        assertEquals(IntegrationCode.INT_SENT, r.code());
        assertTrue(r.attemptUsed());
        assertEquals("upload", r.stepReached());
        assertEquals(3, r.steps().size());
        assertTrue(r.values().containsKey("create.submissionId"));
        assertEquals(java.util.Set.of("doc-1", "doc-2"), r.documentsDone());
        assertNotNull(r.correlationId());
    }

    @Test
    void downIsCantConnect() {
        fx.mock.down();
        AttemptResult r = send();
        assertEquals(Outcome.CANT_CONNECT, r.outcome());
        assertEquals(IntegrationCode.INT_CANT_CONNECT, r.code());
        assertEquals(0, r.httpStatus());
        fx.mock.up();
        assertEquals(Outcome.SENT, send().outcome());
    }

    @Test
    void slowIsATimeout() {
        integration = Fx.stored(integration.config().withLimits(Limits.DEFAULT.withReadTimeout(Duration.ofSeconds(1))));
        fx.mock.slow(Duration.ofMillis(2500));
        AttemptResult r = send();
        assertEquals(Outcome.CANT_CONNECT, r.outcome());
        assertEquals(IntegrationCode.INT_TIMEOUT, r.code());
        fx.mock.slow(Duration.ofMillis(100));
        assertEquals(Outcome.SENT, send().outcome(), "slow but within the timeout");
    }

    @Test
    void busyWithRetryAfterUsesNoAttempt() {
        fx.mock.busy(429, null, Duration.ofSeconds(7));
        AttemptResult r = send();
        assertEquals(Outcome.BUSY, r.outcome());
        assertEquals(IntegrationCode.INT_BUSY, r.code());
        assertEquals(Duration.ofSeconds(7), r.retryAfter());
        assertFalse(r.attemptUsed());
        assertEquals("create", r.stepReached());
    }

    @Test
    void busyWithoutRetryAfter() {
        fx.mock.busy(429, "MOCK-BUSY", null);
        AttemptResult r = send();
        assertEquals(Outcome.BUSY, r.outcome());
        assertNull(r.retryAfter());
        assertEquals("MOCK-BUSY", r.destinationCode());
    }

    @Test
    void outageIs503WithoutACode() {
        fx.mock.outage();
        AttemptResult r = send();
        assertEquals(Outcome.OUTAGE, r.outcome());
        assertEquals(IntegrationCode.INT_OUTAGE, r.code());
        assertNull(r.destinationCode());
        assertEquals(503, r.httpStatus());
        assertTrue(r.attemptUsed());
    }

    @Test
    void refusesWithACode() {
        fx.mock.refuse(409, "MOCK-INT-011");
        AttemptResult r = send();
        assertEquals(Outcome.REFUSED, r.outcome());
        assertEquals(IntegrationCode.INT_REFUSED, r.code());
        assertEquals("MOCK-INT-011", r.destinationCode());
        assertEquals(409, r.httpStatus());
    }

    @Test
    void alreadyReceivedThroughARule() {
        fx.mock.alreadyReceived("{\"id\":\"sub-1\",\"receipt\":\"DUPLICATE\"}");
        // Without a rule a 200 is just "sent".
        assertEquals(Outcome.SENT, send().outcome());
        integration = Fx.stored(integration.config().withRules(List.of(
                Rule.of("200", null, Outcome.ALREADY_RECEIVED).whenField("/receipt", "DUPLICATE"))));
        AttemptResult r = send();
        assertEquals(Outcome.ALREADY_RECEIVED, r.outcome());
        assertEquals(IntegrationCode.INT_ALREADY_RECEIVED, r.code());
        assertTrue(r.delivered());
        assertEquals("sub-1", r.values().get("create.submissionId"));
    }

    @Test
    void signInRefused() {
        fx.mock.signInRefused("MOCK-AUT-001");
        AttemptResult r = send();
        assertEquals(Outcome.SIGN_IN_FAILED, r.outcome());
        assertEquals("MOCK-AUT-001", r.destinationCode());
    }

    @Test
    void healthFailingWhileUploadsWork() {
        fx.mock.healthFailing(true);
        HealthResult h = fx.engine.health(integration);
        assertFalse(h.healthy());
        assertEquals(Outcome.OUTAGE, h.outcome());
        assertEquals(503, h.httpStatus());
        assertEquals(Outcome.SENT, send().outcome());
    }

    @Test
    void healthWorkingWhileUploadsFail() {
        fx.mock.outage();
        HealthResult h = fx.engine.health(integration);
        assertTrue(h.healthy());
        assertEquals(IntegrationCode.INT_HEALTHY, h.code());
        assertEquals(Outcome.OUTAGE, send().outcome());
    }

    @Test
    void healthWithItsOwnRules() {
        fx.mock.healthFailing(true);
        integration = Fx.stored(integration.config().withHealth(integration.config().health()
                .withRules(List.of(Rule.of("503", null, Outcome.BUSY)))));
        assertEquals(Outcome.BUSY, fx.engine.health(integration).outcome());
    }

    @Test
    void switchedByTheAdminCall() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        URI admin = fx.mock.adminAddress();
        post(http, admin.resolve("/mode?name=BUSY&status=503&code=MOCK-SYS-014&retryAfter=3"));
        AttemptResult busy = send();
        assertEquals(Outcome.OUTAGE, busy.outcome(), "no rule says this code means busy");
        assertEquals("MOCK-SYS-014", busy.destinationCode());
        assertEquals(Duration.ofSeconds(3), busy.retryAfter());

        post(http, admin.resolve("/mode?name=REFUSE&status=422&code=MOCK-SEN-006"));
        assertEquals(Outcome.REFUSED, send().outcome());
        post(http, admin.resolve("/health?failing=true"));
        assertFalse(fx.engine.health(integration).healthy());
        post(http, admin.resolve("/mode?name=DOWN"));
        assertEquals(Outcome.CANT_CONNECT, send().outcome());
        post(http, admin.resolve("/mode?name=UP"));
        post(http, admin.resolve("/reset"));
        fx.mock.expectBearer("tok-1");
        assertEquals(Outcome.SENT, send().outcome());
        HttpResponse<String> rec = http.send(HttpRequest.newBuilder(admin.resolve("/received")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, rec.statusCode());
        assertTrue(rec.body().contains("\"sha256\""));
    }

    private static void post(HttpClient http, URI uri) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
    }

    @Test
    void recordsHeadersFieldsAndChecksumsNeverDocumentText() {
        fx.mock.recordValues(true);
        Delivery d = Fx.delivery(1, "SECRET-DOCUMENT-TEXT-");
        AttemptResult r = fx.engine.attempt(integration, d);
        assertEquals(Outcome.SENT, r.outcome());
        MockDestination.Received create = fx.mock.calls().get(0);
        assertEquals(d.packageId(), create.fields().get("packageId"));
        assertEquals(1L, create.fields().get("count"));
        MockDestination.Received upload = fx.mock.calls().get(1);
        Delivery.Document doc = d.documents().get(0);
        assertEquals(doc.sha256(), upload.sha256());
        assertEquals(doc.size(), upload.size());
        assertEquals("application/pdf", upload.contentType());
        assertEquals("file-1.pdf", upload.header("x-file-name").orElseThrow());
        assertEquals(doc.sha256(), upload.header("x-content-sha256").orElseThrow());
        assertEquals(d.packageId() + ":doc-1", upload.header("idempotency-key").orElseThrow());
        assertEquals("[present]", upload.header("authorization").orElseThrow());
        assertTrue(upload.fields().isEmpty());
        assertFalse(fx.mock.received().toString().contains("SECRET-DOCUMENT-TEXT"));
        assertFalse(fx.mock.received().toString().contains("tok-1"));
        // The checksum the destination saw comes back as a value read from the answer.
        assertEquals(doc.sha256(), r.documentValues().get("doc-1").get("upload.sha256"));
    }

    @Test
    void theTestActionSendsOnlyFakeData() {
        IntegrationConfig c = integration.config();
        AttemptResult r = new TestAction(fx.engine, com.documentplatform.integration.store.IntegrationAudit.NONE)
                .run(Fx.stored(c), "it-admin");
        assertTrue(r.test());
        assertEquals(Outcome.SENT, r.outcome());
        assertEquals(TestAction.FAKE_PDF_SHA256, fx.mock.calls().get(1).sha256());
    }
}
