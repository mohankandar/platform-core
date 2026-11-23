package com.documentplatform.integration;

import com.documentplatform.fields.FieldSet;
import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.FieldType;
import com.documentplatform.fields.FieldValue;
import com.documentplatform.integration.config.Endpoint;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.engine.CallEngine;
import com.documentplatform.integration.engine.CaseFields;
import com.documentplatform.integration.engine.Delivery;
import com.documentplatform.outbound.Outbound;
import com.documentplatform.integration.profile.IntegrationProfile;
import com.documentplatform.integration.result.AttemptResult;
import com.documentplatform.integration.result.HealthResult;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.outbound.Installation;
import com.documentplatform.integration.store.IntegrationAudit;
import com.documentplatform.integration.store.IntegrationConfigs;
import com.documentplatform.integration.store.StoredIntegration;
import com.documentplatform.integration.testkit.InMemoryIntegrationStore;
import com.documentplatform.integration.testkit.MockDestination;
import com.documentplatform.integration.testkit.ProfileMock;
import com.documentplatform.integration.testkit.openapi.OpenApiSpec;
import com.documentplatform.notifications.inmemory.InMemorySecretResolver;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.documentplatform.integration.ProfileFiles.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Each made-up profile end to end against the mock it drives, with and without its OpenAPI file: a send, every switch
 * derived from its rules, health busy and down, and the integrity check.
 */
class ProfileMockTest {

    static final FieldSet FIELDS = FieldSet.of(List.of(
            FieldSpec.builder("case_number", "Case number", FieldType.TEXT).build(),
            FieldSpec.builder("amount", "Amount", FieldType.MONEY).currency("USD").build()));

    record Setup(IntegrationProfile profile, ProfileMock mock, CallEngine engine, StoredIntegration stored)
            implements AutoCloseable {
        AttemptResult send(int docs) {
            return engine.attempt(stored, delivery(docs));
        }

        Delivery delivery(int n) {
            List<Delivery.Document> docs = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                docs.add(Delivery.Document.of("doc-" + i, "file-" + i + ".pdf", "application/pdf",
                        ("%PDF-1.4 made-up document " + i).getBytes(StandardCharsets.UTF_8)));
            }
            Map<String, List<FieldValue>> in = new LinkedHashMap<>();
            in.put("case_number", List.of(new FieldValue.Text("C-100")));
            in.put("amount", List.of(new FieldValue.Text("$1,250")));
            Delivery d = Delivery.of("pkg-" + UUID.randomUUID(), docs, Map.of("batchLabel", "Batch 7"))
                    .withFields(CaseFields.of(FIELDS, FIELDS.validate(in)));
            return profile.prepare(d);
        }

