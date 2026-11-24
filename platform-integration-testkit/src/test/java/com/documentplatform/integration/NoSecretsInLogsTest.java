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

    /** A copy of the log lines so far, taken under the lock (the mocks' threads may still be logging). */
    private List<String> logged() {
        synchronized (lines) {
            return List.copyOf(lines);
        }
    }
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
        fx.secrets.put(SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/pw"), PASSWORD).put(SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/key"), API_KEY)
                .put(SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/bearer"), BEARER).put(SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/client"), CLIENT_SECRET);
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger("").removeHandler(capture);
        fx.close();
    }

    @Test
    void nothingSecretOrContentAnywhere(@TempDir Path dir) throws Exception {
        TestCertificates.Credential cred = TestCertificates.credential(dir, "cert", -1, 30);
        fx.secrets.put(SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/cert"), cred.pem());
        List<String> outputs = new ArrayList<>();
        MockDestination m = fx.mock;
        m.registerClient("app", CLIENT_SECRET).registerCertificateClient("app-cert", cred.certificate());

        List<SignIn> signIns = List.of(
                new SignIn.Basic("user", SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/pw")),
                new SignIn.ApiKey("X-Api-Key", SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/key")),
                new SignIn.BearerFixed(SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/bearer")),
                OAuth2ClientCredentials.secret(m.tokenAddress(), "app", SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/client"), "s"),
                OAuth2ClientCredentials.secret(m.tokenAddress(), "app", SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/client"), "s")
                        .withClientAuth(OAuth2ClientCredentials.ClientAuth.BASIC),
                OAuth2ClientCredentials.certificate(m.tokenAddress(), "app-cert", SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/cert"), null));
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
        IntegrationConfig bad = Fx.oneStep(m.address(), new SignIn.BearerFixed(SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/bearer")))
                .withSteps(List.of(com.documentplatform.integration.config.CallStep.of("x",
                        com.documentplatform.integration.config.HttpMethod.POST, "/a?k={value.key}")));
        outputs.add(assertThrows(IntegrationConfigs.Refused.class, () -> configs.create(bad, "admin")).getMessage());
        outputs.add(assertThrows(IllegalArgumentException.class, () -> SecretRef.of("https://" + PASSWORD))
                .getMessage());
        List<String> all = new ArrayList<>(outputs);
        synchronized (lines) {
            all.addAll(lines);
        }
        assertFalse(logged().isEmpty(), "the engine logs");
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
                            .put(SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/bearer"), BEARER).put(SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/proxy"), proxyPassword);
            com.documentplatform.outbound.Installation inst = fx.installation.withProxy(
                    com.documentplatform.outbound.ProxySettings.of(proxy.host(), proxy.port())
                            .withSignIn("svc", SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/proxy")));
            outputs.add(inst.toString());
            try (com.documentplatform.integration.engine.CallEngine engine =
                         new com.documentplatform.integration.engine.CallEngine(
                                 new com.documentplatform.outbound.Outbound(inst, s), s, Clock.systemUTC())) {
                IntegrationConfig c = Fx.twoStep(fx.mock.addressByName(), new SignIn.BearerFixed(SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/bearer")));
                fx.mock.reset();
                Delivery d = Delivery.of("pkg-p", List.of(Delivery.Document.of("doc-1", "a.pdf", "application/pdf",
                        (TEXT + " proxied").getBytes())), java.util.Map.of("loanId", VALUE));
                outputs.add(engine.attempt(Fx.stored(c), d).toString());
                s.put(SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/proxy"), proxyPassword + "-wrong");
                outputs.add(engine.attempt(Fx.stored(c), d).toString());
                outputs.add(engine.health(Fx.stored(c)).toString());
                IntegrationConfig https = c.withEndpoint(new com.documentplatform.integration.config.Endpoint(
                        java.net.URI.create("https://localhost:" + fx.mock.port()), true));
                outputs.add(engine.attempt(Fx.stored(https), d).toString());
                outputs.add(new com.documentplatform.outbound.Outbound.Failure(
                        com.documentplatform.outbound.OutboundCode.OUT_PROXY_SIGN_IN_REFUSED).getMessage());
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
        // xc-never-leak-74: the case field values' and each field's text, sensitive or not.
        com.documentplatform.fields.FieldSet plain = com.documentplatform.fields.FieldSet.of(List.of(
                com.documentplatform.fields.FieldSpec.builder("note", "Note",
                        com.documentplatform.fields.FieldType.TEXT).build()));
        for (com.documentplatform.integration.engine.CaseFields cf : List.of(
                com.documentplatform.integration.engine.CaseFields.of(fields, v),
                com.documentplatform.integration.engine.CaseFields.of(plain, plain.validate(java.util.Map.of("note",
                        List.of(new com.documentplatform.fields.FieldValue.Text(VALUE))))))) {
            outputs.add(cf.toString());
            for (String key : List.of("holder_name", "note")) cf.field(key).ifPresent(f -> outputs.add(f.toString()));
        }
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
        assertTrue(logged().stream().anyMatch(l -> l.contains("INT_FOLDER_NO_PERMISSION")), "folder export logs codes");
        for (String line : all) {
            for (String planted : List.of(proxyPassword, BEARER, TEXT, VALUE, sensitive, pathPart, "c3ZjOl")) {
                assertFalse(line.contains(planted), "found " + planted + " in: " + line);
            }
        }
    }

    /**
     * P026: a client certificate's private key, the PKCS#12 and keystore passwords, and a callback's signing secret go
     * through uploads (right and every refusal), keystores, calls with mutual TLS (presented, refused, expired, key
     * service down), the Test action of NEXT, the switch, the expiry check, callbacks (sent, refused, given up) and the
     * verifier; none of it reaches a log line, a result, a view, an exception's text or the mock's records.
     */

    @Test
    void nothingSecretOrContentFromIntake(@TempDir Path dir) throws Exception {
        // P027: polling (every outcome a switch gives), the watched folder (taken, refused, unreadable, attack and
        // incomplete drops), and the scanner (infected, failed).
        List<String> outputs = new ArrayList<>();
        com.documentplatform.integration.config.PollSource src = PollingTest.source(
                com.documentplatform.integration.config.PollSource.Paging.cursor("/paging/next", "cursor", null, 2));
        try (com.documentplatform.integration.testkit.MockSource source =
                     com.documentplatform.integration.testkit.MockSource.start(src);
             com.documentplatform.integration.engine.CallEngine calls = new com.documentplatform.integration.engine
                     .CallEngine(new com.documentplatform.outbound.Outbound(fx.installation), fx.secrets,
                     Clock.systemUTC());
             com.documentplatform.integration.testkit.MockScanner scanner =
                     com.documentplatform.integration.testkit.MockScanner.clamd()) {
            for (int i = 1; i <= 3; i++) {
                source.add("item-" + i, (TEXT + i).getBytes(), java.util.Map.of("fileName", VALUE + i, "kind", VALUE));
            }
            source.expectBearer(BEARER);
            IntegrationConfig c = IntegrationConfig.apiPoll(Fx.ENTITY, Fx.PRODUCT, "Source",
                    new com.documentplatform.integration.config.Endpoint(source.address(), true),
                    new SignIn.BearerFixed(SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/bearer")), src).withErrorCodePointer("/code");
            com.documentplatform.integration.store.StoredIntegration s = Fx.stored(c);
            PollingTest.App app = new PollingTest.App();
            com.documentplatform.integration.scan.Scanning scanning = new com.documentplatform.integration.scan.Scanning(
                    new com.documentplatform.integration.scan.ClamdScanner(new com.documentplatform.integration.scan
                            .ScannerAddress(scanner.host(), scanner.port(), Duration.ofSeconds(2)),
                            new com.documentplatform.outbound.AddressGuard(true)),
                    com.documentplatform.integration.scan.ScanEvents.NONE);
            com.documentplatform.integration.engine.PollEngine poll =
                    new com.documentplatform.integration.engine.PollEngine(calls, scanning, dir);
            outputs.add(poll.pass(s, com.documentplatform.integration.intake.CursorState.START, i -> {
                outputs.add(i.toString() + i.file());
                return app.handle(i);
            }).toString());
            scanner.infected(true);
            outputs.add(poll.pass(s, com.documentplatform.integration.intake.CursorState.START, app).toString());
            scanner.working().down(true);
            outputs.add(poll.pass(s, com.documentplatform.integration.intake.CursorState.START, app).toString());
            scanner.down(false);
            source.refuseDownload("item-1", 403, "MOCK-SRC-NO").failAcknowledge("item-2");
            outputs.add(poll.pass(s, com.documentplatform.integration.intake.CursorState.of("c0"), app).toString());
            source.working().expectBearer(BEARER + "-other");
            outputs.add(poll.pass(s, com.documentplatform.integration.intake.CursorState.START, app).toString());
            source.working().brokenPage(0);
            outputs.add(poll.pass(s, com.documentplatform.integration.intake.CursorState.START, app).toString());

            // The watched folder.
            com.documentplatform.integration.testkit.MockFolder folder =
                    com.documentplatform.integration.testkit.MockFolder.watched(dir);
            java.time.Instant old = java.time.Instant.now().minus(Duration.ofMinutes(90));
            folder.write("drop-1/a.pdf", TEXT, old).write("drop-1/manifest.json", "{\"loan\":\"" + VALUE + "\"}", old);
            folder.write("drop-2/a.pdf", TEXT, old).write("drop-2/manifest.json", "{\"loan\":" + VALUE, old);
            folder.write("drop-3/a.pdf", TEXT, old).write("drop-3/manifest.json", "{\"loan\":\"x\"}", old);
            folder.write("drop-4/a.pdf", TEXT, old);
            IntegrationConfig w = IntegrationConfig.watchedFolder(Fx.ENTITY, Fx.PRODUCT, "Inbox",
                    com.documentplatform.integration.config.WatchSource.of(folder.folder(),
                            com.documentplatform.integration.config.WatchSource.Layout.SUBFOLDER, "*.json")
                            .mapping("loan", "/loan"));
            com.documentplatform.integration.watch.WatchedFolder watched =
                    new com.documentplatform.integration.watch.WatchedFolder(folder,
                            com.documentplatform.outbound.Installation.onPremises(), scanning,
                            Clock.systemUTC());
            outputs.add(watched.pass(Fx.stored(w), d -> {
                outputs.add(d.toString() + d.trigger() + d.files());
                return d.dropId().equals("drop-3")
                        ? com.documentplatform.integration.intake.IntakeAnswer.refused("APP-NO")
                        : com.documentplatform.integration.intake.IntakeAnswer.TAKEN;
            }).toString());
            outputs.add(watched.health(Fx.stored(w)).toString());
            for (String area : List.of("_problem", "_archive")) {
                for (String drop : folder.entries(area)) {
                    if (folder.has(area + "/" + drop + "/problem.json")) {
                        outputs.add(new String(folder.read(area + "/" + drop, "problem.json")));
                    }
                }
            }
        }
        List<String> all = new ArrayList<>(outputs);
        synchronized (lines) {
            all.addAll(lines);
        }
        assertTrue(logged().stream().anyMatch(l -> l.contains("poll:")), "the poll passes log");
        assertTrue(logged().stream().anyMatch(l -> l.contains("watch:")), "the watch passes log");
        for (String line : all) {
            for (String p : List.of(BEARER, TEXT, VALUE)) {
                assertFalse(line.contains(p), "found " + p + " in: " + line);
            }
        }
    }

    /**
     * xc-never-leak-85 (P037): drop and item IDs (the customer's file names, the source's item IDs) may hold personal
     * data; log lines carry {@code drop=#<hex>} / {@code item=#<hex>} instead, while problem.json in the customer's own
     * folder keeps the real name.
     */
    @Test
    void dropAndItemIdsAreLoggedAsReferencesOnly(@TempDir Path dir) throws Exception {
        String refusedDrop = "Statement-Ann-Lee-planted";
        String incompleteDrop = "Scan-Bo-Lee-planted";
        String infectedDrop = "Letter-Cy-Lee-planted";
        String item = "ACCOUNT-0042-planted";
        com.documentplatform.integration.testkit.MockFolder folder =
                com.documentplatform.integration.testkit.MockFolder.watched(dir.resolve("inbox"));
        java.time.Instant old = java.time.Instant.now().minus(Duration.ofMinutes(90));
        folder.write(refusedDrop + "/a.pdf", "x", old).write(refusedDrop + "/manifest.json", "{}", old);
        folder.write(incompleteDrop + "/a.pdf", "x", old);
        IntegrationConfig w = IntegrationConfig.watchedFolder(Fx.ENTITY, Fx.PRODUCT, "Inbox",
                com.documentplatform.integration.config.WatchSource.of(folder.folder(),
                        com.documentplatform.integration.config.WatchSource.Layout.SUBFOLDER, "*.json"));
        com.documentplatform.integration.config.PollSource src = PollingTest.source(
                com.documentplatform.integration.config.PollSource.Paging.none());
        try (com.documentplatform.integration.testkit.MockScanner scanner =
                     com.documentplatform.integration.testkit.MockScanner.clamd();
             com.documentplatform.integration.testkit.MockSource source =
                     com.documentplatform.integration.testkit.MockSource.start(src);
             com.documentplatform.integration.engine.CallEngine calls =
                     new com.documentplatform.integration.engine.CallEngine(
                             new com.documentplatform.outbound.Outbound(fx.installation), fx.secrets,
                             Clock.systemUTC())) {
            com.documentplatform.integration.scan.Scanning scanning = new com.documentplatform.integration.scan.Scanning(
                    new com.documentplatform.integration.scan.ClamdScanner(new com.documentplatform.integration.scan
                            .ScannerAddress(scanner.host(), scanner.port(), Duration.ofSeconds(2)),
                            new com.documentplatform.outbound.AddressGuard(true)),
                    com.documentplatform.integration.scan.ScanEvents.NONE);
            // Refused by the app and incomplete, then (the scanner finding everything) infected.
            new com.documentplatform.integration.watch.WatchedFolder(folder,
                    com.documentplatform.outbound.Installation.onPremises(),
                    com.documentplatform.integration.scan.Scanning.OFF, Clock.systemUTC())
                    .pass(Fx.stored(w), d -> com.documentplatform.integration.intake.IntakeAnswer.refused("APP-NO"));
            folder.write(infectedDrop + "/a.pdf", "x", old).write(infectedDrop + "/manifest.json", "{}", old);
            scanner.infected(true);
            new com.documentplatform.integration.watch.WatchedFolder(folder,
                    com.documentplatform.outbound.Installation.onPremises(), scanning, Clock.systemUTC())
                    .pass(Fx.stored(w), d -> com.documentplatform.integration.intake.IntakeAnswer.TAKEN);
            // A polled item found infected.
            source.add(item, "x".getBytes(), java.util.Map.of("fileName", "a.pdf", "kind", "k"));
            source.expectBearer(BEARER);
            IntegrationConfig c = IntegrationConfig.apiPoll(Fx.ENTITY, Fx.PRODUCT, "Source",
                    new com.documentplatform.integration.config.Endpoint(source.address(), true),
                    new SignIn.BearerFixed(SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/bearer")), src);
            com.documentplatform.integration.intake.PollResult r =
                    new com.documentplatform.integration.engine.PollEngine(calls, scanning, dir)
                            .pass(Fx.stored(c), com.documentplatform.integration.intake.CursorState.START,
                                    i -> com.documentplatform.integration.intake.IntakeAnswer.TAKEN);
            assertEquals(1, r.quarantined().size(), r.toString());
        }
        String area = com.documentplatform.integration.config.WatchSource.PROBLEM;
        String problem = new String(folder.read(area + "/" + folder.entries(area).get(0),
                com.documentplatform.integration.config.WatchSource.PROBLEM_FILE),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(problem.contains(refusedDrop), "problem.json in the customer's folder keeps the real name");

        // platform-core's own lines (the JDK's test HTTP server logs request paths at FINE).
        List<String> log = logged().stream().filter(l -> l.contains(" com.documentplatform.")).toList();
        for (String line : log) {
            for (String p : List.of(refusedDrop, incompleteDrop, infectedDrop, item, "Lee-planted", BEARER)) {
                assertFalse(line.contains(p), "found " + p + " in: " + line);
            }
        }
        for (String[] expected : new String[][]{{"drop=", refusedDrop, "_problem"}, {"drop=", incompleteDrop,
                "has no trigger"}, {"drop=", infectedDrop, "infected"}, {"item=", item, "infected"}}) {
            String ref = expected[0] + com.documentplatform.integration.intake.LogRef.of(expected[1]);
            assertTrue(ref.matches("(drop|item)=#[0-9a-f]{12}"), ref);
            assertTrue(log.stream().anyMatch(l -> l.contains(ref) && l.contains(expected[2])),
                    "no line with " + ref + " and " + expected[2] + " in " + log);
        }
    }

    @Test
    void nothingSecretFromCertificatesOrCallbacks(@TempDir Path dir) throws Exception {
        String p12Password = "P12PW-planted-4d4d";
        String keystorePassword = "KSPW-planted-5e5e";
        String signingSecret = "SIGN-planted-6f6f";
        TestCertificates.Ca ca = TestCertificates.ca(dir, "logs-ca");
        TestCertificates.Credential good = TestCertificates.credential(dir, "logs-good", -1, 60);
        TestCertificates.Credential next = TestCertificates.credential(dir, "logs-next", -1, 90);
        TestCertificates.Credential other = TestCertificates.credential(dir, "logs-other", -1, 60);
        TestCertificates.Server server = TestCertificates.serverTrusting(dir, ca, "logs-server", "dns:localhost",
                good.certificate(), next.certificate());
        List<String> outputs = new ArrayList<>();
        com.documentplatform.crypto.testkit.InMemoryKeyService keys =
                new com.documentplatform.crypto.testkit.InMemoryKeyService().createKeysOnDemand();
        com.documentplatform.integration.testkit.InMemoryCredentialStore store =
                new com.documentplatform.integration.testkit.InMemoryCredentialStore();
        fx.secrets.put(SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/ks"), keystorePassword).put(SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/sign"), signingSecret);
        com.documentplatform.integration.credential.IntegrationCredentials credentials =
                new com.documentplatform.integration.credential.IntegrationCredentials(store,
                        new com.documentplatform.crypto.envelope.ValueCipher(keys),
                        e -> com.documentplatform.crypto.CustomerId.fromTenantId("TEN-LOGS-1"), fx.secrets,
                        IntegrationAudit.NONE, Clock.systemUTC());
        try (MockDestination mtls = MockDestination.startMutualTls(server.context());
             com.documentplatform.integration.engine.CallEngine engine =
                     new com.documentplatform.integration.engine.CallEngine(
                             new com.documentplatform.outbound.Outbound(fx.installation.withPrivateCa(ca.pem())),
                             fx.secrets, credentials, Clock.systemUTC())) {
            mtls.expectNone();
            com.documentplatform.integration.store.StoredIntegration s = Fx.stored(Fx.twoStep(mtls.addressByName(),
                    SignIn.MutualTls.alone()));
            for (byte[] bad : List.of("junk".getBytes(), TestCertificates.pkcs12(good, p12Password))) {
                outputs.add(assertThrows(RuntimeException.class, () -> credentials.upload(s, bad,
                        "wrong-planted".toCharArray(), null, "admin")).getMessage());
            }
            outputs.add(engine.attempt(s, Fx.delivery(1, TEXT)).toString()); // no certificate yet
            outputs.add(credentials.upload(s, TestCertificates.pkcs12(other, p12Password), p12Password.toCharArray(),
                    null, "admin").toString());
            outputs.add(engine.attempt(s, Fx.delivery(1, TEXT)).toString()); // refused by the destination
            outputs.add(credentials.upload(s, TestCertificates.pkcs12(good, p12Password), p12Password.toCharArray(),
                    null, "admin").toString());
            outputs.add(new TestAction(engine, IntegrationAudit.NONE).runWithNext(s, "admin").toString());
            outputs.add(credentials.switchToNext(s.id(), "admin").toString());
            outputs.add(engine.attempt(s, Fx.delivery(1, TEXT)).toString());
            outputs.add(engine.health(s).toString());
            keys.down(true);
            outputs.add(engine.attempt(s, Fx.delivery(1, TEXT)).toString());
            keys.down(false);
            Path ks = TestCertificates.keystore(next, dir.resolve("logs-ks.p12"), "client", keystorePassword);
            com.documentplatform.integration.store.StoredIntegration s2 = Fx.stored(Fx.twoStep(mtls.addressByName(),
                    SignIn.MutualTls.alone()));
            outputs.add(credentials.useKeystore(s2, new com.documentplatform.integration.credential.KeystoreRef(
                    ks.toString(), SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/ks"), "client"), null, "admin").toString());
            outputs.add(engine.attempt(s2, Fx.delivery(1, TEXT)).toString());
            outputs.add(credentials.list(s.id()).toString() + credentials.history(s.id()) + store.rows());
            outputs.add(new com.documentplatform.integration.credential.CredentialExpiry(store)
                    .check(java.time.Instant.now().plus(Duration.ofDays(365))).toString());
            String records = mtls.received() + " " + mtls.clientCertificates();
            for (TestCertificates.Credential c : List.of(good, next, other)) {
                assertFalse(records.contains(c.keyBase64().substring(0, 40)), "the mock recorded a key");
            }
        }
        // Callbacks: sent, refused, given up; the verifier's results.
        IntegrationConfig cb = IntegrationConfig.callback(Fx.ENTITY, Fx.PRODUCT, "Hooks",
                new com.documentplatform.integration.config.Endpoint(fx.mock.address(), false), new SignIn.None(),
                SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/sign"));
        com.documentplatform.integration.store.StoredIntegration stored = Fx.stored(cb);
        fx.mock.reset();
        fx.mock.expectNone();
        outputs.add(fx.engine.sendCallback(stored, java.util.UUID.randomUUID(), "{\"note\":\"" + VALUE + "\"}")
                .toString());
        fx.mock.refuse(400, "MOCK-CB-1");
        outputs.add(fx.engine.sendCallback(stored, java.util.UUID.randomUUID(), "{}").toString());
        // xc-never-leak-81: the outbox's entries (pending, given up) never show their body.
        InMemoryIntegrationStore cbStore = new InMemoryIntegrationStore();
        cbStore.create(cb, new com.documentplatform.integration.store.ConfigChange(cb.id(), cb.entity(), cb.product(),
                1, "test", java.time.Instant.now(), com.documentplatform.integration.config.ConfigJson.fields(cb),
                List.of()));
        com.documentplatform.integration.testkit.InMemoryCallbackOutbox outbox =
                new com.documentplatform.integration.testkit.InMemoryCallbackOutbox();
        com.documentplatform.integration.callback.Callbacks callbacks =
                new com.documentplatform.integration.callback.Callbacks(outbox, cbStore, fx.engine, Clock.systemUTC());
        java.util.UUID pending = callbacks.enqueue(cb.id(), "{\"note\":\"" + VALUE + "\"}");
        outputs.add(callbacks.find(pending).orElseThrow().toString());
        callbacks.deliverDue(10); // refused by the receiver: given up
        outputs.add(callbacks.find(pending).orElseThrow().toString());
        outputs.add(callbacks.gaveUp(cb.entity(), cb.product(), 10).toString());
        assertTrue(callbacks.find(pending).orElseThrow().body().contains(VALUE), "the entry holds the body");
        outputs.add(com.documentplatform.integration.callback.CallbackVerifier.verify(
                java.util.Map.of(com.documentplatform.integration.callback.CallbackSignature.SIGNATURE_HEADER,
                        "t=1,v1=00", com.documentplatform.integration.callback.CallbackSignature.ID_HEADER, "x")::get,
                VALUE.getBytes(), List.of(signingSecret),
                com.documentplatform.integration.callback.CallbackVerifier.SeenIds.inMemory(10),
                java.time.Instant.now()).toString());

        List<String> all = new ArrayList<>(outputs);
        synchronized (lines) {
            all.addAll(lines);
        }
        assertTrue(logged().stream().anyMatch(l -> l.contains("client certificate change")), "certificate changes log");
        List<String> planted = new ArrayList<>(List.of(p12Password, keystorePassword, signingSecret, "wrong-planted",
                TEXT, VALUE));
        for (TestCertificates.Credential c : List.of(good, next, other)) planted.add(c.keyBase64().substring(0, 40));
        for (String line : all) {
            for (String p : planted) {
                assertFalse(line.contains(p), "found " + p + " in: " + line);
            }
        }
        // The mock records header values for callbacks only as names it checks; never the signing secret.
        assertFalse((fx.mock.received().toString()).contains(signingSecret));
    }
}
