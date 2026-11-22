package com.documentplatform.integration.profile;

import com.documentplatform.integration.testkit.MockDestination;
import com.documentplatform.integration.testkit.openapi.OpenApiSpec;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The generic mock destination in spec mode for the pinned {@code sdv-ingestion-v1} specification: its refusal code
 * for a request the spec refuses ({@code SDV-SYS-015}), its error body ({@code code, message, reference, timestamp}),
 * and the answer values a real server would fill in (the checksum and size it received, the identities sent). Its
 * token endpoint adds the two sender claims in CLAIMS mode, and the mock then requires them ({@code SDV-AUT-005}).
 */
final class IngestionApiMock implements MockDestination.SpecBehaviour {

    static final String PROFILE = "sdv-ingestion-v1";
    static final String READINESS = "/actuator/health/readiness";
    static final String CLIENT = "sender-app";
    static final String CLIENT_SECRET = "client-secret-for-tests";
    static final String TENANT_CLAIM = "sdv_tenant_id";
    static final String SOURCE_CLAIM = "sdv_source_id";

    static OpenApiSpec spec() {
        return OpenApiSpec.parse(Profiles.resource(PROFILE, "sdv-ingestion-v1.openapi.json"));
    }

    final MockDestination mock;
    /** When set, the upload answer names this checksum instead of the one received (integrity test). */
    volatile String wrongSha256;
    /** When set, uploads of this document get {@link #failWith} for {@link #failures} times. */
    volatile String failDocument;
    volatile MockDestination.SpecError failWith;
    final AtomicInteger failures = new AtomicInteger();
    /** Replay answers say DUPLICATE_ALREADY_STORED (true) or an idempotent retry (false). */
    volatile boolean duplicate = true;

    IngestionApiMock(boolean claims) {
        mock = MockDestination.start().specMode(spec(), this).healthPath(READINESS).expectOAuth2()
                .registerClient(CLIENT, CLIENT_SECRET);
        if (claims) {
            mock.tokenClaims(Map.of(TENANT_CLAIM, UUID.randomUUID().toString(), SOURCE_CLAIM,
                    UUID.randomUUID().toString()));
            mock.requireTokenClaims("SDV-AUT-005", List.of(TENANT_CLAIM, SOURCE_CLAIM));
        }
    }

    @Override
    public String badRequestCode() {
        return "SDV-SYS-015";
    }

    @Override
    public Map<String, Object> error(String code, String reference) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (code != null) m.put("code", code);
        m.put("message", "mock answer");
        m.put("reference", reference);
        m.put("timestamp", Instant.now().toString());
        return m;
    }

    @Override
    public Map<String, Object> answer(MockDestination.SpecRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (r.operationId().equals("createSubmission") && r.json() instanceof Map<?, ?> body) {
            m.put("sourceSubmissionId", body.get("sourceSubmissionId"));
            m.put("expectedDocumentCount", body.get("expectedDocumentCount"));
            m.put("status", "OPEN");
            m.put("idempotentReplay", false);
        }
        if (r.operationId().equals("uploadDocument")) {
            m.put("sourceDocumentId", r.header("X-SDV-Source-Document-Id").orElse(null));
            m.put("contentSha256", wrongSha256 != null ? wrongSha256 : r.sha256());
            m.put("contentSizeBytes", r.size());
            m.put("receiptAcknowledgement", "RECEIVED");
            m.put("currentStatus", "RECEIVED");
            m.put("idempotentReplay", false);
            m.put("duplicate", false);
            m.put("exactDuplicateContent", false);
            m.put("sensitivityLevel", r.header("X-SDV-Sensitivity-Level").orElse("STANDARD"));
        }
        return m;
    }

    @Override
    public Map<String, Object> replay(MockDestination.SpecRequest r) {
        Map<String, Object> m = new LinkedHashMap<>(answer(r));
        if (r.operationId().equals("createSubmission")) m.put("idempotentReplay", true);
        if (r.operationId().equals("uploadDocument")) {
            if (duplicate) {
                m.put("receiptAcknowledgement", "DUPLICATE_ALREADY_STORED");
                m.put("duplicate", true);
                m.put("exactDuplicateContent", true);
            } else {
                m.put("idempotentReplay", true);
            }
        }
        return m;
    }

    @Override
    public Optional<MockDestination.SpecError> refuse(MockDestination.SpecRequest r) {
        String doc = failDocument;
        if (doc != null && r.operationId().equals("uploadDocument")
                && doc.equals(r.header("X-SDV-Source-Document-Id").orElse(null)) && failures.getAndDecrement() > 0) {
            return Optional.of(failWith);
        }
        return Optional.empty();
    }
}
