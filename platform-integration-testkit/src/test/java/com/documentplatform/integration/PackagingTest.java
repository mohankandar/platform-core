package com.documentplatform.integration;

import com.documentplatform.integration.config.CallStep;
import com.documentplatform.integration.config.Endpoint;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.Packaging;
import com.documentplatform.integration.config.Rule;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.engine.Delivery;
import com.documentplatform.integration.result.AttemptResult;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.integration.testkit.MockDestination;
import com.documentplatform.notifications.secret.SecretRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The packaging presets, multipart, document attributes, the generic integrity check and per-step rules, on the wire. */
class PackagingTest {

    private Fx fx;

    @BeforeEach
    void setUp() {
        fx = new Fx();
        fx.secrets.put(SecretRef.of("int/t"), "tok-1");
        fx.mock.expectBearer("tok-1");
    }

    @AfterEach
    void close() {
        fx.close();
    }

    private IntegrationConfig config(List<CallStep> steps) {
        return IntegrationConfig.apiPush(Fx.ENTITY, Fx.PRODUCT, "x", new Endpoint(fx.mock.address(), true),
                new SignIn.BearerFixed(SecretRef.of("int/t"))).withSteps(steps).withErrorCodePointer("/code");
    }

    @Test
    void multipartSendsTheJsonPartAndEveryDocumentInOneCall() {
        fx.mock.recordValues(true);
        Delivery d = Fx.delivery(2);
        AttemptResult r = fx.engine.attempt(Fx.stored(config(Packaging.multipart("/api/packages",
                "{\"packageId\":\"{package.id}\",\"files\":[{#each doc}\"{doc.fileName}\"{/each}]}", "metadata",
                "documents"))), d);
        assertEquals(Outcome.SENT, r.outcome(), r.toString());
        MockDestination.Received call = fx.mock.calls().get(0);
        assertTrue(call.contentType().startsWith("multipart/form-data; boundary="));
        List<MockDestination.Part> parts = call.parts();
        assertEquals(3, parts.size());
        assertEquals("metadata", parts.get(0).name());
        assertEquals("application/json", parts.get(0).contentType());
        assertEquals("documents", parts.get(1).name());
        assertEquals("file-1.pdf", parts.get(1).fileName());
        assertEquals(d.documents().get(0).sha256(), parts.get(1).sha256(), "the file part is the document, as it is");
        assertEquals(d.documents().get(1).size(), parts.get(2).size());
        assertEquals(1, fx.mock.calls().size(), "one call");
    }

    @Test
    void filePerCallAndTwoStepWithADocumentAttribute() {
        Delivery d = Fx.delivery(2);
        Delivery withLevel = new Delivery(d.packageId(), null, List.of(d.documents().get(0).withAttribute("level", "HIGH"),
                d.documents().get(1).withAttribute("level", "LOW")), d.values(), null);
        AttemptResult per = fx.engine.attempt(Fx.stored(config(Packaging.filePerCall("/api/files",
                Map.of("X-Name", "{doc.fileName}", "X-Level", "{doc.attr.level}")))), withLevel);
        assertEquals(Outcome.SENT, per.outcome(), per.toString());
        assertEquals(List.of("HIGH", "LOW"), fx.mock.calls().stream().map(c -> c.header("x-level").orElseThrow()).toList());
        AttemptResult missing = fx.engine.attempt(Fx.stored(config(Packaging.filePerCall("/api/files",
                Map.of("X-Level", "{doc.attr.level}")))), d);
        assertEquals(IntegrationCode.INT_VALUE_MISSING, missing.code());

        fx.mock.clearRecords();
        AttemptResult two = fx.engine.attempt(Fx.stored(config(Packaging.twoStep("/api/subs",
                "{\"id\":\"{package.id}\",\"docs\":[{#each doc}\"{doc.sourceDocumentId}\"{/each}]}", "subId", "/id",
                "/api/subs/{step.create.subId}/files", Map.of("X-Name", "{doc.fileName}")))), d);
        assertEquals(Outcome.SENT, two.outcome());
        assertEquals(3, fx.mock.calls().size());
    }

    @Test
    void theIntegrityCheckComparesWhatTheDestinationSays() {
        List<CallStep> steps = List.of(CallStep.of("upload", com.documentplatform.integration.config.HttpMethod.POST,
                "/api/files").eachDocument().withFileBody().reading("sha", "/sha256", true).reading("size", "/size", true)
                .withIntegrity("sha", "size"));
        // The mock's default answer names the SHA-256 and size it received.
        assertEquals(Outcome.SENT, fx.engine.attempt(Fx.stored(config(steps)), Fx.delivery(2)).outcome());
        fx.mock.route("POST", "/api/files", 201, "{\"sha256\":\"${sha256}\",\"size\":1}");
        AttemptResult size = fx.engine.attempt(Fx.stored(config(steps)), Fx.delivery(2));
        assertEquals(Outcome.UNCLEAR, size.outcome());
        assertEquals(IntegrationCode.INT_INTEGRITY_MISMATCH, size.code());
        assertEquals("doc-1", size.documentReached());
        assertTrue(size.attemptUsed());
    }

    @Test
    void perStepRulesComeBeforeTheIntegrationsRules() {
        CallStep step = CallStep.of("send", com.documentplatform.integration.config.HttpMethod.POST, "/api/notice")
                .withJsonBody("{\"id\":\"{package.id}\"}").withRules(List.of(Rule.of("409", "MOCK-DUP",
                        Outcome.ALREADY_RECEIVED)));
        IntegrationConfig c = config(List.of(step)).withRules(List.of(Rule.of("409", null, Outcome.REFUSED)));
        fx.mock.refuse(409, "MOCK-DUP");
        assertEquals(Outcome.ALREADY_RECEIVED, fx.engine.attempt(Fx.stored(c), Fx.delivery(1)).outcome());
        fx.mock.refuse(409, "MOCK-OTHER");
        assertEquals(Outcome.REFUSED, fx.engine.attempt(Fx.stored(c), Fx.delivery(1)).outcome());
    }
}
