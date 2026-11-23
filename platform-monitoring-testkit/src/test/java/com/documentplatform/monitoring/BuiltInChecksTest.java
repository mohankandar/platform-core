package com.documentplatform.monitoring;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.testkit.InMemoryKeyService;
import com.documentplatform.identity.IdentityConnectionStatus;
import com.documentplatform.identity.IdentityProviderType;
import com.documentplatform.identity.jdbc.IdentitySchema;
import com.documentplatform.identity.signin.TestReport;
import com.documentplatform.identity.store.LoginConnection;
import com.documentplatform.identity.testkit.MockIssuer;
import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.integration.config.CallStep;
import com.documentplatform.integration.config.Endpoint;
import com.documentplatform.integration.config.HealthCheck;
import com.documentplatform.integration.config.HttpMethod;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.engine.CallEngine;
import com.documentplatform.integration.store.ConfigChange;
import com.documentplatform.integration.testkit.InMemoryIntegrationStore;
import com.documentplatform.integration.testkit.MockDestination;
import com.documentplatform.licensing.ClockGuard;
import com.documentplatform.licensing.LicenceKind;
import com.documentplatform.licensing.LicenceRefusal;
import com.documentplatform.licensing.LicenceService;
import com.documentplatform.licensing.LicenceVerifier;
import com.documentplatform.licensing.inmemory.InMemoryLastSeenStore;
import com.documentplatform.licensing.inmemory.InMemoryLicenceStore;
import com.documentplatform.licensing.inmemory.InMemoryUsageLedger;
import com.documentplatform.licensing.inmemory.TestLicences;
import com.documentplatform.monitoring.checks.ChannelCheck;
import com.documentplatform.monitoring.checks.DatabaseCheck;
import com.documentplatform.monitoring.checks.FileLocationCheck;
import com.documentplatform.monitoring.checks.HttpServiceCheck;
import com.documentplatform.monitoring.checks.IntegrationChecks;
import com.documentplatform.monitoring.checks.KeyServiceCheck;
import com.documentplatform.monitoring.checks.LicenceCheck;
import com.documentplatform.monitoring.checks.LoginSystemCheck;
import com.documentplatform.monitoring.checks.SecretStoreCheck;
import com.documentplatform.monitoring.testkit.MockHttpService;
import com.documentplatform.notifications.inmemory.InMemorySecretResolver;
import com.documentplatform.notifications.secret.SecretRef;
import com.documentplatform.outbound.Installation;
import com.documentplatform.outbound.Outbound;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** P032 B2: every built-in check in every state, against the platform's mocks and a real PostgreSQL. */
class BuiltInChecksTest {

    final CheckRunner runner = new CheckRunner();

    private CheckResult run(DependencyCheck c) {
        return runner.run(c);
    }

    private static void assertResult(CheckState state, com.documentplatform.errors.PlatformCode code, CheckResult r) {
        assertEquals(state, r.state(), r.line());
        assertEquals(code, r.code(), r.line());
        assertFalse(r.fix().isBlank(), r.line());
    }

