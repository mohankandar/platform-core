package com.documentplatform.integration;

import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.config.SignIn.OAuth2ClientCredentials;
import com.documentplatform.integration.engine.Delivery;
import com.documentplatform.integration.engine.TestAction;
import com.documentplatform.integration.result.AttemptResult;
import com.documentplatform.integration.store.IntegrationAudit;
import com.documentplatform.integration.store.IntegrationConfigs;
import com.documentplatform.integration.testkit.InMemoryIntegrationStore;
import com.documentplatform.integration.testkit.MockDestination;
import com.documentplatform.integration.testkit.TestCertificates;
import com.documentplatform.notifications.secret.SecretRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plants secrets and document text, runs every sign-in type, failures and the Test action, and checks that none of it
 * reaches a log line, a result's text or an exception's text.
 */
class NoSecretsInLogsTest {

    private static final String PASSWORD = "PW-planted-7f3a";
    private static final String API_KEY = "KEY-planted-91bc";
    private static final String BEARER = "BEARER-planted-55d0";
    private static final String CLIENT_SECRET = "CS-planted-e2e2";
    private static final String TEXT = "DOCUMENT-TEXT-planted-0a0a";
    private static final String VALUE = "VALUE-planted-1b1b";

    private final List<String> lines = new ArrayList<>();
    private final Handler capture = new Handler() {
        private final SimpleFormatter f = new SimpleFormatter();

        @Override
        public void publish(LogRecord r) {
            synchronized (lines) {
                lines.add(f.format(r));
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private Fx fx;

    @BeforeEach
    void setUp() {
        Logger root = Logger.getLogger("");
        root.addHandler(capture);
        root.setLevel(Level.ALL);
        Logger.getLogger("com.documentplatform").setLevel(Level.ALL);
        fx = new Fx();
        fx.secrets.put(SecretRef.of("int/pw"), PASSWORD).put(SecretRef.of("int/key"), API_KEY)
                .put(SecretRef.of("int/bearer"), BEARER).put(SecretRef.of("int/client"), CLIENT_SECRET);
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger("").removeHandler(capture);
        fx.close();
    }

    @Test
    void nothingSecretOrContentAnywhere(@TempDir Path dir) throws Exception {
        TestCertificates.Credential cred = TestCertificates.credential(dir, "cert", -1, 30);
        fx.secrets.put(SecretRef.of("int/cert"), cred.pem());
        List<String> outputs = new ArrayList<>();
        MockDestination m = fx.mock;
        m.registerClient("app", CLIENT_SECRET).registerCertificateClient("app-cert", cred.certificate());

        List<SignIn> signIns = List.of(
                new SignIn.Basic("user", SecretRef.of("int/pw")),
                new SignIn.ApiKey("X-Api-Key", SecretRef.of("int/key")),
                new SignIn.BearerFixed(SecretRef.of("int/bearer")),
                OAuth2ClientCredentials.secret(m.tokenAddress(), "app", SecretRef.of("int/client"), "s"),
                OAuth2ClientCredentials.secret(m.tokenAddress(), "app", SecretRef.of("int/client"), "s")
                        .withClientAuth(OAuth2ClientCredentials.ClientAuth.BASIC),
                OAuth2ClientCredentials.certificate(m.tokenAddress(), "app-cert", SecretRef.of("int/cert"), null));
        Delivery d = Delivery.of("pkg-1", List.of(Delivery.Document.of("doc-1", "a.pdf", "application/pdf",
                (TEXT + " more text").getBytes())), java.util.Map.of("loanId", VALUE));

        for (SignIn s : signIns) {
            IntegrationConfig c = Fx.twoStep(m.address(), s);
            m.reset();
            m.registerClient("app", CLIENT_SECRET).registerCertificateClient("app-cert", cred.certificate());
            switch (s) {
                case SignIn.Basic b -> m.expectBasic("user", PASSWORD);
                case SignIn.ApiKey k -> m.expectApiKey("X-Api-Key", API_KEY);
                case SignIn.BearerFixed t -> m.expectBearer(BEARER);
                default -> m.expectOAuth2();
            }
            outputs.add(fx.engine.attempt(Fx.stored(c), d).toString());                      // works
            m.signInRefused("MOCK-AUT-001");
            outputs.add(fx.engine.attempt(Fx.stored(c), d).toString());                      // refused
            m.refuse(400, "MOCK-INT-008");
            outputs.add(fx.engine.attempt(Fx.stored(c), d).toString());
            m.busy(503, "MOCK-BUSY", Duration.ofSeconds(1));
            outputs.add(fx.engine.attempt(Fx.stored(c), d).toString());
            m.working().tokenMode(MockDestination.TokenMode.REFUSE_CLIENT);
            outputs.add(fx.engine.attempt(Fx.stored(c), d).toString());
            outputs.add(fx.engine.health(Fx.stored(c)).toString());
            AttemptResult test = new TestAction(fx.engine, IntegrationAudit.NONE).run(Fx.stored(c), "admin");
            outputs.add(test.toString());
            outputs.add(test.values().toString());
            outputs.add(test.steps().toString());
        }
        // Refused configurations and store problems.
        IntegrationConfigs configs = new IntegrationConfigs(new InMemoryIntegrationStore(), IntegrationAudit.NONE,
                fx.installation, Clock.systemUTC());
        IntegrationConfig bad = Fx.oneStep(m.address(), new SignIn.BearerFixed(SecretRef.of("int/bearer")))
                .withSteps(List.of(com.documentplatform.integration.config.CallStep.of("x",
                        com.documentplatform.integration.config.HttpMethod.POST, "/a?k={value.key}")));
        outputs.add(assertThrows(IntegrationConfigs.Refused.class, () -> configs.create(bad, "admin")).getMessage());
        outputs.add(assertThrows(IllegalArgumentException.class, () -> SecretRef.of("https://" + PASSWORD))
                .getMessage());
        List<String> all = new ArrayList<>(outputs);
        synchronized (lines) {
            all.addAll(lines);
        }
        assertFalse(lines.isEmpty(), "the engine logs");
        String keyBase64 = cred.keyBase64().substring(0, 40);
        for (String line : all) {
            for (String planted : List.of(PASSWORD, API_KEY, BEARER, CLIENT_SECRET, TEXT, VALUE, keyBase64,
                    "mock-")) {
                assertFalse(line.contains(planted), "found " + planted + " in: " + line);
            }
        }
        // The mock's own records hold the values that were sent on purpose (fields), never document text or secrets.
        String records = m.received() + " " + m.tokenRequests();
        for (String planted : List.of(PASSWORD, API_KEY, BEARER, CLIENT_SECRET, TEXT, keyBase64)) {
            assertFalse(records.contains(planted), "the mock recorded " + planted);
        }
    }
}
