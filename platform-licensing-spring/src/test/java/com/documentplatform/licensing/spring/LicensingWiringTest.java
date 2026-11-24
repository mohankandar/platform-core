package com.documentplatform.licensing.spring;

import com.documentplatform.licensing.AllowanceType;
import com.documentplatform.licensing.ClockGuard;
import com.documentplatform.licensing.Ed25519;
import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.LicenceException;
import com.documentplatform.licensing.LicenceKind;
import com.documentplatform.licensing.LicenceRefusal;
import com.documentplatform.licensing.LicenceService;
import com.documentplatform.licensing.LicenceState;
import com.documentplatform.licensing.LicenceVerifier;
import com.documentplatform.licensing.inmemory.InMemoryUsageLedger;
import com.documentplatform.licensing.inmemory.TestLicences;
import com.documentplatform.licensing.jdbc.LicensingSchemaException;
import com.documentplatform.licensing.spring.internal.LicensingConfigException;
import com.documentplatform.licensing.jdbc.PostgresLastSeenStore;
import com.documentplatform.licensing.jdbc.PostgresLicenceStore;
import com.documentplatform.licensing.jdbc.PostgresUsageLedger;
import com.documentplatform.licensing.usage.ExportAnchor;
import com.documentplatform.licensing.usage.UsageExport;
import com.documentplatform.licensing.usage.UsageExportVerifier;
import com.documentplatform.licensing.usage.UsageLedger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static com.documentplatform.licensing.inmemory.TestLicences.PRODUCT;
import static com.documentplatform.licensing.inmemory.TestLicences.SLOT;
import static com.documentplatform.licensing.inmemory.TestLicences.TENANT;
import static org.junit.jupiter.api.Assertions.*;

/** The auto-configuration against Postgres, with the app connecting as the DML-only user. */
class LicensingWiringTest {

    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");
    private final TestLicences licences = new TestLicences();

