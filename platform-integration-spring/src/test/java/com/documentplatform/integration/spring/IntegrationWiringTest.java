package com.documentplatform.integration.spring;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.integration.config.CallStep;
import com.documentplatform.integration.config.Endpoint;
import com.documentplatform.integration.config.FolderTarget;
import com.documentplatform.integration.config.HealthCheck;
import com.documentplatform.integration.config.HttpMethod;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.Limits;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.engine.CallEngine;
import com.documentplatform.integration.engine.Delivery;
import com.documentplatform.integration.engine.TestAction;
import com.documentplatform.integration.folder.ExportFiles;
import com.documentplatform.integration.folder.ExportInfo;
import com.documentplatform.integration.folder.FolderExport;
import com.documentplatform.integration.http.Outbound;
import com.documentplatform.integration.jdbc.IntegrationSchemaException;
import com.documentplatform.integration.jdbc.PostgresIntegrationStore;
import com.documentplatform.integration.profile.ProfileFolder;
import com.documentplatform.integration.result.AttemptResult;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.integration.safety.Installation;
import com.documentplatform.integration.store.ConfigChange;
import com.documentplatform.integration.store.IntegrationAudit;
import com.documentplatform.integration.store.IntegrationConfigs;
import com.documentplatform.integration.store.IntegrationStore;
import com.documentplatform.integration.store.StoredIntegration;
import com.documentplatform.integration.testkit.InMemoryIntegrationStore;
import com.documentplatform.integration.testkit.MockDestination;
import com.documentplatform.integration.testkit.MockFolder;
import com.documentplatform.integration.testkit.MockProxy;
import com.documentplatform.notifications.inmemory.InMemorySecretResolver;
import com.documentplatform.notifications.secret.SecretRef;
import com.documentplatform.notifications.secret.SecretResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.CompositeHealthContributor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.NamedContributor;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class IntegrationWiringTest {

    static final String SLOT = "ENT-0001";
    static final UUID ENTITY = EntityUuid.fromSlotId(SLOT);

    /** The app's audit: records what it gets. */
    static final class Audit implements IntegrationAudit {
        final List<ConfigChange> changes = new ArrayList<>();
        final List<AttemptResult> tests = new ArrayList<>();

        @Override
        public void changed(ConfigChange change) {
            changes.add(change);
        }

        @Override
        public void tested(AttemptResult result, String by) {
            tests.add(result);
        }
    }

    private static ApplicationContextRunner runner(String... properties) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IntegrationAutoConfiguration.class))
                .withPropertyValues(properties);
    }

    /** With the app's beans and an in-memory store (the Postgres one is tested below). */
    private static ApplicationContextRunner app(InMemorySecretResolver secrets, Audit audit, String... properties) {
        return runner(properties)
                .withBean(SecretResolver.class, () -> secrets)
                .withBean(IntegrationAudit.class, () -> audit)
                .withBean(IntegrationStore.class, InMemoryIntegrationStore::new);
    }

    private static Throwable root(Throwable t) {
        while (t != null && !(t instanceof IntegrationConfigException) && !(t instanceof IntegrationSchemaException)
                && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    @Test
    void withoutTheAppsBeansStartupRefusesNamingEachOne() {
        runner("platform.integration.installation=ON_PREMISES")
                .withBean(IntegrationStore.class, InMemoryIntegrationStore::new)
                .run(ctx -> {
                    IntegrationConfigException e = assertInstanceOf(IntegrationConfigException.class,
                            root(ctx.getStartupFailure()));
                    assertEquals(2, e.problems().size(), e.getMessage());
                    assertTrue(e.getMessage().contains("SecretResolver bean is missing"));
                    assertTrue(e.getMessage().contains("IntegrationAudit bean is missing"));
                    assertNotNull(new IntegrationFailureAnalyzer().analyze(ctx.getStartupFailure()));
                });
        runner("platform.integration.installation=ON_PREMISES")
                .withBean(SecretResolver.class, InMemorySecretResolver::new)
                .withBean(IntegrationStore.class, InMemoryIntegrationStore::new)
                .run(ctx -> {
                    IntegrationConfigException e = assertInstanceOf(IntegrationConfigException.class,
                            root(ctx.getStartupFailure()));
                    assertEquals(List.of("the app's IntegrationAudit bean is missing (every configuration change and "
                            + "Test action goes to the app's audit)"), e.problems());
                });
        // Without a DataSource or an IntegrationStore.
        runner("platform.integration.installation=ON_PREMISES")
                .withBean(SecretResolver.class, InMemorySecretResolver::new)
                .withBean(IntegrationAudit.class, () -> IntegrationAudit.NONE)
                .run(ctx -> assertTrue(root(ctx.getStartupFailure()).getMessage().contains("needs the app's DataSource")));
    }

    @Test
    void withTheAppsBeansEverythingIsWiredAndAFolderExportRuns(@TempDir Path dir) {
        MockFolder folder = MockFolder.in(dir);
        Audit audit = new Audit();
        app(new InMemorySecretResolver(), audit, "platform.integration.installation=ON_PREMISES",
                "platform.integration.limits.read-timeout=30s", "platform.integration.limits.max-file-size=10MB")
                .withBean(ExportFiles.class, () -> folder)
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure(), () -> String.valueOf(ctx.getStartupFailure()));
                    for (Class<?> c : List.of(Installation.class, Outbound.class, CallEngine.class,
                            IntegrationConfigs.class, FolderExport.class, TestAction.class, IntegrationSettings.class)) {
                        assertNotNull(ctx.getBean(c), c.getSimpleName());
                    }
                    assertFalse(ctx.containsBean("integrationProfileFolder"), "no profile folder set");
                    Installation inst = ctx.getBean(Installation.class);
                    assertFalse(inst.hosted());
                    assertNull(inst.proxy());
                    Limits defaults = ctx.getBean("integrationDefaultLimits", Limits.class);
                    assertEquals(java.time.Duration.ofSeconds(30), defaults.readTimeout());
                    assertEquals(10L * 1024 * 1024, defaults.maxFileBytes());

                    // The minimal example: save a folder export (audited), send a package, run its Test action.
                    IntegrationConfigs configs = ctx.getBean(IntegrationConfigs.class);
                    StoredIntegration saved = configs.create(IntegrationConfig.folderExport(ENTITY, "PRODUCT_A",
                            "Share export", FolderTarget.of(folder.folder())).withLimits(defaults), "it-admin");
                    Delivery d = Delivery.of("PKG-1", List.of(Delivery.Document.of("doc-1", "statement.pdf",
                            "application/pdf", "%PDF-1.4 test".getBytes(StandardCharsets.UTF_8))), Map.of());
                    AttemptResult r = ctx.getBean(FolderExport.class).attempt(saved, d,
                            new ExportInfo(SLOT, "{\"caseRef\":\"C-1\"}"));
                    assertEquals(Outcome.SENT, r.outcome(), r.toString());
                    assertEquals(List.of("manifest.json", "statement.pdf"), folder.entries("PKG-1"));
                    AttemptResult test = ctx.getBean(TestAction.class).run(saved, "it-admin");
                    assertEquals(Outcome.SENT, test.outcome());
                    assertEquals(1, audit.changes.size());
                    assertEquals(List.of(test), audit.tests);
                    assertEquals(List.of("PKG-1"), folder.entries());

                    // Health: one contributor per enabled integration, outcome and code only.
                    CompositeHealthContributor health = (CompositeHealthContributor) ctx.getBean(
                            "integrationsHealthContributor");
                    List<NamedContributor<?>> all = new ArrayList<>();
                    health.forEach(all::add);
                    assertEquals(1, all.size());
                    assertEquals(saved.id().toString(), all.get(0).getName());
                    Health h = ((HealthIndicator) all.get(0).getContributor()).health();
                    assertEquals(Status.UP, h.getStatus());
                    assertEquals(Map.of("outcome", "SENT", "code", "INT_HEALTHY"), h.getDetails());
                    folder.unreachable(true);
                    assertEquals(Status.UP, ((HealthIndicator) health.getContributor(saved.id().toString())).health()
                            .getStatus(), "kept for cache-for");
                    StoredIntegration v2 = configs.update(saved.config().withName("Share export 2"), 1, "it-admin");
                    Health down = ((HealthIndicator) health.getContributor(saved.id().toString())).health();
                    assertEquals(Status.DOWN, down.getStatus(), "a new version is checked again");
                    assertEquals(Map.of("outcome", "CANT_CONNECT", "code", "INT_FOLDER_UNREACHABLE"), down.getDetails());
                    configs.setEnabled(saved.id(), false, v2.version(), "it-admin");
                    List<NamedContributor<?>> none = new ArrayList<>();
                    health.forEach(none::add);
                    assertEquals(List.of(), none, "switched off: not checked");
                    assertNull(health.getContributor(saved.id().toString()));
                    assertNull(health.getContributor("not-an-id"));
                });
    }

    @Test
    void withTheProxyCallsGoThroughItWithItsSignIn() {
        try (MockDestination mock = MockDestination.start();
             MockProxy proxy = MockProxy.start().requireBasic("svc-app", "proxy-pass")) {
            InMemorySecretResolver secrets = new InMemorySecretResolver().put(SecretRef.of("int/proxy"), "proxy-pass")
                    .put(SecretRef.of("int/t"), "tok-1");
            app(secrets, new Audit(), "platform.integration.installation=ON_PREMISES",
                    "platform.integration.allow-internal-addresses=true",
                    "platform.integration.proxy.host=" + proxy.host(),
                    "platform.integration.proxy.port=" + proxy.port(),
                    "platform.integration.proxy.bypass=.corp.example,files01",
                    "platform.integration.proxy.user=svc-app",
                    "platform.integration.proxy.password-secret=int/proxy")
                    .run(ctx -> {
                        assertNull(ctx.getStartupFailure(), () -> String.valueOf(ctx.getStartupFailure()));
                        Installation inst = ctx.getBean(Installation.class);
                        assertEquals(proxy.port(), inst.proxy().port());
                        assertTrue(inst.proxy().signIn());
                        assertTrue(inst.proxy().bypasses("a.corp.example"));
                        IntegrationConfig c = IntegrationConfig.apiPush(ENTITY, "PRODUCT_A", "API",
                                        new Endpoint(mock.addressByName(), true),
                                        new SignIn.BearerFixed(SecretRef.of("int/t")))
                                .withSteps(List.of(CallStep.of("send", HttpMethod.POST, "/api/notice")
                                        .withJsonBody("{\"packageId\":\"{package.id}\"}")))
                                .withHealth(HealthCheck.get(MockDestination.HEALTH_PATH, false));
                        StoredIntegration s = ctx.getBean(IntegrationConfigs.class).create(c, "it-admin");
                        AttemptResult r = ctx.getBean(CallEngine.class).attempt(s, Delivery.of("PKG-2", List.of(),
                                Map.of()));
                        assertEquals(Outcome.SENT, r.outcome(), r.toString());
                        assertEquals(1, proxy.signInsAccepted());
                        secrets.put(SecretRef.of("int/proxy"), "rotated-wrong");
                        Health h = ((HealthIndicator) ((CompositeHealthContributor) ctx.getBean(
                                "integrationsHealthContributor")).getContributor(s.id().toString())).health();
                        assertEquals(Status.DOWN, h.getStatus());
                        assertEquals(IntegrationCode.INT_PROXY_SIGN_IN_REFUSED.name(), h.getDetails().get("code"));
                    });
        }
    }

    @Test
    void badSettingsStopTheAppWithEveryProblemListed(@TempDir Path dir) throws Exception {
        Path notPem = Files.writeString(dir.resolve("ca.pem"), "not a certificate");
        app(new InMemorySecretResolver(), new Audit(),
                "platform.integration.allow-internal-addresses=true",
                "platform.integration.private-ca-file=relative/ca.pem",
                "platform.integration.proxy.host=proxy.corp.example",
                "platform.integration.proxy.user=svc",
                "platform.integration.limits.max-documents=0",
                "platform.integration.profile-folder=" + dir.resolve("missing").toAbsolutePath(),
                "platform.integration.health.cache-for=1s")
                .run(ctx -> {
                    IntegrationConfigException e = assertInstanceOf(IntegrationConfigException.class,
                            root(ctx.getStartupFailure()));
                    String m = e.getMessage();
                    assertTrue(m.contains("installation is required: ON_PREMISES or HOSTED"), m);
                    assertTrue(m.contains("private-ca-file must be an absolute path"), m);
                    assertTrue(m.contains("proxy.port is required"), m);
                    assertTrue(m.contains("limits: Most documents must be between 1 and 100000"), m);
                    assertTrue(m.contains("profile-folder is not a folder"), m);
                    assertTrue(m.contains("health.cache-for must be 5 seconds to 1 hour"), m);
                    assertEquals(6, e.problems().size(), m);
                });
        app(new InMemorySecretResolver(), new Audit(), "platform.integration.installation=HOSTED",
                "platform.integration.allow-internal-addresses=true", "platform.integration.allow-sign-in-none=true",
                "platform.integration.private-ca-file=" + notPem.toAbsolutePath(),
                "platform.integration.proxy.host=proxy.corp.example", "platform.integration.proxy.port=3128",
                "platform.integration.proxy.user=svc",
                "platform.integration.proxy.password-secret=https://pasted-password-instead")
                .run(ctx -> {
                    IntegrationConfigException e = assertInstanceOf(IntegrationConfigException.class,
                            root(ctx.getStartupFailure()));
                    String m = e.getMessage();
                    assertTrue(m.contains("allow-internal-addresses is never allowed on the hosted service"), m);
                    assertTrue(m.contains("allow-sign-in-none is never allowed on the hosted service"), m);
                    assertTrue(m.contains("private-ca-file is not a PEM bundle"), m);
                    assertTrue(m.contains("password-secret is not a secret reference"), m);
                    assertFalse(m.contains("pasted-password-instead"), "a value is never repeated");
                    assertEquals(4, e.problems().size(), m);
                });
        app(new InMemorySecretResolver(), new Audit(), "platform.integration.installation=ON_PREMISES",
                "platform.integration.proxy.host=proxy.corp.example", "platform.integration.proxy.port=3128",
                "platform.integration.proxy.user=svc")
                .run(ctx -> assertTrue(root(ctx.getStartupFailure()).getMessage()
                        .contains("user and password-secret go together")));
        runner("platform.integration.enabled=false").run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertFalse(ctx.containsBean("integrationCallEngine"));
        });
    }

    @Test
    void theProfileFolderAndThePrivateCa(@TempDir Path dir) throws Exception {
        Path profiles = Files.createDirectories(dir.resolve("profiles"));
        Files.writeString(profiles.resolve("example-api-v1.json"), "{}");
        Files.writeString(profiles.resolve("notes.txt"), "x");
        com.documentplatform.integration.testkit.TestCertificates.Ca ca =
                com.documentplatform.integration.testkit.TestCertificates.ca(dir, "it-ca");
        Path pem = Files.writeString(dir.resolve("private-ca.pem"), ca.pem());
        app(new InMemorySecretResolver(), new Audit(), "platform.integration.installation=ON_PREMISES",
                "platform.integration.profile-folder=" + profiles.toAbsolutePath(),
                "platform.integration.private-ca-file=" + pem.toAbsolutePath())
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure(), () -> String.valueOf(ctx.getStartupFailure()));
                    ProfileFolder f = ctx.getBean(ProfileFolder.class);
                    assertEquals(List.of("example-api-v1.json"), f.files());
                    assertFalse(f.check("example-api-v1.json").valid(), "checked when asked for: {} is no profile");
                    assertFalse(f.check("../private-ca.pem").valid());
                    assertEquals(1, ctx.getBean(Installation.class).privateCaCertificates().size());
                });
    }

    @Test
    void withoutActuatorThereIsNoHealthContributor() {
        app(new InMemorySecretResolver(), new Audit(), "platform.integration.installation=ON_PREMISES")
                .withClassLoader(new FilteredClassLoader("org.springframework.boot.actuate"))
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure(), () -> String.valueOf(ctx.getStartupFailure()));
                    assertFalse(ctx.containsBean("integrationsHealthContributor"));
                });
        app(new InMemorySecretResolver(), new Audit(), "platform.integration.installation=ON_PREMISES",
                "platform.integration.health.enabled=false")
                .run(ctx -> assertFalse(ctx.containsBean("integrationsHealthContributor")));
    }

    @Test
    void onTheAppsDataSourceWithTheDmlOnlyUser() {
        DataSource ds = Pg.migrated().appUser();
        runner("platform.integration.installation=ON_PREMISES")
                .withBean(SecretResolver.class, InMemorySecretResolver::new)
                .withBean(IntegrationAudit.class, () -> IntegrationAudit.NONE)
                .withBean(DataSource.class, () -> ds)
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure(), () -> String.valueOf(ctx.getStartupFailure()));
                    assertInstanceOf(PostgresIntegrationStore.class, ctx.getBean(IntegrationStore.class));
                    IntegrationConfigs configs = ctx.getBean(IntegrationConfigs.class);
                    StoredIntegration s = configs.create(IntegrationConfig.folderExport(ENTITY, "PRODUCT_A", "Share",
                            FolderTarget.of("/srv/exports")), "it-admin");
                    configs.create(IntegrationConfig.folderExport(ENTITY, "PRODUCT_B", "Off",
                            FolderTarget.of("/srv/exports")).withEnabled(false), "it-admin");
                    assertEquals(List.of(s.id()), configs.enabled().stream().map(StoredIntegration::id).toList());
                    // P026: certificates and callbacks on the same database, the worker for both.
                    assertInstanceOf(com.documentplatform.integration.jdbc.PostgresCredentialStore.class,
                            ctx.getBean(com.documentplatform.integration.credential.CredentialStore.class));
                    assertInstanceOf(com.documentplatform.integration.jdbc.PostgresCallbackOutbox.class,
                            ctx.getBean(com.documentplatform.integration.callback.CallbackOutbox.class));
                    assertEquals(List.of(30, 7, 1), ctx.getBean(
                            com.documentplatform.integration.credential.CredentialExpiry.class).days().reversed());
                    var callbacks = ctx.getBean(com.documentplatform.integration.callback.Callbacks.class);
                    StoredIntegration hooks = configs.create(IntegrationConfig.callback(ENTITY, "PRODUCT_A", "Hooks",
                            com.documentplatform.integration.config.Endpoint.of("https://hooks.example/cases"),
                            new com.documentplatform.integration.config.SignIn.None(),
                            com.documentplatform.notifications.secret.SecretRef.of("int/hooks")), "it-admin");
                    java.util.UUID id = callbacks.enqueue(hooks.id(), "{\"case\":\"C-1\"}");
                    ctx.getBean(IntegrationWorker.class).runOnce(); // the secret is missing: tried, kept for later
                    var entry = callbacks.find(id).orElseThrow();
                    assertEquals(1, entry.attempts());
                    assertEquals("INT_SECRET_NOT_FOUND", entry.lastCode());
                    assertEquals(com.documentplatform.integration.callback.CallbackState.PENDING, entry.state());
                    var credentials = ctx.getBean(
                            com.documentplatform.integration.credential.IntegrationCredentials.class);
                    assertTrue(credentials.list(hooks.id()).isEmpty());
                });
        // The tables missing: the app does not start (the schema step has not run).
        DataSource none = Pg.empty().appUser();
        runner("platform.integration.installation=ON_PREMISES")
                .withBean(SecretResolver.class, InMemorySecretResolver::new)
                .withBean(IntegrationAudit.class, () -> IntegrationAudit.NONE)
                .withBean(DataSource.class, () -> none)
                .run(ctx -> assertInstanceOf(IntegrationSchemaException.class, root(ctx.getStartupFailure())));
    }
}
