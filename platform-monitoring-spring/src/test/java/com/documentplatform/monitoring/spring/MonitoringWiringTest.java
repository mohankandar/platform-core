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
            IntegrationConfig c = IntegrationConfig.apiPush(UUID.randomUUID(), "PRODUCT_A", "Destination",
                            new Endpoint(mock.address(), true), new SignIn.BearerFixed(SecretRef.of("int/t")))
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
    void theRequestFilterEchoesOrMakesTheReferenceAndSetsItForTheRequest() throws Exception {
        RequestReferenceFilter f = new RequestReferenceFilter();
        AtomicReference<String> during = new AtomicReference<>();
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Request-Id", "abc-123");
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest q, jakarta.servlet.http.HttpServletResponse s) {
                during.set(ReferenceId.current() + "|" + org.slf4j.MDC.get("ref"));
            }
        }));
        assertEquals("abc-123", res.getHeader("X-Request-Id"));
        assertEquals("abc-123|abc-123", during.get());
        assertNull(ReferenceId.current(), "cleared after the request");

        MockHttpServletRequest bad = new MockHttpServletRequest();
        bad.addHeader("X-Request-Id", "password=hunter2 <script>");
        MockHttpServletResponse made = new MockHttpServletResponse();
        f.doFilter(bad, made, new MockFilterChain());
        assertTrue(ReferenceId.isReference(made.getHeader("X-Request-Id")), made.getHeader("X-Request-Id"));
    }
}
