package com.documentplatform.integration;

import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.Limits;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.engine.Delivery;
import com.documentplatform.integration.result.AttemptResult;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.integration.store.StoredIntegration;
import com.documentplatform.notifications.secret.SecretRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Steps, step values, response mapping, resume, limits. */
class CallEngineTest {

    private Fx fx;
    private StoredIntegration integration;

    @BeforeEach
    void setUp() {
        fx = new Fx();
        fx.secrets.put(SecretRef.of("int/bearer"), "tok-1");
        fx.mock.expectBearer("tok-1");
        integration = Fx.stored(Fx.twoStep(fx.mock.address(), new SignIn.BearerFixed(SecretRef.of("int/bearer"))));
    }

    @AfterEach
    void close() {
        fx.close();
    }

    @Test
    void stepValuesGoIntoLaterPathsEscaped() {
        fx.mock.route("POST", "/api/submissions", 201, "{\"id\":\"sub/1 ä\"}");
        AttemptResult r = fx.engine.attempt(integration, Fx.delivery(1));
        assertEquals(Outcome.SENT, r.outcome(), r.toString());
        assertEquals("/api/submissions/sub%2F1%20%C3%A4/documents", fx.mock.calls().get(1).path());
    }

    @Test
    void aMissingRequiredValueIsUnclear() {
        fx.mock.route("POST", "/api/submissions", 201, "{\"other\":1}");
        AttemptResult r = fx.engine.attempt(integration, Fx.delivery(1));
        assertEquals(Outcome.UNCLEAR, r.outcome());
        assertEquals(IntegrationCode.INT_UNCLEAR_ANSWER, r.code());
        assertEquals("create", r.stepReached());
        fx.mock.clearRoutes().route("POST", "/api/submissions", 201, "not json");
        assertEquals(Outcome.UNCLEAR, fx.engine.attempt(integration, Fx.delivery(1)).outcome());
    }

    @Test
    void resumesFromTheStepReached() {
        fx.mock.route("POST", "/api/submissions", 201, "{\"id\":\"sub-7\"}")
                .route("POST", "/api/submissions/sub-7/documents", 201, "{\"id\":\"doc-${n}\"}");
        Delivery d = Fx.delivery(3);
        // An earlier attempt created the submission and sent doc-1, then the destination was busy.
        AttemptResult first = new AttemptResult(integration.id(), 1, Outcome.BUSY, IntegrationCode.INT_BUSY, null,
                503, null, false, null, "upload", "doc-2", Map.of("create.submissionId", "sub-7"), Map.of(),
                Set.of("create"), Set.of("doc-1"), List.of(), false);
        AttemptResult resumed = fx.engine.attempt(integration, d.resumingFrom(Delivery.Resume.from(first)));
        assertEquals(Outcome.SENT, resumed.outcome());
        assertEquals(2, fx.mock.calls().size(), "create and doc-1 are not sent again");
        assertTrue(fx.mock.calls().stream().allMatch(c -> c.path().equals("/api/submissions/sub-7/documents")));
        assertEquals(Set.of("doc-1", "doc-2", "doc-3"), resumed.documentsDone());
    }

    @Test
    void aFailureMidwayReportsWhereItStopped() {
        fx.mock.route("POST", "/api/submissions", 201, "{\"id\":\"sub-9\"}");
        fx.mock.route("POST", "/api/submissions/sub-9/documents", 409, "{\"code\":\"MOCK-INT-017\"}");
        AttemptResult r = fx.engine.attempt(integration, Fx.delivery(2));
        assertEquals(Outcome.REFUSED, r.outcome());
        assertEquals("upload", r.stepReached());
        assertEquals("doc-1", r.documentReached());
        assertEquals("MOCK-INT-017", r.destinationCode());
        assertEquals(Set.of("create"), r.stepsDone());
        assertEquals("sub-9", r.values().get("create.submissionId"));
        assertTrue(r.documentsDone().isEmpty());
    }

