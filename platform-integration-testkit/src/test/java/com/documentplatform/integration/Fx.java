package com.documentplatform.integration;

import com.documentplatform.integration.config.CallStep;
import com.documentplatform.integration.config.Endpoint;
import com.documentplatform.integration.config.HealthCheck;
import com.documentplatform.integration.config.HttpMethod;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.Limits;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.engine.CallEngine;
import com.documentplatform.integration.engine.Delivery;
import com.documentplatform.integration.http.Outbound;
import com.documentplatform.integration.safety.Installation;
import com.documentplatform.integration.store.StoredIntegration;
import com.documentplatform.integration.testkit.MockDestination;
import com.documentplatform.notifications.inmemory.InMemorySecretResolver;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shared set-up: a mock destination, secrets in memory, an on-premises installation that may call 127.0.0.1. */
final class Fx implements AutoCloseable {

    static final UUID ENTITY = UUID.fromString("5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f");
    static final String PRODUCT = "DEMO";

    final MockDestination mock;
    final InMemorySecretResolver secrets = new InMemorySecretResolver();
    final Installation installation;
    final CallEngine engine;

    Fx() {
        this(MockDestination.start(), Installation.onPremises().withInternalAddresses(true));
    }

    Fx(MockDestination mock, Installation installation) {
        this.mock = mock;
        this.installation = installation;
        this.engine = new CallEngine(new Outbound(installation), secrets, Clock.systemUTC());
    }

    /** create (JSON, reads the submission ID) → upload each document (file body). */
    static IntegrationConfig twoStep(URI base, SignIn signIn) {
        CallStep create = CallStep.of("create", HttpMethod.POST, "/api/submissions")
                .withJsonBody("{\"packageId\":\"{package.id}\",\"count\":{package.documentCount},"
                        + "\"metadata\":{\"loan\":\"{value.loanId}\"}}")
                .reading("submissionId", "/id", true);
        CallStep upload = CallStep.of("upload", HttpMethod.POST,
                        "/api/submissions/{step.create.submissionId}/documents")
                .withFileBody().eachDocument()
                .withHeader("X-File-Name", "{doc.fileName}")
                .withHeader("Idempotency-Key", "{package.id}:{doc.sourceDocumentId}")
                .withHeader("X-Content-SHA256", "{doc.sha256}")
                .reading("documentId", "/id", true)
                .reading("sha256", "/sha256", false);
        return IntegrationConfig.apiPush(ENTITY, PRODUCT, "Test destination", new Endpoint(base, true), signIn)
                .withSteps(List.of(create, upload))
                .withErrorCodePointer("/code")
                .withHealth(HealthCheck.get(MockDestination.HEALTH_PATH, false))
                .withLimits(Limits.DEFAULT.withReadTimeout(Duration.ofSeconds(5)));
    }

    /** One POST with a small JSON body. */
    static IntegrationConfig oneStep(URI base, SignIn signIn) {
        return IntegrationConfig.apiPush(ENTITY, PRODUCT, "One step", new Endpoint(base, true), signIn)
                .withSteps(List.of(CallStep.of("send", HttpMethod.POST, "/api/notice")
                        .withJsonBody("{\"packageId\":\"{package.id}\"}")))
                .withErrorCodePointer("/code")
                .withHealth(HealthCheck.get(MockDestination.HEALTH_PATH, false))
                .withLimits(Limits.DEFAULT.withReadTimeout(Duration.ofSeconds(5)));
    }

    static StoredIntegration stored(IntegrationConfig c) {
        return new StoredIntegration(c, 1, "test", Instant.now());
    }

    /** {@code n} documents whose text is {@code text + index}. */
    static Delivery delivery(int n, String text) {
        List<Delivery.Document> docs = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            docs.add(Delivery.Document.of("doc-" + i, "file-" + i + ".pdf", "application/pdf",
                    (text + i).getBytes(StandardCharsets.UTF_8)));
        }
        return Delivery.of("pkg-" + UUID.randomUUID(), docs, Map.of("loanId", "L-100"));
    }

    static Delivery delivery(int n) {
        return delivery(n, "%PDF-1.4 document text ");
    }

    @Override
    public void close() {
        engine.close();
        mock.close();
    }
}
