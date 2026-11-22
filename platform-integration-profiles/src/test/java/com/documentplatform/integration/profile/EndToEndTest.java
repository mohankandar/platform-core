package com.documentplatform.integration.profile;

import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.integration.config.ConfigProblem;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.Limits;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.engine.Delivery;
import com.documentplatform.integration.engine.TestAction;
import com.documentplatform.integration.result.AttemptResult;
import com.documentplatform.integration.result.HealthResult;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.integration.store.IntegrationAudit;
import com.documentplatform.integration.store.IntegrationConfigs;
import com.documentplatform.integration.store.StoredIntegration;
import com.documentplatform.integration.testkit.InMemoryIntegrationStore;
import com.documentplatform.integration.testkit.MockDestination;
import com.documentplatform.notifications.secret.SecretRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The sdv-ingestion-v1 profile end to end against the spec-mode mock, for every switch. */
class EndToEndTest {

    private ProfileFx fx = new ProfileFx(true);

    @AfterEach
    void close() {
        fx.close();
    }

    private StoredIntegration claims() {
        return ProfileFx.stored(fx.config("CLAIMS"));
    }

    private AttemptResult send() {
        return fx.engine.attempt(claims(), fx.delivery(2));
    }

    @Test
    void sendsAPackageInTwoStepsWithTypedMetadataAndIntegrity() {
        fx.api.mock.recordValues(true);
        Delivery d = fx.delivery(2);
        AttemptResult r = fx.engine.attempt(claims(), d);
        assertEquals(Outcome.SENT, r.outcome(), r + " " + fx.api.mock.specProblems());
        assertEquals(List.of(), fx.api.mock.specProblems());
        List<MockDestination.Received> calls = fx.api.mock.calls();
        assertEquals(3, calls.size());
        Map<String, Object> create = calls.get(0).fields();
        assertEquals(d.packageId(), create.get("sourceSubmissionId"));
        assertEquals(2L, create.get("expectedDocumentCount"));
        assertEquals(List.of(Map.of("sourceDocumentId", "doc-1"), Map.of("sourceDocumentId", "doc-2")),
                create.get("expectedDocuments"));
        assertEquals(Map.of("contractNumber", "990023450001", "loanAmount", 250000.0), create.get("metadata"),
                "the TEXT field as a string, the MONEY field as a number, normalised");
        MockDestination.Received upload = calls.get(1);
        assertEquals("file-1.pdf", upload.header("x-sdv-filename").orElseThrow());
        assertEquals("doc-1", upload.header("x-sdv-source-document-id").orElseThrow());
        assertEquals("doc-1", upload.header("idempotency-key").orElseThrow());
        assertEquals("STANDARD", upload.header("x-sdv-sensitivity-level").orElseThrow(), "the profile's default");
        assertEquals("application/pdf", upload.contentType());
        assertEquals(d.documents().get(0).sha256(), upload.sha256());
        assertEquals(d.documents().get(0).sha256(), r.documentValues().get("doc-1").get("upload.contentSha256"));
        assertNotNull(r.values().get("create.submissionId"));
        assertTrue(calls.stream().allMatch(c -> "OAUTH2".equals(c.signIn())), "the token carried the two claims");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 300})
    void busyIsBusyWithItsRetryAfterAndUsesNoAttempt(int seconds) {
        fx.api.mock.busy(503, "SDV-SYS-018", Duration.ofSeconds(seconds));
        AttemptResult r = send();
        assertEquals(Outcome.BUSY, r.outcome());
        assertEquals(IntegrationCode.INT_BUSY, r.code());
        assertEquals(Duration.ofSeconds(seconds), r.retryAfter());
        assertFalse(r.attemptUsed());
        assertEquals("SDV-SYS-018", r.destinationCode());
    }

    @Test
    void a503WithoutTheBusyCodeIsAnOutageAndStorageNotAvailableIsDown() {
        fx.api.mock.outage();
        AttemptResult bare = send();
        assertEquals(Outcome.OUTAGE, bare.outcome());
        assertTrue(bare.attemptUsed());
        fx.api.mock.busy(503, "SDV-STO-004", null);
        AttemptResult storage = send();
        assertEquals(Outcome.OUTAGE, storage.outcome());
        assertEquals("SDV-STO-004", storage.destinationCode(), "IT sees why");
        fx.api.mock.busy(503, "SDV-SYS-014", null);
        assertEquals(Outcome.OUTAGE, send().outcome());
    }

    @Test
    void signInFaultsIncludingAut004() {
        fx.api.mock.signInRefused("SDV-AUT-003");
        AttemptResult r = send();
        assertEquals(Outcome.SIGN_IN_FAILED, r.outcome());
        assertEquals("SDV-AUT-003", r.destinationCode());
        fx.api.mock.refuse(403, "SDV-INT-005");
        assertEquals(Outcome.SIGN_IN_FAILED, send().outcome());
        fx.api.mock.busy(503, "SDV-AUT-004", null);
        AttemptResult aut004 = send();
        assertEquals(Outcome.SIGN_IN_FAILED, aut004.outcome(), "a sign-in fault, not busy");
        assertTrue(aut004.attemptUsed());
    }

    @Test
    void alreadyReceivedBothWays() {
        fx.api.mock.alreadyReceived(null);
        fx.api.duplicate = true;
        AttemptResult dup = send();
        assertEquals(Outcome.ALREADY_RECEIVED, dup.outcome(), dup + " " + fx.api.mock.specProblems());
        assertEquals("DUPLICATE_ALREADY_STORED", dup.documentValues().get("doc-1").get("upload.receipt"));
        fx.api.duplicate = false;
        assertEquals(Outcome.ALREADY_RECEIVED, send().outcome(), "an idempotent retry");
        assertEquals(List.of(), fx.api.mock.specProblems());
    }

    @Test
    void refusals() {
        fx.api.mock.refuse(409, "SDV-INT-017");
        AttemptResult r = send();
        assertEquals(Outcome.REFUSED, r.outcome());
        assertEquals("SDV-INT-017", r.destinationCode());
        assertEquals(409, r.httpStatus());
        fx.api.mock.refuse(400, "SDV-SEN-001");
        assertEquals(Outcome.REFUSED, send().outcome());
        fx.api.mock.refuse(413, "SDV-INT-014");
        assertEquals(Outcome.REFUSED, send().outcome());
    }

    @Test
    void healthBusyVersusDown() {
        StoredIntegration s = claims();
        assertTrue(fx.engine.health(s).healthy());

        fx.api.mock.healthBusy("SDV-SYS-018", Duration.ofSeconds(5));
        HealthResult code = fx.engine.health(s);
        assertTrue(code.busy());
        assertFalse(code.down());
        assertEquals(IntegrationCode.INT_DESTINATION_BUSY, code.code());
        assertEquals(Duration.ofSeconds(5), code.retryAfter());

        fx.api.mock.healthBusy(null, Duration.ofSeconds(30));
        HealthResult retryAfterOnly = fx.engine.health(s);
        assertTrue(retryAfterOnly.busy(), "503 with Retry-After only is busy too");
        assertEquals(Duration.ofSeconds(30), retryAfterOnly.retryAfter());

        fx.api.mock.healthFailing(true);
        HealthResult bare = fx.engine.health(s);
        assertTrue(bare.down(), "503 without the busy code and without Retry-After is down");
        assertEquals(Outcome.OUTAGE, bare.outcome());

        fx.api.mock.healthOk().healthSlow(Duration.ofMillis(2500));
        HealthResult slow = fx.engine.health(ProfileFx.stored(s.config().withLimits(
                s.config().limits().withReadTimeout(Duration.ofSeconds(1)))));
        assertTrue(slow.down());
        assertEquals(IntegrationCode.INT_TIMEOUT, slow.code());

        fx.api.mock.healthOk().down();
        HealthResult unreachable = fx.engine.health(s);
        assertTrue(unreachable.down());
        assertEquals(Outcome.CANT_CONNECT, unreachable.outcome());
        fx.api.mock.up();
    }

    @Test
    void resumesAfterAFailureMidUpload() {
        fx.api.failDocument = "doc-2";
        fx.api.failWith = new MockDestination.SpecError(503, "SDV-SYS-018", Duration.ofSeconds(5));
        fx.api.failures.set(1);
        Delivery d = fx.delivery(3);
        StoredIntegration s = claims();
        AttemptResult first = fx.engine.attempt(s, d);
        assertEquals(Outcome.BUSY, first.outcome());
        assertEquals("upload", first.stepReached());
        assertEquals("doc-2", first.documentReached());
        assertEquals(Set.of("doc-1"), first.documentsDone());
        AttemptResult second = fx.engine.attempt(s, d.resumingFrom(Delivery.Resume.from(first)));
        assertEquals(Outcome.SENT, second.outcome());
        assertEquals(Set.of("doc-1", "doc-2", "doc-3"), second.documentsDone());
        List<String> sent = fx.api.mock.calls().stream()
                .map(c -> c.operation() + ":" + c.header("x-sdv-source-document-id").orElse("-")).toList();
        assertEquals(List.of("createSubmission:-", "uploadDocument:doc-1", "uploadDocument:doc-2",
                "uploadDocument:doc-2", "uploadDocument:doc-3"), sent, "create and doc-1 are not sent again");
    }

    @Test
    void anIntegrityMismatchIsUnclearAndStops() {
        fx.api.wrongSha256 = "0".repeat(64);
        AttemptResult r = send();
        assertEquals(Outcome.UNCLEAR, r.outcome());
        assertEquals(IntegrationCode.INT_INTEGRITY_MISMATCH, r.code());
        assertTrue(r.attemptUsed());
        assertEquals("upload", r.stepReached());
        assertEquals("doc-1", r.documentReached());
        assertEquals(2, fx.api.mock.calls().size(), "doc-2 is not sent after the mismatch");
    }

    @Test
    void sensitiveFieldsOnlyWhenAllowed() {
        Map<String, String> withName = new java.util.LinkedHashMap<>(ProfileFx.MAPPING);
        withName.put("memberName", "member_name");
        IntegrationConfigs configs = new IntegrationConfigs(new InMemoryIntegrationStore(), IntegrationAudit.NONE,
                fx.installation, Clock.systemUTC(), (entity, product) -> Optional.of(ProfileFx.FIELDS));

        IntegrationConfig notAllowed = fx.config("CLAIMS", withName, Set.of());
        IntegrationConfigs.Refused refused = assertThrows(IntegrationConfigs.Refused.class,
                () -> configs.create(notAllowed, "it-admin"));
        assertEquals(List.of(IntegrationCode.INT_CONFIG_SENSITIVE_FIELD_NOT_ALLOWED),
                refused.problems().stream().map(ConfigProblem::code).toList());
        // Even unsaved, the engine refuses to send it.
        Delivery d = fx.delivery(1).withFields(ProfileFx.caseFields(true));
        AttemptResult blocked = fx.engine.attempt(ProfileFx.stored(notAllowed), d);
        assertEquals(Outcome.NOT_CONFIGURED, blocked.outcome());
        assertEquals(IntegrationCode.INT_SENSITIVE_FIELD_NOT_ALLOWED, blocked.code());
        assertTrue(fx.api.mock.received().isEmpty(), "nothing was sent");

        List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.logging.Handler capture = new java.util.logging.Handler() {
            public void publish(java.util.logging.LogRecord rec) {
                lines.add(new java.util.logging.SimpleFormatter().format(rec));
            }

            public void flush() {
            }

            public void close() {
            }
        };
        java.util.logging.Logger.getLogger("").addHandler(capture);
        IntegrationConfig allowed = fx.config("CLAIMS", withName, Set.of("member_name"));
        StoredIntegration saved;
        AttemptResult r;
        try {
            saved = configs.create(allowed, "it-admin");
            r = fx.engine.attempt(saved, d);
        } finally {
            java.util.logging.Logger.getLogger("").removeHandler(capture);
        }
        assertEquals(Outcome.SENT, r.outcome(), r + " " + fx.api.mock.specProblems());
        String records = fx.api.mock.received().toString();
        assertFalse(records.contains("Example Member"), "the mock records field names only");
        assertTrue(records.contains("metadata"));
        assertFalse(r.toString().contains("Example Member"));
        assertFalse(r.values().toString().contains("Example Member"));
        assertFalse(lines.isEmpty());
        assertTrue(lines.stream().noneMatch(l -> l.contains("Example Member") || l.contains("990023450001")),
                "no case field value in a log line");

        IntegrationConfig unknown = fx.config("CLAIMS", Map.of("x", "no_such_field"), Set.of());
        assertEquals(IntegrationCode.INT_CONFIG_FIELD_UNKNOWN, assertThrows(IntegrationConfigs.Refused.class,
                () -> configs.create(unknown, "it-admin")).problems().get(0).code());
    }

    @Test
    void theProfileSavesAndChecksItsSettings() {
        IntegrationConfigs configs = new IntegrationConfigs(new InMemoryIntegrationStore(), IntegrationAudit.NONE,
                fx.installation, Clock.systemUTC(), (entity, product) -> Optional.of(ProfileFx.FIELDS));
        StoredIntegration saved = configs.create(fx.config("CLAIMS"), "it-admin");
        assertEquals("sdv-ingestion-v1", saved.config().profile());
        assertEquals("CLAIMS", saved.config().settings().get("senderIdentity"));
        assertEquals(List.of("sdv_tenant_id", "sdv_source_id"), saved.config().requiredTokenClaims());
        assertEquals(List.of(), fx.config("LOOKUP").requiredTokenClaims());
        assertThrows(IllegalArgumentException.class, () -> fx.config("MAYBE"));
        assertThrows(IllegalArgumentException.class, () -> fx.profile.complete(new Profile.Completion(ProfileFx.ENTITY,
                ProfileFx.PRODUCT, "x", new com.documentplatform.integration.config.Endpoint(fx.api.mock.address(),
                true), new SignIn.BearerFixed(SecretRef.of("int/t")), Map.of(), Set.of(), Map.of())),
                "the profile takes OAuth 2.0 client credentials only");
        assertThrows(IllegalArgumentException.class, () -> fx.profile.withDocumentDefaults(Delivery.of("p",
                List.of(fx.delivery(1).documents().get(0).withAttribute("sensitivityLevel", "secret")), Map.of())));
    }

    @Test
    void claimsModeChecksTheTokenLookupModeDoesNot() {
        // The login server does not add the claims (LOOKUP set-up) but the profile is set to CLAIMS.
        fx.close();
        fx = new ProfileFx(false);
        AttemptResult missing = fx.engine.attempt(claims(), fx.delivery(1));
        assertEquals(Outcome.SIGN_IN_FAILED, missing.outcome());
        assertEquals(IntegrationCode.INT_TOKEN_CLAIMS_MISSING, missing.code());
        assertTrue(fx.api.mock.calls().isEmpty(), "found before anything was sent");
        AttemptResult lookup = fx.engine.attempt(ProfileFx.stored(fx.config("LOOKUP")), fx.delivery(1));
        assertEquals(Outcome.SENT, lookup.outcome());

        // A destination that wants the claims answers AUT-005 when they are missing.
        fx.close();
        fx = new ProfileFx(true);
        fx.api.mock.tokenClaims(Map.of());
        AttemptResult refused = fx.engine.attempt(ProfileFx.stored(fx.config("LOOKUP")), fx.delivery(1));
        assertEquals(Outcome.SIGN_IN_FAILED, refused.outcome());
        assertEquals("SDV-AUT-005", refused.destinationCode());
    }

    @Test
    void theTestActionChecksHealthAndTheToken() {
        AttemptResult ok = new TestAction(fx.engine, IntegrationAudit.NONE).run(claims(), "it-admin");
        assertEquals(IntegrationCode.INT_TEST_CHECKED, ok.code());
        assertTrue(fx.api.mock.calls().isEmpty(), "CHECK_ONLY sends nothing");
        fx.api.mock.healthBusy("SDV-SYS-018", Duration.ofSeconds(5));
        assertEquals(Outcome.BUSY, new TestAction(fx.engine, IntegrationAudit.NONE).run(claims(), "it").outcome());
    }

    @Test
    void sizeLimitsBeforeSending() {
        IntegrationConfig c = fx.config("CLAIMS").withLimits(Limits.DEFAULT.withMaxPackageBytes(30));
        AttemptResult r = fx.engine.attempt(ProfileFx.stored(c), fx.delivery(2));
        assertEquals(IntegrationCode.INT_PACKAGE_TOO_LARGE, r.code());
        assertTrue(fx.api.mock.received().isEmpty());
    }
}
