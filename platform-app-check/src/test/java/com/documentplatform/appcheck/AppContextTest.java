package com.documentplatform.appcheck;

import com.documentplatform.crypto.KeyService;
import com.documentplatform.crypto.testkit.InMemoryKeyService;
import com.documentplatform.eraselog.Eraser;
import com.documentplatform.identity.push.PushCaller;
import com.documentplatform.identity.store.LoginConnections;
import com.documentplatform.integration.engine.CallEngine;
import com.documentplatform.integration.store.IntegrationAudit;
import com.documentplatform.licensing.ClockGuard;
import com.documentplatform.licensing.LicenceBuild;
import com.documentplatform.licensing.LicenceKind;
import com.documentplatform.licensing.LicenceService;
import com.documentplatform.licensing.LicenceVerifier;
import com.documentplatform.licensing.inmemory.TestLicences;
import com.documentplatform.licensing.jdbc.PostgresLastSeenStore;
import com.documentplatform.licensing.jdbc.PostgresLicenceStore;
import com.documentplatform.licensing.jdbc.PostgresUsageLedger;
import com.documentplatform.modelgov.ModelGate;
import com.documentplatform.monitoring.DependencyStatus;
import com.documentplatform.notifications.EventCatalogue;
import com.documentplatform.notifications.Notifier;
import com.documentplatform.notifications.Recipient;
import com.documentplatform.notifications.RecipientResolver;
import com.documentplatform.notifications.RoleId;
import com.documentplatform.notifications.secret.SecretResolver;
import com.documentplatform.outbound.Outbound;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P051 item 1: a plain Spring Boot 4.2 app (JDBC, Actuator and Security starters), its DataSource made by Boot, starts with
 * each -spring module alone (with what it needs) and with all of them, and the module's beans are there.
 */
class AppContextTest {

    @TempDir
    static Path dir;
    static final TestLicences LICENCES = new TestLicences();
    static Db db;

    /** Module → its bean. */
    static final Map<String, Class<?>> BEANS = new LinkedHashMap<>(Map.of("crypto", KeyService.class, "eraselog", Eraser.class,
            "identity", LoginConnections.class, "integration", CallEngine.class, "licensing", LicenceService.class,
            "modelgov", ModelGate.class, "monitoring", DependencyStatus.class, "notifications", Notifier.class,
            "outbound", Outbound.class));
    /** What a module's app has as well. */
    static final Map<String, List<String>> NEEDS = Map.of("integration", List.of("outbound"), "identity", List.of("outbound"));

    @BeforeAll
    static void database() {
        db = new Db().migrated();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        new LicenceService(new PostgresLicenceStore(db.owner), new LicenceVerifier(LICENCES.trustedKeys()),
                new ClockGuard(Clock.systemUTC(), new PostgresLastSeenStore(db.owner)), new PostgresUsageLedger(db.owner),
                TestLicences.PRODUCT, TestLicences.TENANT)
                .store(LICENCES.sign(LICENCES.subscription(LicenceKind.PRODUCTION, today.minusDays(1), today.plusYears(1))));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class App {

        @Bean
        KeyService keyService() {
            return new InMemoryKeyService();
        }

        @Bean
        LicenceBuild licenceBuild() {
            return LICENCES.licenceBuild();
        }

        @Bean
        SecretResolver secrets() {
            return ref -> Optional.empty();
        }

        @Bean
        IntegrationAudit integrationAudit() {
            return IntegrationAudit.NONE;
        }

        @Bean
        EventCatalogue catalogue() {
            return EventCatalogue.builder(RoleId.of("it-admin")).build();
        }

        @Bean
        RecipientResolver recipients() {
            return (tenant, entity, role) -> Set.of(new Recipient("u-1", "one@customer.example"));
        }
    }

    private ConfigurableApplicationContext start(String module) throws Exception {
        List<String> exclude = new ArrayList<>();
        if (!module.equals("all")) {
            for (String m : BEANS.keySet()) {
                if (m.equals(module) || NEEDS.getOrDefault(module, List.of()).contains(m)) continue;
                try (var s = Files.list(Path.of(".."))) {
                    for (Path p : s.filter(x -> x.getFileName().toString().endsWith("-spring")).toList()) {
                        Path imports = p.resolve("src/main/resources/META-INF/spring/"
                                + "org.springframework.boot.autoconfigure.AutoConfiguration.imports");
                        for (String line : Files.readAllLines(imports)) {
                            if (line.startsWith("com.documentplatform." + m + ".")) exclude.add(line.strip());
                        }
                    }
                }
            }
        }
        List<String> args = new ArrayList<>(List.of("--spring.datasource.url=" + db.url(),
                "--spring.datasource.username=" + db.user(), "--spring.datasource.password=" + db.password(),
                "--management.endpoint.health.probes.enabled=true",
                "--platform.licensing.product-code=" + TestLicences.PRODUCT, "--platform.licensing.tenant-id=" + TestLicences.TENANT,
                "--platform.licensing.notifications.enabled=false", "--platform.erase-log.location=" + dir.resolve("log-" + module),
                "--platform.outbound.installation=ON_PREMISES", "--platform.identity.push.product-code=" + TestLicences.PRODUCT,
                "--platform.model-governance.approver-roles=model-owner", "--platform.model-governance.forbidden-roles=model-builder",
                "--platform.monitoring.startup-checks=false", "--platform.crypto.tenant-id=" + TestLicences.TENANT,
                "--spring.autoconfigure.exclude=" + String.join(",", exclude)));
        return new SpringApplicationBuilder(App.class).web(WebApplicationType.NONE).logStartupInfo(false)
                .run(args.toArray(String[]::new));
    }

    @ParameterizedTest
    @ValueSource(strings = {"crypto", "eraselog", "identity", "integration", "licensing", "modelgov", "monitoring",
            "notifications", "outbound"})
    void anAppStartsWithTheModule(String module) throws Exception {
        try (ConfigurableApplicationContext ctx = start(module)) {
            assertEquals(1, ctx.getBeansOfType(BEANS.get(module)).size(), module);
            if (module.equals("identity")) assertNotNull(ctx.getBean(PushCaller.class));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"all"})
    void anAppStartsWithAllOfThem(String all) throws Exception {
        try (ConfigurableApplicationContext ctx = start(all)) {
            BEANS.forEach((m, type) -> assertEquals(1, ctx.getBeansOfType(type).size(), m));
            assertTrue(ctx.getBean("readinessStateHealthIndicator", HealthIndicator.class).getClass().getName()
                    .startsWith("com.documentplatform."), "our readiness takes the place of Boot's");
            for (String health : List.of("keyService", "eraseLog", "licensing", "notifications", "dependencies")) {
                assertTrue(ctx.getBeansOfType(HealthIndicator.class).keySet().stream()
                        .anyMatch(n -> n.toLowerCase().contains(health.toLowerCase())), health + " health is there");
            }
        }
    }
}
