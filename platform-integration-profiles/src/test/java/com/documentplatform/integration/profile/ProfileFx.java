package com.documentplatform.integration.profile;

import com.documentplatform.fields.FieldSet;
import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.FieldType;
import com.documentplatform.fields.FieldValue;
import com.documentplatform.fields.ValidatedFields;
import com.documentplatform.integration.config.Endpoint;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.engine.CallEngine;
import com.documentplatform.integration.engine.CaseFields;
import com.documentplatform.integration.engine.Delivery;
import com.documentplatform.integration.http.Outbound;
import com.documentplatform.integration.safety.Installation;
import com.documentplatform.integration.store.StoredIntegration;
import com.documentplatform.notifications.inmemory.InMemorySecretResolver;
import com.documentplatform.notifications.secret.SecretRef;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The sdv-ingestion-v1 profile completed against the spec-mode mock, with a small case field list. */
final class ProfileFx implements AutoCloseable {

    static final UUID ENTITY = UUID.fromString("0d6f3b8e-7c41-4d2a-9b5e-3f1a2c4d5e6f");
    static final String PRODUCT = "DEMO";

    /** The entity's case fields: two plain ones and a sensitive name. */
    static final FieldSet FIELDS = FieldSet.of(List.of(
            FieldSpec.builder("contract_number", "Contract number", FieldType.TEXT).build(),
            FieldSpec.builder("loan_amount", "Loan amount", FieldType.MONEY).currency("USD").build(),
            FieldSpec.builder("member_name", "Member name", FieldType.TEXT).sensitive(true).build()));

    static final Map<String, String> MAPPING = mapping();

    private static Map<String, String> mapping() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("contractNumber", "contract_number");
        m.put("loanAmount", "loan_amount");
        return m;
    }

    final IngestionApiMock api;
    final InMemorySecretResolver secrets = new InMemorySecretResolver();
    final Installation installation = Installation.onPremises().withInternalAddresses(true);
    final CallEngine engine;
    final Profile profile = Profiles.load(IngestionApiMock.PROFILE);

    ProfileFx(boolean claims) {
        api = new IngestionApiMock(claims);
        secrets.put(SecretRef.of("int/sender"), IngestionApiMock.CLIENT_SECRET);
        engine = new CallEngine(new Outbound(installation), secrets, Clock.systemUTC());
    }

    SignIn signIn() {
        return SignIn.OAuth2ClientCredentials.secret(api.mock.tokenAddress(), IngestionApiMock.CLIENT,
                SecretRef.of("int/sender"), "ingest");
    }

    IntegrationConfig config(String senderIdentity, Map<String, String> mapping, Set<String> allowedSensitive) {
        return profile.complete(new Profile.Completion(ENTITY, PRODUCT, "Archive", new Endpoint(api.mock.address(), true),
                signIn(), mapping, allowedSensitive, Map.of("senderIdentity", senderIdentity)));
    }

    IntegrationConfig config(String senderIdentity) {
        return config(senderIdentity, MAPPING, Set.of());
    }

    static StoredIntegration stored(IntegrationConfig c) {
        return new StoredIntegration(c, 1, "test", Instant.now());
    }

    static CaseFields caseFields(boolean withName) {
        Map<String, List<FieldValue>> in = new LinkedHashMap<>();
        in.put("contract_number", List.of(new FieldValue.Text("990023450001")));
        in.put("loan_amount", List.of(new FieldValue.Text("$250,000")));
        if (withName) in.put("member_name", List.of(new FieldValue.Text("Example Member")));
        ValidatedFields v = FIELDS.validate(in);
        if (!v.accepted()) throw new IllegalStateException(v.problems().toString());
        return CaseFields.of(FIELDS, v);
    }

    /** {@code n} PDF-looking documents, with the case fields. */
    Delivery delivery(int n) {
        List<Delivery.Document> docs = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            docs.add(Delivery.Document.of("doc-" + i, "file-" + i + ".pdf", "application/pdf",
                    ("%PDF-1.4 test document " + i).getBytes(StandardCharsets.UTF_8)));
        }
        return profile.withDocumentDefaults(Delivery.of("pkg-" + UUID.randomUUID(), docs, Map.of())
                .withFields(caseFields(false)));
    }

    @Override
    public void close() {
        engine.close();
        api.mock.close();
    }
}