    private ApplicationContextRunner app(DataSource ds, Instant now, String... properties) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LicensingAutoConfiguration.class))
                .withBean(DataSource.class, () -> ds)
                .withBean(LicensingClock.class, () -> new LicensingClock(Clock.fixed(now, ZoneOffset.UTC)))
                .withBean(com.documentplatform.licensing.LicenceBuild.class, licences::licenceBuild)
                .withPropertyValues("platform.licensing.product-code=" + PRODUCT, "platform.licensing.tenant-id=" + TENANT)
                .withPropertyValues(properties);
    }

    /** Stores a licence the way the licence command does, as the DML-only user. */
    private void store(DataSource ds, Licence licence) {
        new LicenceService(new PostgresLicenceStore(ds), new LicenceVerifier(licences.trustedKeys()),
                new ClockGuard(Clock.fixed(NOW, ZoneOffset.UTC), new PostgresLastSeenStore(ds)),
                new PostgresUsageLedger(ds), PRODUCT, null).store(licences.sign(licence));
    }

    private Licence production(String starts, String expires) {
        return licences.subscription(LicenceKind.PRODUCTION, LocalDate.parse(starts), LocalDate.parse(expires));
    }

    private static <T extends Throwable> T rootOf(Throwable t, Class<T> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return type.cast(c);
            }
        }
        throw new AssertionError("no " + type.getSimpleName() + " in " + t);
    }

    @Test
    void aDmlOnlyUserRunsEverything() {
        Db db = Db.migrated();
        DataSource ds = db.dmlUser();
        store(ds, production("2026-01-01", "2027-12-31"));
        app(ds, NOW).run(ctx -> {
            assertNull(ctx.getStartupFailure());
            LicenceService service = ctx.getBean(LicenceService.class);
            assertEquals(LicenceState.VALID, service.status().state());
            assertTrue(service.intakeAllowed());
            assertFalse(service.testEnvironment());
            service.recordUsage(SLOT, AllowanceType.PAGES_PER_MONTH, 900, false);
            assertEquals(900, service.usage().assess(SLOT, PRODUCT, AllowanceType.PAGES_PER_MONTH, YearMonth.of(2026, 11)).used());
            UsageLedger ledger = ctx.getBean(UsageLedger.class);
            assertTrue(ledger.verifyChain().intact());
            UsageExport export = UsageExport.create(service.status().licence(), ledger, YearMonth.of(2026, 11),
                    YearMonth.of(2026, 11), ExportAnchor.first(), NOW);
            assertTrue(UsageExportVerifier.verify(export.text(), ExportAnchor.first()).ok());
            // A renewal stored through the running app waits for its start date.
            Licence renewal = new Licence("LIC-TEST-0009", 2, "Test Customer", TENANT, List.of(PRODUCT),
                    production("2028-01-01", "2028-12-31").entitySlots(), com.documentplatform.licensing.LicenceType.SUBSCRIPTION,
                    LicenceKind.PRODUCTION, LocalDate.parse("2028-01-01"), LocalDate.parse("2028-12-31"), null,
                    com.documentplatform.licensing.Deployment.ON_PREM, licences.keyId(), NOW);
            assertEquals(LicenceService.StoreResult.STORED_WAITING, service.store(licences.sign(renewal)).result());
            Health h = ctx.getBean(LicensingHealthIndicator.class).health();
            assertEquals(Status.UP, h.getStatus());
            assertEquals("2028-01-01", h.getDetails().get("renewalStartsOn"));
        });
    }

    @Test
    void aRestartDuringGraceStarts() {
        Db db = Db.migrated();
        DataSource ds = db.dmlUser();
        store(ds, production("2026-01-01", "2026-11-11"));
        app(ds, NOW).run(ctx -> {
            assertNull(ctx.getStartupFailure());
            Health h = ctx.getBean(LicensingHealthIndicator.class).health();
            assertEquals(LicensingHealthIndicator.WARNING, h.getStatus());
            assertEquals("EXPIRED_IN_GRACE", h.getDetails().get("state"));
            assertEquals(-10L, h.getDetails().get("daysUntilExpiry"));
            assertEquals("2026-12-11", h.getDetails().get("graceEndsOn"));
            assertEquals(true, h.getDetails().get("intakeAllowed"));
        });
        app(ds, Instant.parse("2026-12-12T00:00:00Z")).run(ctx -> {
            assertNull(ctx.getStartupFailure(), "grace over still starts");
            Health h = ctx.getBean(LicensingHealthIndicator.class).health();
            assertEquals(LicensingHealthIndicator.INTAKE_STOPPED, h.getStatus());
            assertFalse(ctx.getBean(LicenceService.class).intakeAllowed());
        });
    }

    @Test
    void aClockRolledBackStartsWithTheFlagAndCannotExtendExpiry() {
        Db db = Db.migrated();
        DataSource ds = db.dmlUser();
        store(ds, production("2026-01-01", "2026-12-31"));
        app(ds, Instant.parse("2027-02-15T00:00:00Z")).run(ctx ->
                assertEquals(LicenceState.EXPIRED_GRACE_OVER, ctx.getBean(LicenceService.class).status().state()));
        app(ds, Instant.parse("2027-02-14T21:00:00Z")).run(ctx -> {
            assertNull(ctx.getStartupFailure());
            Health h = ctx.getBean(LicensingHealthIndicator.class).health();
            assertEquals(true, h.getDetails().get("clockBehind"));
            assertEquals("3 hours", h.getDetails().get("clockBehindBy"));
            assertEquals("EXPIRED_GRACE_OVER", h.getDetails().get("state"));
        });
        app(ds, Instant.parse("2026-06-01T00:00:00Z")).run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertEquals(LicenceState.EXPIRED_GRACE_OVER, ctx.getBean(LicenceService.class).status().state(),
                    "a clock back in the valid period does not extend the licence");
        });
    }

    @Test
    void healthShowsNoKeysOrSignatures() {
        Db db = Db.migrated();
        DataSource ds = db.dmlUser();
        store(ds, production("2026-01-01", "2027-12-31"));
        String file = db.text("select file from lic_licence");
        String signature = file.substring(file.indexOf("\"value\": \"") + 10, file.indexOf("\"value\": \"") + 40);
        String publicKey = Ed25519.encodePublic(licences.keyPair().getPublic());
        app(ds, NOW).run(ctx -> {
            String details = ctx.getBean(LicensingHealthIndicator.class).health().getDetails().toString();
            assertFalse(details.contains(signature));
            assertFalse(details.contains(publicKey));
            assertFalse(details.contains("payloadSha256"));
        });
    }

    /**
     * Every log line of the licence service and the licensing wiring (storing, a start, a refused start, a waiting
     * licence taking over): licence IDs, versions and states only, never the signature, a key or the file.
     */
    @Test
    void logLinesShowNoKeysOrSignatures() {
        List<String> lines = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.logging.Handler capture = new java.util.logging.Handler() {
            private final java.util.logging.SimpleFormatter format = new java.util.logging.SimpleFormatter();

            @Override
            public void publish(java.util.logging.LogRecord r) {
                lines.add(format.format(r));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        capture.setLevel(java.util.logging.Level.ALL);
        java.util.logging.Logger platform = java.util.logging.Logger.getLogger("com.documentplatform");
        java.util.logging.Level before = platform.getLevel();
        platform.addHandler(capture);
        platform.setLevel(java.util.logging.Level.ALL);
        Db db = Db.migrated();
        DataSource ds = db.dmlUser();
        List<String> secrets = new ArrayList<>(List.of(Ed25519.encodePublic(licences.keyPair().getPublic()),
                java.util.Base64.getEncoder().encodeToString(licences.keyPair().getPrivate().getEncoded()),
                "payloadSha256"));
        try {
            store(ds, production("2026-01-01", "2026-12-31"));
            String file = db.text("select file from lic_licence");
            int at = file.indexOf("\"value\": \"") + 10;
            secrets.add(file.substring(at, file.indexOf('"', at)));
            Licence v1 = production("2026-01-01", "2026-12-31");
            store(ds, new Licence(v1.licenceId(), 2, v1.customer(), v1.tenantId(), v1.products(), v1.entitySlots(),
                    v1.type(), v1.kind(), LocalDate.parse("2026-12-01"), LocalDate.parse("2027-12-31"), null,
                    v1.deployment(), v1.keyId(), v1.issuedAt()));
            app(ds, NOW).run(ctx -> assertNull(ctx.getStartupFailure()));
            app(ds, Instant.parse("2026-12-02T10:00:00Z")).run(ctx -> ctx.getBean(LicenceService.class).status());
            db.exec("update lic_licence set file = replace(file, '2026-12-31', '2036-12-31')");
            app(ds, NOW).run(ctx -> assertNotNull(ctx.getStartupFailure()));
        } finally {
            platform.removeHandler(capture);
            platform.setLevel(before);
        }
        String all = String.join("\n", lines);
        assertTrue(all.contains("stored"), "the paths logged: " + all);
        // licensing-44 / licensing-79: the start-up check names the licence by ID; the refused start logs the code.
        assertTrue(all.contains("Licence LIC-TEST-0001 v1: PRODUCTION"), "the start-up check line: " + all);
        assertTrue(lines.stream().anyMatch(l -> l.strip().endsWith("Licence refused: LICENCE_TAMPERED")),
                "the refusal line carries the code: " + all);
        secrets.addAll(List.of("Test Customer", "entitySlots", "\"allowances\"", "2036-12-31", "platform-licence/1"));
        for (String secret : secrets) {
            assertFalse(all.contains(secret), "a key, signature or file text in the log: " + secret + "\n" + all);
        }
    }

    private FailureAnalysis refusedStart(ApplicationContextRunner runner, LicenceRefusal code) {
        FailureAnalysis[] out = new FailureAnalysis[1];
        runner.run(ctx -> {
            assertNotNull(ctx.getStartupFailure(), "should not start");
            LicenceException e = rootOf(ctx.getStartupFailure(), LicenceException.class);
            assertEquals(code, e.code());
            out[0] = new LicensingFailureAnalyzer().analyze(ctx.getStartupFailure());
        });
        assertTrue(out[0].getDescription().startsWith(code.name() + ": "), out[0].getDescription());
        assertEquals(code.action(), out[0].getAction());
        return out[0];
    }

    @Test
    void startupRefusesWhenNoLicenceWasEverStored() {
        Db db = Db.migrated();
        FailureAnalysis a = refusedStart(app(db.dmlUser(), NOW), LicenceRefusal.LICENCE_MISSING);
        assertTrue(a.getAction().contains("store --file"));
    }

    @Test
    void startupRefusesAForgedLicence() {
        Db db = Db.migrated();
        DataSource ds = db.dmlUser();
        store(ds, production("2026-01-01", "2027-12-31"));
        String forged = com.documentplatform.licensing.issue.LicenceSigner.sign(production("2026-01-01", "2099-12-31"),
                com.documentplatform.licensing.issue.SigningKeys.generate().getPrivate());
        db.exec("update lic_licence set file = '" + forged.replace("'", "''") + "'");
        refusedStart(app(ds, NOW), LicenceRefusal.LICENCE_FORGED);
    }

    @Test
    void startupRefusesATamperedLicence() {
        Db db = Db.migrated();
        DataSource ds = db.dmlUser();
        store(ds, production("2026-01-01", "2026-12-31"));
        db.exec("update lic_licence set file = replace(file, '2026-12-31', '2036-12-31')");
        refusedStart(app(ds, NOW), LicenceRefusal.LICENCE_TAMPERED);
    }

    @Test
    void startupRefusesAnotherCustomersLicence() {
        Db db = Db.migrated();
        DataSource ds = db.dmlUser();
        store(ds, production("2026-01-01", "2027-12-31"));
        refusedStart(app(ds, NOW, "platform.licensing.tenant-id=TEN-SOMEONE-ELSE"), LicenceRefusal.LICENCE_WRONG_CUSTOMER);
    }

    @Test
    void theTenantIdIsRequired() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LicensingAutoConfiguration.class))
                .withBean(com.documentplatform.licensing.LicenceBuild.class, licences::licenceBuild)
                .withPropertyValues("platform.licensing.product-code=" + PRODUCT)
                .run(ctx -> {
                    LicensingConfigException e = rootOf(ctx.getStartupFailure(), LicensingConfigException.class);
                    assertEquals(1, e.problems().size(), e.getMessage());
                    assertTrue(e.getMessage().contains("platform.licensing.tenant-id is missing"), e.getMessage());
                    assertTrue(e.getMessage().contains("setup command"), e.getMessage());
                });
    }

    /** P045, SEC-12: the customer makes a key, signs a licence with it and adds the key at the installation. */
    @Test
    void anInstallationCannotAddOrChangeATrustedKey(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        TestLicences own = new TestLicences("customer-key");
        java.nio.file.Path keys = dir.resolve("trusted-keys.json");
        java.nio.file.Files.writeString(keys, own.trustedKeys().toJson());
        for (String setting : List.of("platform.licensing.trusted-keys.customer-key="
                + Ed25519.encodePublic(own.keyPair().getPublic()), "platform.licensing.trusted-keys-file="
                + keys.toString().replace('\\', '/'))) {
            app(Db.migrated().dmlUser(), NOW, setting).run(ctx -> assertTrue(rootOf(ctx.getStartupFailure(),
                    LicensingConfigException.class).getMessage().contains("is no longer a setting"), setting));
        }
        // Without a key in the build, nothing an installation gives starts the app.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LicensingAutoConfiguration.class))
                .withPropertyValues("platform.licensing.product-code=" + PRODUCT, "platform.licensing.tenant-id=" + TENANT)
                .run(ctx -> assertTrue(rootOf(ctx.getStartupFailure(), LicensingConfigException.class).getMessage()
                        .contains("missing from the app's build")));
    }

    /** P045, SEC-13: a new installation's clock set back, with nothing stored, still reads the licence's issue time. */
    @Test
    void anEmptyStoreAndAClockSetBackDoNotReviveALicence() {
        DataSource ds = Db.migrated().dmlUser();
        store(ds, production("2026-01-01", "2026-10-01"));
        DataSource fresh = Db.migrated().dmlUser();
        new PostgresLicenceStore(fresh).put(com.documentplatform.licensing.LicenceStore.Slot.CURRENT,
                new PostgresLicenceStore(ds).get(com.documentplatform.licensing.LicenceStore.Slot.CURRENT).orElseThrow());
        app(fresh, Instant.parse("2026-02-01T00:00:00Z")).run(ctx -> assertEquals(LicenceState.EXPIRED_GRACE_OVER,
                ctx.getBean(LicenceService.class).status().state()));
    }

    @Test
    void startupRefusesAnUnknownKeyId() {
        Db db = Db.migrated();
        DataSource ds = db.dmlUser();
        store(ds, production("2026-01-01", "2027-12-31"));
        TestLicences other = new TestLicences("other-key-9");
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LicensingAutoConfiguration.class))
                .withBean(DataSource.class, () -> ds)
                .withBean(com.documentplatform.licensing.LicenceBuild.class, other::licenceBuild)
                .withPropertyValues("platform.licensing.product-code=" + PRODUCT, "platform.licensing.tenant-id=" + TENANT)
                .run(ctx -> assertEquals(LicenceRefusal.LICENCE_UNKNOWN_KEY_ID,
                        rootOf(ctx.getStartupFailure(), LicenceException.class).code()));
    }

    @Test
    void anEditedRowWhileRunningShowsDownInHealth() {
        Db db = Db.migrated();
        DataSource ds = db.dmlUser();
        store(ds, production("2026-01-01", "2027-12-31"));
        app(ds, NOW).run(ctx -> {
            db.exec("update lic_licence set file = replace(file, '\"amount\": 1000', '\"amount\": 9000')");
            Health h = ctx.getBean(LicensingHealthIndicator.class).health();
            assertEquals(Status.DOWN, h.getStatus());
            assertEquals("LICENCE_TAMPERED", h.getDetails().get("code"));
        });
    }

    @Test
    void missingTablesStopTheStartAndNothingIsCreated() {
        Db db = Db.empty();
        app(db.owner, NOW).run(ctx -> {
            LicensingSchemaException e = rootOf(ctx.getStartupFailure(), LicensingSchemaException.class);
            assertEquals(LicensingSchemaException.Problem.LIC_TABLES_MISSING, e.problem());
            FailureAnalysis a = new LicensingFailureAnalyzer().analyze(ctx.getStartupFailure());
            assertTrue(a.getAction().contains("history table lic_schema_history"), a.getAction());
        });
        assertEquals(List.of(), db.tables());
    }

    @Test
    void wrongConfigIsListedWithPropertyKeys() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LicensingAutoConfiguration.class))
                .withPropertyValues("platform.licensing.trusted-keys.k1=not-a-key", "platform.licensing.product-code=x y",
                        "platform.licensing.clock-tolerance=-1s", "platform.licensing.tenant-id=bad id")
                .run(ctx -> {
                    LicensingConfigException e = rootOf(ctx.getStartupFailure(), LicensingConfigException.class);
                    assertEquals(5, e.problems().size(), e.getMessage());
                    assertTrue(e.getMessage().contains("platform.licensing.trusted-keys is no longer a setting"));
                    assertTrue(e.getMessage().contains("platform.licensing.product-code"));
                    assertTrue(e.getMessage().contains("platform.licensing.clock-tolerance"));
                    assertTrue(e.getMessage().contains("platform.licensing.tenant-id"));
                    assertFalse(e.getMessage().contains("not-a-key"), "values are not echoed");
                });
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LicensingAutoConfiguration.class))
                .run(ctx -> assertEquals(3, rootOf(ctx.getStartupFailure(), LicensingConfigException.class).problems().size()));
    }

    @Test
    void appBeansReplaceTheStoresAndTheModuleCanBeSwitchedOff() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LicensingAutoConfiguration.class))
                .withPropertyValues("platform.licensing.enabled=false")
                .run(ctx -> assertTrue(ctx.getBeansOfType(LicenceService.class).isEmpty()));

        com.documentplatform.licensing.inmemory.InMemoryLicenceStore memory = new com.documentplatform.licensing.inmemory.InMemoryLicenceStore();
        com.documentplatform.licensing.inmemory.InMemoryLastSeenStore lastSeen = new com.documentplatform.licensing.inmemory.InMemoryLastSeenStore();
        new LicenceService(memory, new LicenceVerifier(licences.trustedKeys()),
                new ClockGuard(Clock.fixed(NOW, ZoneOffset.UTC), lastSeen), new InMemoryUsageLedger(), PRODUCT, null)
                .store(licences.sign(production("2026-01-01", "2027-12-31")));
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LicensingAutoConfiguration.class))
                .withBean(com.documentplatform.licensing.LicenceStore.class, () -> memory)
                .withBean(com.documentplatform.licensing.LastSeenStore.class, () -> lastSeen)
                .withBean(UsageLedger.class, InMemoryUsageLedger::new)
                .withBean(com.documentplatform.licensing.LicenceBuild.class, licences::licenceBuild)
                .withPropertyValues("platform.licensing.product-code=" + PRODUCT,
                        "platform.licensing.tenant-id=" + TENANT)
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure(), "no DataSource needed with the app's own stores");
                    assertEquals(LicenceState.VALID, ctx.getBean(LicenceService.class).status().state());
                });
    }

    @Test
    void aTestEnvironmentLicenceIsFlagged() {
        Db db = Db.migrated();
        DataSource ds = db.dmlUser();
        store(ds, licences.subscription(LicenceKind.NON_PRODUCTION, LocalDate.parse("2026-01-01"), LocalDate.parse("2027-12-31")));
        app(ds, NOW).run(ctx -> {
            assertTrue(ctx.getBean(LicenceService.class).testEnvironment());
            assertEquals(true, ctx.getBean(LicensingHealthIndicator.class).health().getDetails().get("testEnvironment"));
        });
    }
}