    @Test
    void limitsAreCheckedBeforeSending() {
        integration = Fx.stored(integration.config().withLimits(Limits.DEFAULT.withMaxDocuments(2)));
        assertEquals(IntegrationCode.INT_TOO_MANY_DOCUMENTS, fx.engine.attempt(integration, Fx.delivery(3)).code());
        integration = Fx.stored(integration.config().withLimits(Limits.DEFAULT.withMaxFileBytes(10)));
        AttemptResult big = fx.engine.attempt(integration, Fx.delivery(1));
        assertEquals(Outcome.REFUSED, big.outcome());
        assertEquals(IntegrationCode.INT_FILE_TOO_LARGE, big.code());
        assertTrue(fx.mock.received().isEmpty());
    }

    @Test
    void aTooLargeAnswerIsNotRead() {
        integration = Fx.stored(integration.config().withLimits(Limits.DEFAULT.withMaxResponseBytes(1024)));
        fx.mock.route("POST", "/api/submissions", 201, "{\"id\":\"" + "x".repeat(2000) + "\"}");
        AttemptResult r = fx.engine.attempt(integration, Fx.delivery(1));
        assertEquals(Outcome.UNCLEAR, r.outcome());
        assertEquals(IntegrationCode.INT_RESPONSE_TOO_LARGE, r.code());
    }

    @Test
    void offOrMissingValuesSendNothing() {
        AttemptResult off = fx.engine.attempt(Fx.stored(integration.config().withEnabled(false)), Fx.delivery(1));
        assertEquals(Outcome.NOT_CONFIGURED, off.outcome());
        assertEquals(IntegrationCode.INT_DISABLED, off.code());
        AttemptResult noValue = fx.engine.attempt(integration, Delivery.of("pkg-1", Fx.delivery(1).documents(),
                Map.of()));
        assertEquals(Outcome.NOT_CONFIGURED, noValue.outcome());
        assertEquals(IntegrationCode.INT_VALUE_MISSING, noValue.code());
        assertTrue(fx.mock.received().isEmpty());
    }

    @Test
    void aHeaderValueWithALineBreakIsRefused() {
        IntegrationConfig c = integration.config();
        Delivery bad = Delivery.of("pkg-1", List.of(new Delivery.Document("doc-1", "a.pdf", "application/pdf", 3,
                "a".repeat(64), () -> new java.io.ByteArrayInputStream(new byte[3]))), Map.of("loanId", "L-1"));
        // The file name cannot hold a line break (refused by Document); a value can, and only JSON escapes it.
        assertThrows(IllegalArgumentException.class, () -> new Delivery.Document("d", "a\nb.pdf", "application/pdf",
                1, "a".repeat(64), () -> null));
        assertEquals(Outcome.SENT, fx.engine.attempt(Fx.stored(c), Delivery.of("pkg-2", bad.documents(),
                Map.of("loanId", "line\nbreak \"quoted\""))).outcome());
        assertEquals("line\nbreak \"quoted\"",
                ((Map<?, ?>) fx.mock.calls().get(0).fields().get("metadata")).get("loan"));
    }

    @Test
    void unreadableContentIsNotSent() {
        Delivery d = Delivery.of("pkg-1", List.of(new Delivery.Document("doc-1", "a.pdf", "application/pdf", 3,
                "a".repeat(64), () -> {
                    throw new IOException("disk");
                })), Map.of("loanId", "L-1"));
        AttemptResult r = fx.engine.attempt(integration, d);
        assertEquals(Outcome.NOT_CONFIGURED, r.outcome());
        assertEquals(IntegrationCode.INT_CONTENT_UNREADABLE, r.code());
    }

    @Test
    void correlationIdIsSentOnEveryCallWhenConfigured() {
        Delivery d = new Delivery("pkg-1", "corr-123", Fx.delivery(1).documents(), Map.of("loanId", "L"), null);
        AttemptResult r = fx.engine.attempt(integration, d);
        assertEquals("corr-123", r.correlationId());
        assertTrue(fx.mock.calls().stream().allMatch(c -> "corr-123".equals(c.header("x-correlation-id").orElse(null))));
        fx.mock.clearRecords();
        AttemptResult without = fx.engine.attempt(Fx.stored(integration.config().withSendCorrelationId(false)), d);
        assertNull(without.correlationId());
        assertTrue(fx.mock.calls().stream().noneMatch(c -> c.header("x-correlation-id").isPresent()));
    }
}