        @Override
        public void close() {
            engine.close();
            mock.close();
        }
    }

    static Setup setup(String name, boolean withSpec) {
        IntegrationProfile p = profile(name);
        OpenApiSpec spec = withSpec ? spec(name) : null;
        ProfileMock mock = ProfileMock.start(p, spec);
        InMemorySecretResolver secrets = new InMemorySecretResolver().put(ProfileMock.SECRET_REF, ProfileMock.SECRET);
        Installation inst = Installation.onPremises().withInternalAddresses(true);
        CallEngine engine = new CallEngine(new Outbound(inst), secrets, Clock.systemUTC());
        Map<String, String> mapping = name.equals(ACME) ? Map.of("caseNumber", "case_number", "amount", "amount")
                : Map.of("caseNo", "case_number");
        IntegrationConfig c = p.complete(new IntegrationProfile.Completion(UUID.randomUUID(), "DEMO", "x",
                new Endpoint(mock.address(), true), mock.signIn(), mapping, Set.of(), Map.of("senderIdentity", "LOOKUP")
                .entrySet().stream().filter(e -> p.settings().containsKey(e.getKey()))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))));
        // The normal save checks, with the entity's field list.
        StoredIntegration stored = new IntegrationConfigs(new InMemoryIntegrationStore(), IntegrationAudit.NONE, inst,
                Clock.systemUTC(), (e, pr) -> Optional.of(FIELDS)).create(c, "it-admin");
        return new Setup(p, mock, engine, stored);
    }

    @ParameterizedTest
    @CsvSource({"acme-archive-v1,true", "acme-archive-v1,false", "example-dms-v2,true", "example-dms-v2,false"})
    void everySwitch(String name, boolean withSpec) {
        try (Setup s = setup(name, withSpec)) {
            s.mock().mock().recordValues(true);
            AttemptResult sent = s.send(2);
            assertEquals(Outcome.SENT, sent.outcome(), name + " " + sent + " " + s.mock().mock().specProblems());
            assertEquals(List.of(), s.mock().mock().specProblems(), "requests and answers fit the spec");
            if (name.equals(ACME)) {
                MockDestination.Received create = s.mock().mock().calls().get(0);
                assertEquals(Map.of("caseNumber", "C-100", "amount", 1250.0), create.fields().get("attributes"));
                assertEquals("INTERNAL", s.mock().mock().calls().get(1).header("x-acme-class").orElseThrow(),
                        "the attribute's default");
                assertEquals(3, s.mock().mock().calls().size());
            } else {
                assertEquals(1, s.mock().mock().calls().size(), "one multipart call");
                assertEquals(3, s.mock().mock().calls().get(0).parts().size());
            }

            s.mock().busy();
            AttemptResult busy = s.send(1);
            assertEquals(Outcome.BUSY, busy.outcome(), name);
            assertFalse(busy.attemptUsed());
            assertEquals(ProfileMock.RETRY_AFTER, busy.retryAfter());

            s.mock().down();
            assertEquals(Outcome.OUTAGE, s.send(1).outcome(), name);

            s.mock().refuse();
            AttemptResult refused = s.send(1);
            assertEquals(Outcome.REFUSED, refused.outcome(), name);
            assertNotNull(refused.destinationCode());

            s.mock().alreadyReceived();
            assertEquals(Outcome.ALREADY_RECEIVED, s.send(1).outcome(), name + " " + s.mock().mock().specProblems());

            s.mock().signInRefused();
            assertEquals(Outcome.SIGN_IN_FAILED, s.send(1).outcome(), name);

            s.mock().working().unreachable();
            assertEquals(Outcome.CANT_CONNECT, s.send(1).outcome(), name);
            s.mock().working();
            assertEquals(Outcome.SENT, s.send(1).outcome(), name);
        }
    }

    @ParameterizedTest
    @CsvSource({"acme-archive-v1,true", "acme-archive-v1,false", "example-dms-v2,true", "example-dms-v2,false"})
    void healthBusyAndDown(String name, boolean withSpec) {
        try (Setup s = setup(name, withSpec)) {
            assertTrue(s.engine().health(s.stored()).healthy(), name);
            s.mock().healthBusy();
            HealthResult busy = s.engine().health(s.stored());
            if (name.equals(ACME)) {
                assertTrue(busy.busy(), "the profile's health rule: 503 with its busy code");
                assertEquals(IntegrationCode.INT_DESTINATION_BUSY, busy.code());
                assertEquals(ProfileMock.RETRY_AFTER, busy.retryAfter());
            } else {
                assertTrue(busy.down(), "a profile without a health busy rule: a 503 is down");
            }
            s.mock().healthDown();
            assertTrue(s.engine().health(s.stored()).down(), name);
            s.mock().working().unreachable();
            assertEquals(Outcome.CANT_CONNECT, s.engine().health(s.stored()).outcome());
            s.mock().working();
        }
    }

    @ParameterizedTest
    @CsvSource({"true", "false"})
    void theIntegrityCheck(boolean withSpec) {
        try (Setup s = setup(ACME, withSpec)) {
            s.mock().wrongChecksum("0".repeat(64));
            AttemptResult r = s.send(2);
            assertEquals(Outcome.UNCLEAR, r.outcome());
            assertEquals(IntegrationCode.INT_INTEGRITY_MISMATCH, r.code());
            assertEquals("doc-1", r.documentReached());
        }
    }

    @org.junit.jupiter.api.Test
    void requiredValuesAndAttributesAreChecked() {
        try (Setup s = setup(ACME, false)) {
            Delivery d = s.delivery(1);
            Delivery noLabel = new Delivery(d.packageId(), null, d.documents(), Map.of(), null, d.fields());
            assertThrows(IllegalArgumentException.class, () -> s.profile().prepare(noLabel));
            Delivery badClass = new Delivery(d.packageId(), null, List.of(d.documents().get(0)
                    .withAttribute("classification", "TOP")), d.values(), null, d.fields());
            assertThrows(IllegalArgumentException.class, () -> s.profile().prepare(badClass));
            assertThrows(IllegalArgumentException.class, () -> s.profile().complete(new IntegrationProfile.Completion(
                    UUID.randomUUID(), "DEMO", "x", new Endpoint(s.mock().address(), true), s.mock().signIn(),
                    Map.of("amount", "amount"), Set.of(), Map.of())), "caseNumber is required");
            assertThrows(IllegalArgumentException.class, () -> s.profile().complete(new IntegrationProfile.Completion(
                    UUID.randomUUID(), "DEMO", "x", new Endpoint(s.mock().address(), true), s.mock().signIn(),
                    Map.of("caseNumber", "case_number", "other", "amount"), Set.of(), Map.of())),
                    "the profile takes no other fields");
            assertEquals(Duration.ofSeconds(5), ProfileMock.RETRY_AFTER);
        }
    }
}
