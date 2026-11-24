package com.documentplatform.monitoring.spring;

import com.documentplatform.errors.ReferenceId;
import com.documentplatform.identity.inmemory.InMemoryIdentityStore;
import com.documentplatform.identity.jdbc.IdentitySchemaException;
import com.documentplatform.identity.store.LoginConnectionStore;
import com.documentplatform.integration.config.CallStep;
import com.documentplatform.integration.config.Endpoint;
import com.documentplatform.integration.config.HealthCheck;
import com.documentplatform.integration.config.HttpMethod;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.spring.IntegrationAutoConfiguration;
import com.documentplatform.integration.store.ConfigChange;
import com.documentplatform.integration.store.IntegrationAudit;
import com.documentplatform.integration.store.IntegrationStore;
import com.documentplatform.integration.testkit.InMemoryIntegrationStore;
import com.documentplatform.integration.testkit.MockDestination;
import com.documentplatform.monitoring.CheckResult;
import com.documentplatform.monitoring.CheckState;
import com.documentplatform.monitoring.DependencyCheck;
import com.documentplatform.monitoring.DependencyStatus;
import com.documentplatform.monitoring.Verdict;
import com.documentplatform.monitoring.MonitoringCode;
import com.documentplatform.monitoring.MonitoringEvent;
import com.documentplatform.monitoring.StartupChecks;
import com.documentplatform.monitoring.testkit.MockHttpService;
import com.documentplatform.monitoring.testkit.SwitchableCheck;
import com.documentplatform.notifications.inmemory.InMemorySecretResolver;
import com.documentplatform.notifications.secret.SecretRef;
import com.documentplatform.notifications.secret.SecretResolver;
import com.documentplatform.outbound.spring.OutboundAutoConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** P032 B2: the Spring wiring of the checks, health, start-up refusal, metrics and the request filter. */
class MonitoringWiringTest {

