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

    /**
     * P025: the proxy password (right and wrong), document text, a sensitive case field that is allowed into the
     * manifest, and the export folder's path go through proxy sign-in and folder export (success, every failure,
     * health, Test action), and none of it reaches a log line, a result's or an exception's text.
     */
    @Test
    void nothingSecretOrContentFromProxySignInOrFolderExport(@TempDir Path dir) throws Exception {
        String proxyPassword = "PROXYPW-planted-3c3c";
        String sensitive = "SENSITIVE-planted-4d4d";
        String pathPart = "PATHPART-planted-5e5e";
        List<String> outputs = new ArrayList<>();

        // Proxy sign-in: right, wrong, the JVM refusing Basic for https.
        try (com.documentplatform.integration.testkit.MockProxy proxy =
                     com.documentplatform.integration.testkit.MockProxy.start().requireBasic("svc", proxyPassword)) {
            com.documentplatform.notifications.inmemory.InMemorySecretResolver s =
                    new com.documentplatform.notifications.inmemory.InMemorySecretResolver()
                            .put(SecretRef.of("int/bearer"), BEARER).put(SecretRef.of("int/proxy"), proxyPassword);
            com.documentplatform.integration.safety.Installation inst = fx.installation.withProxy(
                    com.documentplatform.integration.safety.ProxySettings.of(proxy.host(), proxy.port())
                            .withSignIn("svc", SecretRef.of("int/proxy")));
            outputs.add(inst.toString());
            try (com.documentplatform.integration.engine.CallEngine engine =
                         new com.documentplatform.integration.engine.CallEngine(
                                 new com.documentplatform.integration.http.Outbound(inst, s), s, Clock.systemUTC())) {
                IntegrationConfig c = Fx.twoStep(fx.mock.addressByName(), new SignIn.BearerFixed(SecretRef.of("int/bearer")));
                fx.mock.reset();
                Delivery d = Delivery.of("pkg-p", List.of(Delivery.Document.of("doc-1", "a.pdf", "application/pdf",
                        (TEXT + " proxied").getBytes())), java.util.Map.of("loanId", VALUE));
                outputs.add(engine.attempt(Fx.stored(c), d).toString());
                s.put(SecretRef.of("int/proxy"), proxyPassword + "-wrong");
                outputs.add(engine.attempt(Fx.stored(c), d).toString());
                outputs.add(engine.health(Fx.stored(c)).toString());
                IntegrationConfig https = c.withEndpoint(new com.documentplatform.integration.config.Endpoint(
                        java.net.URI.create("https://localhost:" + fx.mock.port()), true));
                outputs.add(engine.attempt(Fx.stored(https), d).toString());
                outputs.add(new com.documentplatform.integration.http.Outbound.Failure(
                        IntegrationCode.INT_PROXY_SIGN_IN_DISABLED_BY_JVM).getMessage());
            }
        }

        // Folder export: success with a sensitive field allowed into the manifest, every failure, health, Test.
        com.documentplatform.integration.testkit.MockFolder folder =
                com.documentplatform.integration.testkit.MockFolder.in(dir.resolve(pathPart));
        com.documentplatform.integration.folder.FolderExport export =
                new com.documentplatform.integration.folder.FolderExport(fx.installation, folder, Clock.systemUTC());
        com.documentplatform.fields.FieldSet fields = com.documentplatform.fields.FieldSet.of(List.of(
                com.documentplatform.fields.FieldSpec.builder("holder_name", "Holder",
                        com.documentplatform.fields.FieldType.TEXT).sensitive(true).build()));
        com.documentplatform.fields.ValidatedFields v = fields.validate(java.util.Map.of("holder_name",
                List.of(new com.documentplatform.fields.FieldValue.Text(sensitive))));
        IntegrationConfig fc = IntegrationConfig.folderExport(FolderExportTest.ENTITY, "PRODUCT_A", "Share",
                        com.documentplatform.integration.config.FolderTarget.of(folder.folder()))
                .withAllowedSensitiveFields(java.util.Set.of("holder_name"));
        com.documentplatform.integration.store.StoredIntegration stored = Fx.stored(fc);
        com.documentplatform.integration.folder.ExportInfo info =
                new com.documentplatform.integration.folder.ExportInfo(FolderExportTest.SLOT, "{\"note\":\"" + VALUE + "\"}");
        int n = 0;
        for (Runnable sw : List.<Runnable>of(folder::working, () -> folder.readOnly(true), () -> folder.missing(true),
                () -> folder.unreachable(true), () -> folder.fullAfter(10), () -> folder.corrupt(true),
                () -> folder.reportedSpace(1))) {
            folder.working();
            sw.run();
            Delivery d = Delivery.of("pkg-f" + (n++), List.of(Delivery.Document.of("doc-1", "a.pdf", "application/pdf",
                    (TEXT + " exported").getBytes())), java.util.Map.of())
                    .withFields(com.documentplatform.integration.engine.CaseFields.of(fields, v));
            AttemptResult r = export.attempt(stored, d, info);
            outputs.add(r.toString());
            outputs.add(r.values().toString());
            outputs.add(export.health(stored).toString());
            outputs.add(new TestAction(fx.engine, export, IntegrationAudit.NONE).run(stored, "admin").toString());
        }
        folder.working();
        assertTrue(folder.manifest("pkg-f0").contains(sensitive), "allowed into the manifest on purpose");
        outputs.add(assertThrows(IllegalArgumentException.class,
                () -> new com.documentplatform.integration.folder.ExportInfo(FolderExportTest.SLOT, "[\"" + VALUE + "\"]"))
                .getMessage());
        outputs.add(fc.toString());

        List<String> all = new ArrayList<>(outputs);
        synchronized (lines) {
            all.addAll(lines);
        }
        assertTrue(lines.stream().anyMatch(l -> l.contains("INT_FOLDER_NO_PERMISSION")), "folder export logs codes");
        for (String line : all) {
            for (String planted : List.of(proxyPassword, BEARER, TEXT, VALUE, sensitive, pathPart, "c3ZjOl")) {
                assertFalse(line.contains(planted), "found " + planted + " in: " + line);
            }
        }
    }
}