    @Test
    void database() {
        try (PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine")) {
            pg.start();
            PGSimpleDataSource ds = new PGSimpleDataSource();
            ds.setURL(pg.getJdbcUrl());
            ds.setUser(pg.getUsername());
            ds.setPassword(pg.getPassword());
            DatabaseCheck check = new DatabaseCheck(ds, List.of(new DatabaseCheck.SchemaVerify("identity",
                    IdentitySchema::verify)));
            assertTrue(check.requiredAtStartup());
            assertResult(CheckState.DOWN, com.documentplatform.identity.jdbc.IdentitySchemaException.Problem
                    .IDN_TABLES_MISSING, run(check));
            try (var c = ds.getConnection(); var s = c.createStatement()) {
                for (String script : IdentitySchema.scripts()) s.execute(IdentitySchema.script(script));
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
            // xc-schemas-9 (P037): the owner as the app's login works, with a warning; a DML-only login has none.
            CheckResult owner = run(check);
            assertResult(CheckState.HAVING_PROBLEMS, MonitoringCode.MON_DB_USER_TOO_POWERFUL, owner);
            assertTrue(owner.cause().contains("owns tables"), owner.cause());
            try (var c = ds.getConnection(); var s = c.createStatement()) {
                s.execute("create role app_dml login password 'dml-pw'");
                for (String t : com.documentplatform.identity.jdbc.IdentitySchema.TABLES) {
                    s.execute("grant select, insert, update, delete on " + t + " to app_dml");
                }
                s.execute("grant select on " + com.documentplatform.identity.jdbc.IdentitySchema.VERSION_TABLE
                        + " to app_dml");
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
            PGSimpleDataSource dml = new PGSimpleDataSource();
            dml.setURL(pg.getJdbcUrl());
            dml.setUser("app_dml");
            dml.setPassword("dml-pw");
            assertResult(CheckState.WORKING, MonitoringCode.MON_WORKING, run(new DatabaseCheck(dml,
                    List.of(new DatabaseCheck.SchemaVerify("identity", IdentitySchema::verify)))));
            try (var c = ds.getConnection(); var s = c.createStatement()) {
                s.execute("update idn_schema_version set version = 3");
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
            CheckResult older = run(check);
            assertResult(CheckState.DOWN, com.documentplatform.identity.jdbc.IdentitySchemaException.Problem
                    .IDN_VERSION_OLDER, older);
            assertTrue(older.cause().contains("identity tables are older"), older.cause());

            PGSimpleDataSource wrong = new PGSimpleDataSource();
            wrong.setURL(pg.getJdbcUrl());
            wrong.setUser(pg.getUsername());
            wrong.setPassword("not-the-password");
            CheckResult refused = run(new DatabaseCheck(wrong, List.of()));
            assertResult(CheckState.DOWN, MonitoringCode.MON_DB_LOGIN_REFUSED, refused);
            assertFalse(refused.cause().contains("not-the-password"));
        }
        PGSimpleDataSource none = new PGSimpleDataSource();
        none.setURL("jdbc:postgresql://127.0.0.1:1/nothing?connectTimeout=2");
        none.setUser("app");
        assertResult(CheckState.DOWN, MonitoringCode.MON_DB_UNREACHABLE, run(new DatabaseCheck(none, List.of())));
    }

    @Test
    void keyService() {
        InMemoryKeyService keys = new InMemoryKeyService();
        KeyServiceCheck required = new KeyServiceCheck(keys, true);
        assertTrue(required.requiredAtStartup());
        assertFalse(new KeyServiceCheck(keys, false).requiredAtStartup(), "only when customerKeys is required");
        assertResult(CheckState.WORKING, MonitoringCode.MON_WORKING, run(required));
        keys.down(true);
        assertResult(CheckState.DOWN, CryptoError.CRYPTO_KEY_SERVICE_UNREACHABLE, run(required));
        // Login refused, a key missing, permission denied, key disabled: the key service's own code is passed on.
        for (CryptoError code : List.of(CryptoError.CRYPTO_LOGIN_REFUSED, CryptoError.CRYPTO_KEY_MISSING,
                CryptoError.CRYPTO_PERMISSION_DENIED, CryptoError.CRYPTO_KEY_DISABLED)) {
            com.documentplatform.crypto.KeyService failing = (com.documentplatform.crypto.KeyService)
                    java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[]{com.documentplatform.crypto.KeyService.class}, (proxy, m, args) -> {
                                if (m.getName().equals("health")) {
                                    return new com.documentplatform.crypto.KeyService.Health(
                                            com.documentplatform.crypto.KeyService.Health.Status.DOWN, "vault", code,
                                            "test");
                                }
                                throw new UnsupportedOperationException(m.getName());
                            });
            assertResult(CheckState.DOWN, code, run(new KeyServiceCheck(failing, true)));
        }
    }

    @Test
    void secretStore() {
        InMemorySecretResolver secrets = new InMemorySecretResolver();
        secrets.put(SecretRef.of("app/db-password"), "Very-Secret-Value-123");
        SecretStoreCheck check = new SecretStoreCheck(secrets, List.of(SecretRef.of("app/db-password"),
                SecretRef.of("app/smtp-password")), false);
        CheckResult missing = run(check);
        assertResult(CheckState.DOWN, MonitoringCode.MON_SECRET_MISSING, missing);
        assertEquals("The secret app/smtp-password is not in the secret store.", missing.cause());
        secrets.put(SecretRef.of("app/smtp-password"), "Other-Secret-456");
        assertResult(CheckState.WORKING, MonitoringCode.MON_WORKING, run(check));
        CheckResult unavailable = run(new SecretStoreCheck(ref -> {
            throw new IllegalStateException("store down");
        }, List.of(SecretRef.of("app/db-password")), false));
        assertResult(CheckState.DOWN, MonitoringCode.MON_SECRET_UNAVAILABLE, unavailable);
        assertFalse((missing.cause() + unavailable.cause()).contains("Very-Secret"));
    }

    @Test
    void loginSystem() {
        try (MockIssuer issuer = MockIssuer.start()) {
            LoginConnection c = new LoginConnection(UUID.randomUUID(), "TEN-TEST-0001", "PRODUCT_A", "test-idp",
                    IdentityProviderType.OIDC, issuer.issuer(), "web", "api://product-a", null,
                    IdentityConnectionStatus.ACTIVE, 1);
            LoginSystemCheck check = new LoginSystemCheck("PRODUCT_A", () -> Optional.of(c), MockIssuer.fetch(), true,
                    Clock.systemUTC());
            assertResult(CheckState.WORKING, MonitoringCode.MON_WORKING, run(check));
            issuer.noKeys();
            assertResult(CheckState.DOWN, TestReport.Code.IDN_TEST_KEYS_MISSING, run(check));
            issuer.down();
            assertResult(CheckState.DOWN, TestReport.Code.IDN_TEST_ISSUER_UNREACHABLE, run(check));
            assertResult(CheckState.HAVING_PROBLEMS, MonitoringCode.MON_LOGIN_NO_CONNECTION,
                    run(new LoginSystemCheck("PRODUCT_A", Optional::empty, MockIssuer.fetch(), true, Clock.systemUTC())));
            // Through the shared outbound rules: an http issuer on this machine is refused without the development rule.
            CheckResult strict = run(new LoginSystemCheck("PRODUCT_A", () -> Optional.of(c),
                    new com.documentplatform.identity.spring.OutboundIssuerFetch(new Outbound(Installation.onPremises())),
                    false, Clock.systemUTC()));
            assertResult(CheckState.DOWN, TestReport.Code.IDN_TEST_ISSUER_UNREACHABLE, strict);
        }
    }

    @Test
    void licence() {
        TestLicences licences = new TestLicences();
        Instant now = Instant.parse("2026-11-22T10:00:00Z");
        LocalDate today = LocalDate.of(2026, 11, 22);
        java.util.function.Function<String, LicenceService> service = tenant -> new LicenceService(
                new InMemoryLicenceStore(), new LicenceVerifier(licences.trustedKeys()),
                new ClockGuard(Clock.fixed(now, ZoneOffset.UTC), new InMemoryLastSeenStore()),
                new InMemoryUsageLedger(), TestLicences.PRODUCT, tenant);

        LicenceService empty = service.apply(null);
        assertResult(CheckState.DOWN, LicenceRefusal.LICENCE_MISSING, run(new LicenceCheck(empty)));

        record Case(LocalDate starts, LocalDate ends, CheckState state, com.documentplatform.errors.PlatformCode code) {
        }
        for (Case k : List.of(
                new Case(today.minusYears(1), today.plusYears(1), CheckState.WORKING, MonitoringCode.MON_WORKING),
                new Case(today.minusYears(1), today.plusDays(20), CheckState.HAVING_PROBLEMS,
                        MonitoringCode.MON_LICENCE_EXPIRING),
                new Case(today.minusYears(1), today.minusDays(5), CheckState.HAVING_PROBLEMS,
                        MonitoringCode.MON_LICENCE_IN_GRACE),
                new Case(today.minusYears(2), today.minusDays(60), CheckState.DOWN, MonitoringCode.MON_LICENCE_EXPIRED),
                new Case(today.plusDays(10), today.plusYears(1), CheckState.DOWN,
                        MonitoringCode.MON_LICENCE_NOT_STARTED))) {
            LicenceService s = service.apply(null);
            s.store(licences.sign(licences.subscription(LicenceKind.PRODUCTION, k.starts(), k.ends())));
            CheckResult r = run(new LicenceCheck(s));
            assertResult(k.state(), k.code(), r);
            if (k.code() == MonitoringCode.MON_LICENCE_EXPIRING) {
                assertEquals("The licence ends on " + today.plusDays(20) + ", in 20 days.", r.cause());
            }
        }
        // Another customer's licence; a signature that does not verify.
        LicenceService mine = service.apply(TestLicences.TENANT);
        InMemoryLicenceStore store = new InMemoryLicenceStore();
        LicenceService other = new LicenceService(store, new LicenceVerifier(licences.trustedKeys()),
                new ClockGuard(Clock.fixed(now, ZoneOffset.UTC), new InMemoryLastSeenStore()),
                new InMemoryUsageLedger(), TestLicences.PRODUCT, null);
        other.store(licences.sign(licences.subscription(LicenceKind.PRODUCTION, today.minusYears(1),
                today.plusYears(1))));
        LicenceService wrongTenant = new LicenceService(store, new LicenceVerifier(licences.trustedKeys()),
                new ClockGuard(Clock.fixed(now, ZoneOffset.UTC), new InMemoryLastSeenStore()),
                new InMemoryUsageLedger(), TestLicences.PRODUCT, "TEN-SOMEONE-ELSE");
        assertResult(CheckState.DOWN, LicenceRefusal.LICENCE_WRONG_CUSTOMER, run(new LicenceCheck(wrongTenant)));
        var stored = store.get(com.documentplatform.licensing.LicenceStore.Slot.CURRENT).orElseThrow();
        store.put(com.documentplatform.licensing.LicenceStore.Slot.CURRENT, new com.documentplatform.licensing
                .LicenceStore.Stored(stored.file().replace("PRODUCT_A", "PRODUCT_Z"), stored.licenceId(),
                stored.version(), stored.tenantId(), stored.startsOn(), stored.storedAt()));
        CheckResult tampered = run(new LicenceCheck(other));
        assertEquals(CheckState.DOWN, tampered.state());
        assertTrue(List.of(LicenceRefusal.LICENCE_TAMPERED, LicenceRefusal.LICENCE_MALFORMED).contains(tampered.code()),
                tampered.line());
        assertNotNull(mine);
    }

    @Test
    void mailAndWebhookChannels() throws Exception {
        GreenMail mail = new GreenMail(ServerSetupTest.SMTP);
        mail.start();
        try {
            int port = mail.getSmtp().getPort();
            assertResult(CheckState.WORKING, MonitoringCode.MON_WORKING, run(ChannelCheck.mail("mail",
                    Map.of("host", "127.0.0.1", "from", "app@example.test"), "127.0.0.1", port)));
            assertEquals(0, mail.getReceivedMessages().length, "nothing is sent");
            CheckResult incomplete = run(ChannelCheck.mail("mail", Map.of("host", "127.0.0.1", "from", ""),
                    "127.0.0.1", port));
            assertResult(CheckState.DOWN, MonitoringCode.MON_CHANNEL_SETTINGS_INCOMPLETE, incomplete);
            assertEquals("The mail channel is missing the setting from.", incomplete.cause());
        } finally {
            mail.stop();
        }
        assertResult(CheckState.DOWN, MonitoringCode.MON_CHANNEL_UNREACHABLE, run(ChannelCheck.mail("mail",
                Map.of("host", "127.0.0.1"), "127.0.0.1", 1)));
        try (MockHttpService hook = MockHttpService.start()) {
            URI https = URI.create("https://127.0.0.1:" + hook.health().getPort() + "/workflows/abc/secret-path");
            CheckResult ok = run(ChannelCheck.webhook("teams", https));
            assertResult(CheckState.WORKING, MonitoringCode.MON_WORKING, ok);
            hook.down();
            CheckResult unreachable = run(ChannelCheck.webhook("teams", https));
            assertResult(CheckState.DOWN, MonitoringCode.MON_CHANNEL_UNREACHABLE, unreachable);
            assertFalse(unreachable.cause().contains("secret-path"), "only the host is shown");
        }
        assertResult(CheckState.DOWN, MonitoringCode.MON_CHANNEL_ADDRESS_INVALID,
                run(ChannelCheck.webhook("slack", URI.create("http://hooks.example.test/x"))));
    }

    @Test
    void fileLocations(@TempDir Path dir) {
        assertResult(CheckState.WORKING, MonitoringCode.MON_WORKING, run(new FileLocationCheck("export", dir)));
        assertResult(CheckState.DOWN, MonitoringCode.MON_PATH_MISSING,
                run(new FileLocationCheck("export", dir.resolve("missing"))));
        class Fake implements FileLocationCheck.Disk {
            boolean readable = true, writable = true;
            long used = 50;

            public boolean isDirectory(Path p) {
                return true;
            }

            public boolean isReadable(Path p) {
                return readable;
            }

            public boolean canWrite(Path p) {
                return writable;
            }

            public OptionalLong usedPercent(Path p) {
                return OptionalLong.of(used);
            }
        }
        Fake disk = new Fake();
        FileLocationCheck check = new FileLocationCheck("intake", dir, true, 90, 95, false, disk);
        assertResult(CheckState.WORKING, MonitoringCode.MON_WORKING, run(check));
        disk.used = 91;
        CheckResult almost = run(check);
        assertResult(CheckState.HAVING_PROBLEMS, MonitoringCode.MON_DISK_ALMOST_FULL, almost);
        assertEquals("The disk of " + dir + " (intake) is 91% full (warning at 90%).", almost.cause());
        disk.used = 96;
        assertResult(CheckState.DOWN, MonitoringCode.MON_DISK_FULL, run(check));
        disk.writable = false;
        assertResult(CheckState.DOWN, MonitoringCode.MON_PATH_NOT_WRITABLE, run(check));
        disk.readable = false;
        assertResult(CheckState.DOWN, MonitoringCode.MON_PATH_NOT_READABLE, run(check));
        assertThrows(IllegalArgumentException.class, () -> new FileLocationCheck("x", dir, true, 96, 95, false));
    }

    @Test
    void httpService() {
        try (MockHttpService ocr = MockHttpService.start()) {
            HttpServiceCheck check = new HttpServiceCheck("ocr", ocr.health(), 200, Duration.ofSeconds(1), true);
            assertResult(CheckState.WORKING, MonitoringCode.MON_WORKING, run(check));
            ocr.answering(503);
            CheckResult bad = run(check);
            assertResult(CheckState.DOWN, MonitoringCode.MON_HTTP_UNEXPECTED_ANSWER, bad);
            assertEquals("The service ocr answered 503 at " + ocr.health() + "; 200 was expected.", bad.cause());
            ocr.answering(200).slow(Duration.ofSeconds(3));
            assertResult(CheckState.DOWN, MonitoringCode.MON_HTTP_TIMEOUT, run(check));
            ocr.down();
            assertResult(CheckState.DOWN, MonitoringCode.MON_HTTP_UNREACHABLE, run(check));
        }
        assertResult(CheckState.DOWN, MonitoringCode.MON_HTTP_ADDRESS_INVALID, run(new HttpServiceCheck("ocr",
                URI.create("ftp://files.example.test/health"), 200, Duration.ofSeconds(1), false)));
    }

    @Test
    void httpServiceNeverShowsUserInfoOrQueryOfTheAddress() {
        List<String> planted = List.of("planted-user", "PLANTED-PW", "PLANTED-TOKEN", "token=");
        List<CheckResult> results = new ArrayList<>();
        try (MockHttpService ocr = MockHttpService.start()) {
            URI health = ocr.health();
            URI withSecrets = URI.create("http://planted-user:PLANTED-PW@" + health.getHost() + ":" + health.getPort()
                    + health.getPath() + "?token=PLANTED-TOKEN");
            HttpServiceCheck check = new HttpServiceCheck("ocr", withSecrets, 200, Duration.ofSeconds(1), true);
            ocr.answering(503);
            CheckResult bad = run(check);
            assertResult(CheckState.DOWN, MonitoringCode.MON_HTTP_UNEXPECTED_ANSWER, bad);
            assertTrue(bad.cause().contains(health.toString()), bad.cause());
            results.add(bad);
            ocr.answering(200).slow(Duration.ofSeconds(3));
            results.add(run(check));
            ocr.down();
            results.add(run(check));
        }
        results.add(run(new HttpServiceCheck("ocr", URI.create("ftp://planted-user:PLANTED-PW@files.example.test/"
                + "health?token=PLANTED-TOKEN"), 200, Duration.ofSeconds(1), false)));
        assertEquals(List.of(MonitoringCode.MON_HTTP_UNEXPECTED_ANSWER, MonitoringCode.MON_HTTP_TIMEOUT,
                MonitoringCode.MON_HTTP_UNREACHABLE, MonitoringCode.MON_HTTP_ADDRESS_INVALID),
                results.stream().map(CheckResult::code).toList());
        for (CheckResult r : results) {
            for (String text : List.of(r.cause(), r.line(), r.toString())) {
                for (String secret : planted) {
                    assertFalse(text.contains(secret), secret + " in: " + text);
                }
            }
        }
    }

    @Test
    void integrationsAreNeverRequiredAndGiveTheirOwnCodes() {
        try (MockDestination mock = MockDestination.start();
             CallEngine engine = new CallEngine(new Outbound(Installation.onPremises().withInternalAddresses(true)),
                     new InMemorySecretResolver(), Clock.systemUTC())) {
            InMemoryIntegrationStore store = new InMemoryIntegrationStore();
            IntegrationConfig c = IntegrationConfig.apiPush(UUID.randomUUID(), "PRODUCT_A", "Destination",
                            new Endpoint(mock.address(), true), new SignIn.BearerFixed(SecretRef.of("int/t")))
                    .withSteps(List.of(CallStep.of("send", HttpMethod.POST, "/api/notice")))
                    .withHealth(HealthCheck.get(MockDestination.HEALTH_PATH, false));
            store.create(c, new ConfigChange(c.id(), c.entity(), c.product(), 1, "test", Instant.now(), List.of(),
                    List.of()));
            IntegrationChecks checks = new IntegrationChecks(store, engine::health);
            List<DependencyCheck> list = checks.get();
            assertEquals(1, list.size());
            DependencyCheck one = list.get(0);
            assertFalse(one.requiredAtStartup());
            assertEquals("integrations", one.group());
            assertResult(CheckState.WORKING, MonitoringCode.MON_WORKING, run(one));
            mock.healthFailing(true);
            CheckResult down = run(one);
            assertEquals(CheckState.DOWN, down.state(), down.line());
            assertEquals(IntegrationCode.INT_OUTAGE, down.code(), down.line());
            // A health rule that says busy (for example 503 with Retry-After) is HAVING_PROBLEMS, not DOWN.
            DependencyCheck busy = new IntegrationChecks(store, s -> new com.documentplatform.integration.result
                    .HealthResult(s.config().id(), 1, com.documentplatform.integration.result.Outcome.BUSY,
                    IntegrationCode.INT_DESTINATION_BUSY, null, 503, Duration.ofSeconds(30), Instant.now())).get().get(0);
            assertResult(CheckState.HAVING_PROBLEMS, IntegrationCode.INT_DESTINATION_BUSY, run(busy));
        }
    }
}