    static ApplicationContextRunner runner(String... properties) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MonitoringAutoConfiguration.class))
                .withPropertyValues(properties);
    }

    static Throwable root(Throwable t) {
        while (t != null && !(t instanceof StartupChecks.Refused) && t.getCause() != null) t = t.getCause();
        return t;
    }

    /**
     * monitoring-30 (P039): the key service check comes from the app's KeyService bean, and is required at start-up
     * only with customer-keys-required=true.
     */
    @Test
    void theKeyServiceCheckComesFromTheBeanAndIsRequiredOnlyWhenTheProductSaysSo() {
        com.documentplatform.crypto.testkit.InMemoryKeyService down =
                new com.documentplatform.crypto.testkit.InMemoryKeyService().down(true);
        runner("platform.monitoring.customer-keys-required=true")
                .withBean(com.documentplatform.crypto.KeyService.class, () -> down)
                .run(ctx -> {
                    StartupChecks.Refused r = assertInstanceOf(StartupChecks.Refused.class,
                            root(ctx.getStartupFailure()));
                    assertTrue(r.getMessage().contains("CRYPTO_KEY_SERVICE_UNREACHABLE"), r.getMessage());
                });
        runner("platform.monitoring.customer-keys-required=false")
                .withBean(com.documentplatform.crypto.KeyService.class, () -> down)
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure(), "not required: the app starts with the key service down");
                    CheckResult keys = ctx.getBean(DependencyStatus.class).runAll().stream()
                            .filter(x -> x.code().code().startsWith("CRYPTO_")).findFirst().orElseThrow();
                    assertEquals(CheckState.DOWN, keys.state());
                    assertFalse(keys.requiredAtStartup());
                });
        runner().run(ctx -> assertTrue(ctx.getBean(DependencyStatus.class).runAll().stream()
                .noneMatch(x -> x.code().code().startsWith("CRYPTO_")), "no KeyService bean: no check"));
    }

    /** The mail check of an app whose notifications settings are these (the sender's own checked settings). */
    private static CheckResult mailCheck(Map<String, String> mail) {
        Map<String, String> props = new java.util.LinkedHashMap<>();
        mail.forEach((k, v) -> props.put("platform.notifications.mail." + k, v));
        var p = new org.springframework.boot.context.properties.bind.Binder(
                new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(props))
                .bindOrCreate("platform.notifications",
                        com.documentplatform.notifications.spring.NotificationsProperties.class);
        var config = com.documentplatform.notifications.spring.internal.NotificationsConfig.from(p);
        CheckResult[] out = new CheckResult[1];
        runner(props.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toArray(String[]::new))
                .withBean(com.documentplatform.notifications.spring.internal.NotificationsConfig.class, () -> config)
                .run(ctx -> out[0] = ctx.getBean(DependencyStatus.class).runAll().stream()
                        .filter(r -> r.name().equals("channel-mail")).findFirst().orElseThrow());
        return out[0];
    }

    /**
     * monitoring-34 (P039): the mail check uses the sender's own settings: the port from the security when none is
     * set, and under Microsoft 365 the sender mailbox as the from-address (no false DOWN).
     */
    @Test
    void theMailCheckUsesTheSendersOwnSettings() throws Exception {
        try (java.net.ServerSocket smtp = new java.net.ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress())) {
            CheckResult m365 = mailCheck(Map.of("host", "127.0.0.1", "port", String.valueOf(smtp.getLocalPort()),
                    "auth", "OAUTH2_MICROSOFT", "oauth.directory-id", "11111111-2222-3333-4444-555555555555",
                    "oauth.client-id", "66666666-7777-8888-9999-000000000000", "oauth.credential-type", "SECRET",
                    "oauth.credential-secret", "app/m365-secret", "oauth.sender-mailbox", "notifications@corp.example"));
            assertEquals(CheckState.WORKING, m365.state(), m365.cause());
        }
        CheckResult tls = mailCheck(Map.of("host", "mail.invalid", "security", "TLS",
                "from-address", "notifications@corp.example"));
        assertEquals(MonitoringCode.MON_CHANNEL_UNREACHABLE, tls.code());
        assertTrue(tls.cause().contains("mail.invalid:465"), "TLS without a port is 465, as the sender: " + tls.cause());
    }

    /** monitoring-28 (P039): platform.monitoring.every is how often a check runs that does not say so itself. */
    @Test
    void theEverySettingIsTheIntervalOfChecksThatDoNotChooseTheirOwn() {
        java.util.concurrent.atomic.AtomicInteger plain = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger own = new java.util.concurrent.atomic.AtomicInteger();
        java.time.Instant[] now = {java.time.Instant.parse("2026-11-23T10:00:00Z")};
        java.time.Clock clock = new java.time.Clock() {
            @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            @Override public java.time.Clock withZone(java.time.ZoneId z) { return this; }
            @Override public java.time.Instant instant() { return now[0]; }
        };
        DependencyCheck plainCheck = new DependencyCheck() {
            @Override public String name() { return "plain"; }
            @Override public Verdict check() { plain.incrementAndGet(); return Verdict.working(); }
        };
        DependencyCheck ownCheck = new DependencyCheck() {
            @Override public String name() { return "own"; }
            @Override public java.time.Duration every() { return java.time.Duration.ofSeconds(30); }
            @Override public Verdict check() { own.incrementAndGet(); return Verdict.working(); }
        };
        runner("platform.monitoring.every=10m", "platform.monitoring.startup-checks=false",
                "platform.monitoring.tick=1h")
                .withBean(java.time.Clock.class, () -> clock)
                .withBean("plainCheck", DependencyCheck.class, () -> plainCheck)
                .withBean("ownCheck", DependencyCheck.class, () -> ownCheck)
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    DependencyStatus status = ctx.getBean(DependencyStatus.class);
                    // The scheduler's first round runs at once; with tick=1h the test drives the next ones.
                    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
                    while ((plain.get() == 0 || own.get() == 0) && System.nanoTime() < deadline) Thread.onSpinWait();
                    assertEquals(1, plain.get());
                    now[0] = now[0].plus(java.time.Duration.ofMinutes(2));
                    status.runDue();
                    assertEquals(1, plain.get(), "every=10m: not due again after 2 minutes");
                    assertEquals(2, own.get(), "a check's own interval (30 s) wins");
                    now[0] = now[0].plus(java.time.Duration.ofMinutes(9));
                    status.runDue();
                    assertEquals(2, plain.get(), "due again after 10 minutes");
                });
    }

    /** xc-catalogue-12 (P037): bad settings stop the start with MON_SETTINGS_INVALID, every problem listed. */
    @Test
    void badSettingsAreRefusedWithTheirCodeAndEveryProblemListed() {
        runner("platform.monitoring.folders.export.path=/srv/export", "platform.monitoring.folders.export.warn-at=96",
                "platform.monitoring.folders.export.down-at=101", "platform.monitoring.services.ocr.url=ftp://ocr.internal/",
                "platform.monitoring.services.ocr.expected-status=42", "platform.monitoring.every=0s")
                .run(ctx -> {
                    Throwable t = ctx.getStartupFailure();
                    while (t != null && !(t instanceof MonitoringConfigException)) t = t.getCause();
                    MonitoringConfigException e = assertInstanceOf(MonitoringConfigException.class, t);
                    assertEquals(MonitoringSettingsCode.MON_SETTINGS_INVALID, e.platformCode());
                    assertTrue(e.getMessage().startsWith("MON_SETTINGS_INVALID: "), e.getMessage());
                    assertEquals(List.of("platform.monitoring.every must be more than zero (for example 60s)",
                            "platform.monitoring.folders.export.down-at must be 1 to 100 (per cent)",
                            "platform.monitoring.services.ocr.url must be an http or https address",
                            "platform.monitoring.services.ocr.expected-status must be an HTTP status (100 to 599)"),
                            e.problems());
                    assertNotNull(new MonitoringFailureAnalyzer().analyze(ctx.getStartupFailure()));
                });
        runner("platform.monitoring.folders.export.path=/srv/export", "platform.monitoring.folders.export.warn-at=96",
                "platform.monitoring.folders.export.down-at=95").run(ctx -> assertTrue(
                String.valueOf(ctx.getStartupFailure()).contains("warn-at must not be above down-at")));
    }

    @Test
    void theAppRefusesToStartWhileARequiredCheckIsDown() {
        runner().withBean("ocrCheck", DependencyCheck.class, () -> new SwitchableCheck("ocr", true).down())
                .run(ctx -> {
                    StartupChecks.Refused r = assertInstanceOf(StartupChecks.Refused.class, root(ctx.getStartupFailure()));
                    assertTrue(r.getMessage().startsWith("The app cannot start: 1 required dependency is down.\n"
                            + "  ocr: MON_HTTP_UNREACHABLE: The service ocr at http://127.0.0.1:1/ cannot be reached. "
                            + "Fix: Check the service is running"), r.getMessage());
                    assertNotNull(new MonitoringFailureAnalyzer().analyze(ctx.getStartupFailure()));
                });
        runner("platform.monitoring.startup-checks=false")
                .withBean("ocrCheck", DependencyCheck.class, () -> new SwitchableCheck("ocr", true).down())
                .run(ctx -> assertNull(ctx.getStartupFailure(), "the refusal can be switched off"));
    }

    @Test
    void readinessHoldsOnlyRequiredChecksAndTheListShowsAll() {
        SwitchableCheck required = new SwitchableCheck("database-ish", true);
        SwitchableCheck optional = new SwitchableCheck("ocr", false).down();
        List<MonitoringEvent> events = new CopyOnWriteArrayList<>();
        runner().withBean("a", DependencyCheck.class, () -> required).withBean("b", DependencyCheck.class, () -> optional)
                .withBean(MonitoringListener.class, () -> events::add)
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure(), () -> String.valueOf(ctx.getStartupFailure()));
                    DependencyStatus status = ctx.getBean(DependencyStatus.class);
                    status.runAll();
                    HealthIndicator readiness = ctx.getBean("readinessStateHealthIndicator", HealthIndicator.class);
                    HealthIndicator all = ctx.getBean("dependenciesHealthIndicator", HealthIndicator.class);
                    assertEquals(Status.UP, readiness.health().getStatus(), "an optional check down: still ready");
                    assertEquals(Status.UP, all.health().getStatus(), "the list is informational");
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> ocr = (java.util.Map<String, Object>) all.health().getDetails().get("ocr");
                    assertEquals("DOWN", ocr.get("state"));
                    assertEquals("MON_HTTP_UNREACHABLE", ocr.get("code"));
                    assertNotNull(ocr.get("cause"));
                    required.down();
                    status.runAll();
                    assertEquals(Status.OUT_OF_SERVICE, readiness.health().getStatus());
                    assertTrue(events.stream().anyMatch(e -> e.kind() == MonitoringEvent.Kind.BECAME_DOWN
                            && e.now().name().equals("database-ish")), events.toString());
                });
    }

    @Test
    void integrationsAreInTheListButNeverInReadiness() {
        try (MockDestination mock = MockDestination.start()) {
            InMemoryIntegrationStore store = new InMemoryIntegrationStore();
            IntegrationConfig c = IntegrationConfig.apiPush(UUID.fromString("5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f"), "PRODUCT_A", "Destination",
                            new Endpoint(mock.address(), true), new SignIn.BearerFixed(SecretRef.of("int/5f0c8a52-2f7e-4c1b-9d6e-0a1b2c3d4e5f/t")))
                    .withSteps(List.of(CallStep.of("send", HttpMethod.POST, "/api/notice")))
                    .withHealth(HealthCheck.get(MockDestination.HEALTH_PATH, false));
            store.create(c, new ConfigChange(c.id(), c.entity(), c.product(), 1, "test", Instant.now(), List.of(),
                    List.of()));
            mock.healthFailing(true);
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(OutboundAutoConfiguration.class,
                            IntegrationAutoConfiguration.class, MonitoringAutoConfiguration.class))
                    .withPropertyValues("platform.outbound.installation=ON_PREMISES",
                            "platform.outbound.allow-internal-addresses=true")
                    .withBean(SecretResolver.class, InMemorySecretResolver::new)
                    .withBean(IntegrationAudit.class, () -> IntegrationAudit.NONE)
                    .withBean(IntegrationStore.class, () -> store)
                    .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                    .run(ctx -> {
                        assertNull(ctx.getStartupFailure(), () -> String.valueOf(ctx.getStartupFailure()));
                        assertFalse(ctx.containsBean("integrationsHealthContributor"),
                                "replaced by entries in the dependencies list");
                        DependencyStatus status = ctx.getBean(DependencyStatus.class);
                        List<CheckResult> results = status.runAll();
                        CheckResult integration = results.stream().filter(r -> r.group().equals("integrations"))
                                .findFirst().orElseThrow();
                        assertEquals(CheckState.DOWN, integration.state());
                        assertFalse(integration.requiredAtStartup());
                        assertEquals(Status.UP, ctx.getBean("readinessStateHealthIndicator", HealthIndicator.class)
                                .health().getStatus(), "another system down never makes this app unready");
                        MeterRegistry m = ctx.getBean(MeterRegistry.class);
                        assertEquals(2.0, m.get(MonitoringMetrics.STATE).tag("check", integration.name()).gauge().value());
                        assertNotNull(m.get(MonitoringMetrics.DURATION).tag("check", integration.name()).timer());
                    });
        }
    }

    @Test
    void theBuiltInChecksComeFromTheAppsBeansAndSettings(@TempDir Path dir) {
        try (PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine"); MockHttpService ocr = MockHttpService.start()) {
            pg.start();
            PGSimpleDataSource ds = new PGSimpleDataSource();
            ds.setURL(pg.getJdbcUrl());
            ds.setUser(pg.getUsername());
            ds.setPassword(pg.getPassword());
            InMemorySecretResolver secrets = new InMemorySecretResolver();
            secrets.put(SecretRef.of("app/db-password"), "x");
            ApplicationContextRunner r = runner("platform.monitoring.secrets=app/db-password,app/missing",
                    "platform.monitoring.folders.export.path=" + dir.toAbsolutePath(),
                    "platform.monitoring.services.ocr.url=" + ocr.health(),
                    "platform.monitoring.services.ocr.required=true")
                    .withBean(DataSource.class, () -> ds)
                    .withBean(LoginConnectionStore.class, InMemoryIdentityStore::new)
                    .withBean(SecretResolver.class, () -> secrets);
            // The identity schema is in use (a LoginConnectionStore bean) but not created: the app does not start.
            r.run(ctx -> {
                StartupChecks.Refused refused = assertInstanceOf(StartupChecks.Refused.class, root(ctx.getStartupFailure()));
                assertTrue(refused.getMessage().contains("database: IDN_TABLES_MISSING"), refused.getMessage());
            });
            r.withPropertyValues("platform.monitoring.startup-checks=false").run(ctx -> {
                DependencyStatus status = ctx.getBean(DependencyStatus.class);
                List<String> names = status.checks().stream().map(DependencyCheck::name).toList();
                assertEquals(List.of("database", "secret-store", "folder-export", "service-ocr"), names);
                java.util.Map<String, CheckResult> by = new java.util.HashMap<>();
                status.runAll().forEach(x -> by.put(x.name(), x));
                assertEquals(IdentitySchemaException.Problem.IDN_TABLES_MISSING, by.get("database").code());
                assertEquals(MonitoringCode.MON_SECRET_MISSING, by.get("secret-store").code());
                assertEquals(CheckState.WORKING, by.get("folder-export").state());
                assertEquals(CheckState.WORKING, by.get("service-ocr").state());
                assertTrue(by.get("service-ocr").requiredAtStartup());

                ByteArrayOutputStream zip = new ByteArrayOutputStream();
                var written = ctx.getBean(DiagnosticBundles.class).write(zip);
                assertTrue(written.entries().contains("checks.txt"));
                assertTrue(zip.size() > 0);
            });
        }
    }

    @Test
    void theRequestFilterMakesTheReferenceNeverTakingTheCallersAndSetsItForTheRequest() throws Exception {
        RequestReferenceFilter f = new RequestReferenceFilter();
        AtomicReference<String> during = new AtomicReference<>();
        AtomicReference<String> recorded = new AtomicReference<>();
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Request-Id", "abc-123");
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest q, jakarta.servlet.http.HttpServletResponse s) {
                during.set(ReferenceId.current() + "|" + org.slf4j.MDC.get("ref") + "|" + org.slf4j.MDC.get("requestId"));
                // error-codes-27 (P037): a failure recorded in the request carries the request's reference.
                recorded.set(com.documentplatform.errors.FailureLog.record(MonitoringCode.MON_CHECK_FAILED,
                        java.util.Map.of("check", "ocr")));
            }
        }));
        // P045, SEC-21: the caller does not choose the reference; its own ID is kept apart.
        String own = res.getHeader("X-Request-Id");
        assertTrue(ReferenceId.isReference(own), own);
        assertEquals(own + "|" + own + "|abc-123", during.get());
        assertEquals(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 10, RequestReferenceFilter.class
                .getAnnotation(org.springframework.core.annotation.Order.class).value(), "before Spring Security");
        assertEquals(own, recorded.get(), "FailureLog.record uses the request's reference");
        assertEquals(own, com.documentplatform.errors.FailureLog.recent(1).get(0).reference());
        assertNull(ReferenceId.current(), "cleared after the request");

        MockHttpServletRequest bad = new MockHttpServletRequest();
        bad.addHeader("X-Request-Id", "password=hunter2 <script>");
        MockHttpServletResponse made = new MockHttpServletResponse();
        f.doFilter(bad, made, new MockFilterChain());
        assertTrue(ReferenceId.isReference(made.getHeader("X-Request-Id")), made.getHeader("X-Request-Id"));
    }
}
