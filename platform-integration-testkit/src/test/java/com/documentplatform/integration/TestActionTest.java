package com.documentplatform.integration;

import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.SignIn.OAuth2ClientCredentials;
import com.documentplatform.integration.engine.TestAction;
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

/** The Test action runs the full path with fake data and never sends real content. */
class TestActionTest {

    private Fx fx;
    private ConfigVersioningTest.RecordingAudit audit;
    private TestAction action;
    private IntegrationConfig config;

    @BeforeEach
    void setUp() {
        fx = new Fx();
        audit = new ConfigVersioningTest.RecordingAudit();
        action = new TestAction(fx.engine, audit);
        fx.secrets.put(SecretRef.of("int/client"), "client-secret");
        fx.mock.expectOAuth2().registerClient("app", "client-secret");
        config = Fx.twoStep(fx.mock.address(), OAuth2ClientCredentials.secret(fx.mock.tokenAddress(), "app",
                SecretRef.of("int/client"), null));
    }

    @AfterEach
    void close() {
        fx.close();
    }

    @Test
    void fullRunSendsOnlyTheFakeDocumentAndFakeValues() {
        fx.mock.recordValues(true);
        AttemptResult r = action.run(Fx.stored(config), "it-admin");
        assertEquals(Outcome.SENT, r.outcome(), r.toString());
        assertTrue(r.test());
        List<MockDestination.Received> calls = fx.mock.calls();
        assertEquals(2, calls.size());
        MockDestination.Received create = calls.get(0);
        assertTrue(((String) create.fields().get("packageId")).startsWith("test-"));
        assertEquals(Map.of("loan", "test-loanId"), create.fields().get("metadata"));
        MockDestination.Received upload = calls.get(1);
        assertEquals(TestAction.FAKE_PDF_SHA256, upload.sha256());
        assertEquals(TestAction.FAKE_PDF.length, upload.size());
        assertEquals(TestAction.FAKE_FILE_NAME, upload.header("x-file-name").orElseThrow());
        assertEquals(List.of("it-admin:SENT"), audit.tests);
    }

    @Test
    void checkOnlyRunsHealthAndSignInAndSendsNoStep() {
        AttemptResult r = action.run(Fx.stored(config.withTestMode(IntegrationConfig.TestMode.CHECK_ONLY)), "it");
        assertEquals(Outcome.SENT, r.outcome());
        assertEquals(IntegrationCode.INT_TEST_CHECKED, r.code());
        assertTrue(fx.mock.calls().isEmpty(), "no step was sent");
        assertEquals(1, fx.mock.received().size(), "only the health check");
        assertEquals(1, fx.mock.tokensIssued());

        fx.mock.healthFailing(true);
        AttemptResult unhealthy = action.run(Fx.stored(config.withTestMode(IntegrationConfig.TestMode.CHECK_ONLY)),
                "it");
        assertEquals(Outcome.OUTAGE, unhealthy.outcome());
        assertEquals("health", unhealthy.stepReached());

        fx.mock.healthFailing(false).tokenMode(MockDestination.TokenMode.REFUSE_CLIENT);
        IntegrationConfig fresh = Fx.twoStep(fx.mock.address(), OAuth2ClientCredentials.secret(
                fx.mock.tokenAddress(), "other-app", SecretRef.of("int/client"), null))
                .withTestMode(IntegrationConfig.TestMode.CHECK_ONLY);
        AttemptResult badSignIn = action.run(Fx.stored(fresh), "it");
        assertEquals(Outcome.SIGN_IN_FAILED, badSignIn.outcome());
        assertEquals("sign-in", badSignIn.stepReached());
        assertEquals(3, audit.tests.size(), "every run is audited");
    }

    @Test
    void theFakePdfIsAPdf() {
        String head = new String(TestAction.FAKE_PDF, 0, 8, java.nio.charset.StandardCharsets.US_ASCII);
        assertEquals("%PDF-1.4", head);
        assertTrue(new String(TestAction.FAKE_PDF, java.nio.charset.StandardCharsets.US_ASCII).contains("%%EOF"));
    }
}
