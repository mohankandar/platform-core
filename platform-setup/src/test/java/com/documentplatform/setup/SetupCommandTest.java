package com.documentplatform.setup;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.testkit.InMemoryKeyService;
import com.documentplatform.identity.EntityUuid;
import com.documentplatform.identity.jdbc.PostgresEntityStore;
import com.documentplatform.identity.jdbc.PostgresLoginConnectionStore;
import com.documentplatform.identity.store.ChangeSource;
import com.documentplatform.identity.store.ConnectionChange;
import com.documentplatform.identity.store.EntityTimeZones;
import com.documentplatform.identity.store.LoginConnection;
import com.documentplatform.identity.store.TimeZoneOutcome;
import com.documentplatform.licensing.Allowance;
import com.documentplatform.licensing.AllowanceType;
import com.documentplatform.licensing.Deployment;
import com.documentplatform.licensing.EntitySlot;
import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.LicenceKind;
import com.documentplatform.licensing.LicenceType;
import com.documentplatform.licensing.inmemory.TestLicences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The setup command end to end, against Postgres, connecting as the DML-only app user, with the demo input files
 * (signed with the development key) copied into a temporary folder per test.
 */
class SetupCommandTest {

    static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");
    static final String PASSWORD = "Db-Pw-7f3c9e-NEVER-PRINTED";
    static final String TENANT = "TEN-DEMO-0001";
    static final String S1 = "ENT-DEMO-0001";
    static final String S2 = "ENT-DEMO-0002";
    static final String A = "PRODUCT_A";

    @TempDir
    Path dir;

    /** The key service setup reaches (in memory); a test can point setup elsewhere through {@link #connector}. */
    final InMemoryKeyService keys = new InMemoryKeyService();
    SetupKeys.Connector connector = env -> new SetupKeys.Connection(keys, () -> { });

    record Result(int exit, String out, String err) {
        String all() {
            return out + err;
        }
    }

    /** A copy of the demo files in the test's folder. */
    private Path demo() throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(Path.of("demo"))) {
            for (Path f : files) Files.copy(f, dir.resolve(f.getFileName()));
        }
        return dir;
    }

    private Result run(Pg db, String action, String product, String... extra) {
        return run(db.appEnv(PASSWORD), action, product, extra);
    }

    private Result run(Map<String, String> env, String action, String product, String... extra) {
        String suffix = product.equals(A) ? "a" : "b";
        List<String> args = new ArrayList<>(List.of(action,
                "--licence", dir.resolve("licence-demo.json").toString(),
                "--signin", dir.resolve("signin-demo.json").toString(),
                "--product", product,
                "--keys", dir.resolve("development-trusted-keys.json").toString(),
                "--product-roles", dir.resolve("product-roles-product-" + suffix + ".json").toString(),
                "--config-out", dir.resolve("platform-tenant.properties").toString(),
                "--run-log", dir.resolve("setup-runs.log").toString()));
        // Later values of an option replace earlier ones (so a test can point one option elsewhere).
        for (int i = 0; i < extra.length; i++) {
            String name = extra[i];
            if (name.startsWith("--") && i + 1 < extra.length && !extra[i + 1].startsWith("--")) {
                int at = args.indexOf(name);
                if (at >= 0) {
                    args.set(at + 1, extra[++i]);
                    continue;
                }
                args.add(name);
                args.add(extra[++i]);
            } else {
                args.add(name);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = new SetupCommand(env, Clock.fixed(NOW, ZoneOffset.UTC), connector).run(args,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private void write(String file, String text) throws IOException {
        Files.writeString(dir.resolve(file), text, StandardCharsets.UTF_8);
    }

    private String read(String file) throws IOException {
        return Files.readString(dir.resolve(file), StandardCharsets.UTF_8);
    }

    private static long rows(Pg db) {
        return db.count("select (select count(*) from lic_licence) + (select count(*) from idn_customer) "
                + "+ (select count(*) from idn_entity) + (select count(*) from idn_login_connection) "
                + "+ (select count(*) from idn_group_grant)");
    }

    @Test
    void theDemoFilesSetUpBothProductDatabasesAndARerunChangesNothing() throws Exception {
        demo();
        Pg dbA = Pg.migrated();
        Pg dbB = Pg.migrated();

        Result plan = run(dbA, "plan", A);
        assertEquals(0, plan.exit(), plan.all());
        assertTrue(plan.out().contains("create      customer " + TENANT), plan.out());
        assertTrue(plan.out().contains("create      grant ENT-DEMO-0001 PRODUCT_A: group \"Demo Finance Editors\" -> EDITOR"), plan.out());
        assertTrue(plan.out().contains("Plan only: nothing was changed."), plan.out());
        assertEquals(0, rows(dbA), "plan writes nothing");
        assertFalse(Files.exists(dir.resolve("platform-tenant.properties")), "plan writes no config fragment");

        Result first = run(dbA, "apply", A);
        assertEquals(0, first.exit(), first.all());
        assertTrue(first.out().contains("Setup done ("), first.out());
        assertEquals(List.of("LIC-DEMO-0001"), dbA.strings("select licence_id from lic_licence"));
        assertEquals(List.of("Demo Customer Ltd"), dbA.strings("select name from idn_customer"));
        assertEquals(List.of(S1 + " NOT_ACTIVATED " + EntityUuid.fromSlotId(S1), S2 + " NOT_ACTIVATED " + EntityUuid.fromSlotId(S2)),
                dbA.strings("select slot_id || ' ' || status || ' ' || entity_uuid from idn_entity order by 1"));
        assertEquals(List.of("PRODUCT_A demo-okta https://login.demo.example/oauth2/default api://demo-product-a ACTIVE 1"),
                dbA.strings("select product_code || ' ' || provider_id || ' ' || issuer_uri || ' ' || api_audience "
                        + "|| ' ' || status || ' ' || configuration_version from idn_login_connection"));
        assertEquals(List.of("SETUP setup"), dbA.strings("select change_source || ' ' || changed_by from idn_login_connection_history"));
        assertEquals(4, dbA.count("select count(*) from idn_group_grant where product_code = 'PRODUCT_A'"));
        assertTrue(read("platform-tenant.properties").contains("platform.licensing.tenant-id=" + TENANT));

        long before = rows(dbA);
        Result again = run(dbA, "apply", A);
        assertEquals(0, again.exit(), again.all());
        assertFalse(again.out().contains("  create ") || again.out().contains("  update "), again.out());
        assertTrue(again.out().contains("unchanged   licence LIC-DEMO-0001 v1"), again.out());
        assertTrue(again.out().contains("0 created, 0 updated, 0 removed"), again.out());
        assertEquals(before, rows(dbA), "a rerun changes nothing");
        assertEquals(1, dbA.count("select count(*) from idn_login_connection_history"), "no new connection version");

        // The second product's database, with the same input files.
        Result b = run(dbB, "apply", "PRODUCT_B");
        assertEquals(0, b.exit(), b.all());
        assertEquals(List.of("PRODUCT_B"), dbB.strings("select distinct product_code from idn_group_grant"));
        assertEquals(EntityUuid.fromSlotId(S1).toString(),
                dbB.strings("select entity_uuid::text from idn_entity where slot_id = '" + S1 + "'").get(0),
                "the same entity UUID in every product database");

        List<String> log = Files.readAllLines(dir.resolve("setup-runs.log"));
        assertEquals(4, log.size(), log.toString());
        assertTrue(log.get(1).contains("setup apply product=PRODUCT_A tenant=TEN-DEMO-0001 licence=LIC-DEMO-0001/v1 "
                + "result=DONE exit=0 create=10"), log.get(1));
        assertTrue(log.get(0).contains("result=PLANNED"), log.get(0));
    }

    @Test
    void aRerunAfterAMidRunFailureFinishesWhatWasMissing() throws Exception {
        demo();
        Pg db = Pg.migrated();
        // The grants step fails (the last database step), after the licence, customer, slots and connection.
        db.exec("create function fail_grant() returns trigger language plpgsql as "
                        + "$$ begin raise exception 'grant insert failed on purpose'; end $$",
                "create trigger fail_grant before insert on idn_group_grant for each row execute function fail_grant()");
        Result failed = run(db, "apply", A);
        assertEquals(SetupCommand.EXIT_FAILED, failed.exit(), failed.all());
        assertTrue(failed.err().startsWith("SETUP_FAILED: "), failed.err());
        assertEquals(0, rows(db), "the whole database part was rolled back, the licence too");
        assertEquals(0, db.count("select count(*) from lic_last_seen"));
        assertFalse(Files.exists(dir.resolve("platform-tenant.properties")));

        db.exec("drop trigger fail_grant on idn_group_grant");
        Result rerun = run(db, "apply", A);
        assertEquals(0, rerun.exit(), rerun.all());
        assertEquals(4, db.count("select count(*) from idn_group_grant"));
        assertEquals(1, db.count("select count(*) from lic_licence"));

        // The config fragment step fails after the commit: the rerun writes it and changes nothing else.
        Pg db2 = Pg.migrated();
        Path missingFolder = dir.resolve("no-such-folder").resolve("platform-tenant.properties");
        Result noConfig = run(db2, "apply", A, "--config-out", missingFolder.toString());
        assertEquals(SetupCommand.EXIT_FAILED, noConfig.exit(), noConfig.all());
        assertTrue(noConfig.err().startsWith("SETUP_CONFIG_NOT_WRITTEN: "), noConfig.err());
        long committed = rows(db2);
        assertTrue(committed > 0, "the database part is committed");
        Files.createDirectories(missingFolder.getParent());
        Result finish = run(db2, "apply", A, "--config-out", missingFolder.toString());
        assertEquals(0, finish.exit(), finish.all());
        assertTrue(finish.out().contains("create      config platform-tenant.properties"), finish.out());
        assertTrue(finish.out().contains("0 updated"), finish.out());
        assertEquals(committed, rows(db2));
        assertTrue(Files.readString(missingFolder).contains("platform.licensing.tenant-id=" + TENANT));
    }

    @Test
    void thePlanShowsRemovalsFirstAndGrantsAreRemovedOnlyWithTheFlag() throws Exception {
        demo();
        Pg db = Pg.migrated();
        assertEquals(0, run(db, "apply", A).exit());
        write("role-mapping-product-a.csv", "# Product: PRODUCT_A\nentity_id,entity_name,group,role\n"
                + "ENT-DEMO-0001,,Demo Finance Readers,READER\nENT-DEMO-0001,,Demo Finance Editors,EDITOR\n"
                + "ENT-DEMO-0002,,Demo Legal Readers,READER\n"); // the auditors row is gone
        String grant = "grant ENT-DEMO-0002 PRODUCT_A: group \"Demo Auditors\" -> AUDITOR (not in the mapping file)";

        Result plan = run(db, "plan", A);
        assertEquals(SetupCommand.EXIT_NEEDS_FLAG, plan.exit(), plan.all());
        assertTrue(plan.out().contains("not removed " + grant + "; kept. Run again with --remove-unlisted-grants"),
                plan.out());
        Result keep = run(db, "apply", A);
        assertEquals(SetupCommand.EXIT_NEEDS_FLAG, keep.exit(), keep.all());
        assertTrue(keep.out().contains("Setup done except 1 item(s)"), keep.out());
        assertEquals(4, db.count("select count(*) from idn_group_grant"), "not removed without the flag");

        Result planRemove = run(db, "plan", A, "--remove-unlisted-grants");
        assertEquals(0, planRemove.exit(), planRemove.all());
        assertTrue(planRemove.out().contains("remove      " + grant), planRemove.out());
        assertEquals(4, db.count("select count(*) from idn_group_grant"), "the plan shows it before it happens");

        Result remove = run(db, "apply", A, "--remove-unlisted-grants");
        assertEquals(0, remove.exit(), remove.all());
        assertEquals(3, db.count("select count(*) from idn_group_grant"));
        assertEquals(0, db.count("select count(*) from idn_group_grant where external_group = 'Demo Auditors'"));
        assertTrue(Files.readAllLines(dir.resolve("setup-runs.log")).get(4).contains(" remove=1 "),
                "the run log counts it");
    }

    @Test
    void aConnectionChangedInTheAppIsAConflictUntilReplaceConnectionIsGiven() throws Exception {
        demo();
        Pg db = Pg.migrated();
        assertEquals(0, run(db, "apply", A).exit());
        PostgresLoginConnectionStore store = new PostgresLoginConnectionStore(db.appDataSource(PASSWORD));
        LoginConnection setupMade = store.active(TENANT, A).orElseThrow();
        LoginConnection appMade = new LoginConnection(setupMade.connectionId(), TENANT, A, setupMade.providerId(),
                setupMade.providerType(), setupMade.issuer(), setupMade.browserClientId(), "api://changed-in-app",
                null, setupMade.status(), 2);
        store.update(appMade, 1, ChangeSource.APP, "it-admin-1").orElseThrow();

        Result conflict = run(db, "apply", A);
        assertEquals(SetupCommand.EXIT_NEEDS_FLAG, conflict.exit(), conflict.all());
        assertTrue(conflict.out().contains("conflict    login connection PRODUCT_A (version 2) was changed in the app "
                + "since the last setup (apiAudience differ from the sign-in file); left as it is."), conflict.out());
        assertEquals("api://changed-in-app", store.active(TENANT, A).orElseThrow().apiAudience(), "left alone");

        Result replaced = run(db, "apply", A, "--replace-connection");
        assertEquals(0, replaced.exit(), replaced.all());
        assertTrue(replaced.out().contains("update      login connection PRODUCT_A (version 2) -> version 3: "
                + "apiAudience (replaces the change made in the app)"), replaced.out());
        assertEquals("api://demo-product-a", store.active(TENANT, A).orElseThrow().apiAudience());
        List<ConnectionChange> history = store.history(setupMade.connectionId());
        assertEquals(List.of(ChangeSource.SETUP, ChangeSource.APP, ChangeSource.SETUP),
                history.stream().map(ConnectionChange::source).toList());

        // A change made by setup itself is updated without the flag.
        write("signin-demo.json", read("signin-demo.json").replace("demo-product-a-web", "demo-product-a-web-2"));
        Result update = run(db, "apply", A);
        assertEquals(0, update.exit(), update.all());
        assertTrue(update.out().contains("update      login connection PRODUCT_A (version 3) -> version 4: browserClientId"),
                update.out());
    }

    /** The demo sign-in file with time zones added at its end (raw JSON members). */
    private void withTimeZones(String members) throws IOException {
        write("signin-demo.json", read("signin-demo.json").replaceFirst("\\}\\s*\\z", ", " + members + "}\n"));
    }

    @Test
    void timeZonesFromTheSignInFileAreDefaultsAndARerunKeepsWhatTheAppSet() throws Exception {
        demo();
        Pg db = Pg.migrated();
        withTimeZones("\"timeZone\": \"America/New_York\", \"entityTimeZones\": {\"" + S1 + "\": \"America/Chicago\"}");

        Result plan = run(db, "plan", A);
        assertEquals(0, plan.exit(), plan.all());
        assertTrue(plan.out().contains("create      time zone of customer " + TENANT + ": America/New_York"), plan.out());
        assertTrue(plan.out().contains("create      time zone of entity slot " + S1 + ": America/Chicago"), plan.out());
        assertEquals(0, rows(db));

        Result first = run(db, "apply", A);
        assertEquals(0, first.exit(), first.all());
        assertEquals(List.of("America/New_York"), db.strings("select time_zone from idn_customer"));
        assertEquals(List.of(S1 + " America/Chicago", S2 + " -"),
                db.strings("select slot_id || ' ' || coalesce(time_zone, '-') from idn_entity order by 1"));
        EntityTimeZones zones = new EntityTimeZones(new PostgresEntityStore(db.appDataSource(PASSWORD)));
        assertEquals(java.util.Optional.of(java.time.ZoneId.of("America/New_York")),
                zones.effective(EntityUuid.fromSlotId(S2)), "S2 follows the customer's zone");

        Result again = run(db, "apply", A);
        assertEquals(0, again.exit(), again.all());
        assertTrue(again.out().contains("unchanged   time zone of entity slot " + S1 + ": America/Chicago"), again.out());
        assertTrue(again.out().contains("0 created, 0 updated, 0 removed"), again.out());

        // The app changes S1's zone; setup keeps it.
        assertEquals(TimeZoneOutcome.Result.SET, zones.setEntityZone(S1, "America/Denver", "admin-1").result());
        Result kept = run(db, "apply", A);
        assertEquals(0, kept.exit(), kept.all());
        assertTrue(kept.out().contains("unchanged   time zone of entity slot " + S1 + ": America/Denver kept (already "
                + "set; the sign-in file's America/Chicago is a default only)"), kept.out());
        assertEquals(List.of("America/Denver"), db.strings("select time_zone from idn_entity where slot_id = '" + S1
                + "'"));
    }

    @Test
    void anInvalidTimeZoneOrOneForASlotOutsideTheLicenceIsRefused() throws Exception {
        demo();
        Pg db = Pg.migrated();
        String signin = read("signin-demo.json");
        withTimeZones("\"entityTimeZones\": {\"" + S1 + "\": \"-05:00\"}");
        Result offset = run(db, "apply", A);
        assertEquals(SetupCommand.EXIT_REFUSED, offset.exit(), offset.all());
        assertTrue(offset.err().startsWith("SETUP_TIME_ZONE_INVALID: The sign-in file is not valid: entityTimeZones."
                + S1 + " is not an IANA time zone name"), offset.err());
        assertEquals(0, rows(db));

        write("signin-demo.json", signin);
        withTimeZones("\"timeZone\": \"New York\"");
        assertTrue(run(db, "apply", A).err().startsWith("SETUP_TIME_ZONE_INVALID"));

        write("signin-demo.json", signin);
        withTimeZones("\"entityTimeZones\": {\"ENT-DEMO-0099\": \"UTC\"}");
        Result outside = run(db, "apply", A);
        assertEquals(SetupCommand.EXIT_REFUSED, outside.exit(), outside.all());
        assertTrue(outside.err().startsWith("SETUP_TIME_ZONE_SLOT_NOT_IN_LICENCE"), outside.err());
        assertEquals(0, rows(db));
    }

    @Test
    void aSignInFileOfAnotherCustomerIsRefused() throws Exception {
        demo();
        Pg db = Pg.migrated();
        write("signin-demo.json", read("signin-demo.json").replace("TEN-DEMO-0001", "TEN-OTHER-0001"));
        Result r = run(db, "apply", A);
        assertEquals(SetupCommand.EXIT_REFUSED, r.exit(), r.all());
        assertTrue(r.err().startsWith("SETUP_WRONG_TENANT: The sign-in file is for customer TEN-OTHER-0001; the "
                + "licence LIC-DEMO-0001 is for TEN-DEMO-0001."), r.err());
        assertTrue(r.err().contains("What to do: "), r.err());
        assertEquals(0, rows(db));
        assertTrue(Files.readString(dir.resolve("setup-runs.log")).contains("result=REFUSED exit=2 code=SETUP_WRONG_TENANT"));
    }

    @Test
    void anUnknownRoleRefusesTheWholeFileAndNothingIsWritten() throws Exception {
        demo();
        Pg db = Pg.migrated();
        write("role-mapping-product-a.csv", read("role-mapping-product-a.csv")
                + "ENT-DEMO-0002,,Demo Directors,DIRECTOR\nENT-DEMO-9999,,Demo Others,READER\n");
        Result r = run(db, "apply", A);
        assertEquals(SetupCommand.EXIT_REFUSED, r.exit(), r.all());
        assertTrue(r.err().startsWith("SETUP_MAPPING_INVALID: The role mapping file role-mapping-product-a.csv has 2 "
                + "error(s) [UNKNOWN_ROLE, UNKNOWN_ENTITY]."), r.err());
        assertTrue(r.err().contains("line 9: unknown role \"DIRECTOR\""), r.err());
        assertTrue(r.err().contains("line 10: entity slot ENT-DEMO-9999 is not in the licence"), r.err());
        assertEquals(0, rows(db), "refused before any write");

        write("role-mapping-product-a.csv", "entity_id,entity_name,group,role\nENT-DEMO-0001,,Demo Admins,PLATFORM_ADMIN\n"
                + "ENT-DEMO-0001,,Demo Readers,reader\n");
        Result platform = run(db, "plan", A);
        assertEquals(SetupCommand.EXIT_REFUSED, platform.exit());
        assertTrue(platform.err().contains("[PLATFORM_ROLE, ROLE_NOT_WRITTEN_CANONICALLY]"), platform.err());
    }

    @Test
    void theDevelopmentKeyLicenceIsRefusedByProductionTrustedKeys() throws Exception {
        demo();
        Pg db = Pg.migrated();
        // A production install's keys file lists our production keys only.
        write("production-trusted-keys.json", new TestLicences("lic-2026:1").trustedKeys().toJson());
        Result r = run(db, "apply", A, "--keys", dir.resolve("production-trusted-keys.json").toString());
        assertEquals(SetupCommand.EXIT_REFUSED, r.exit(), r.all());
        assertTrue(r.err().startsWith("SETUP_LICENCE_REFUSED: LICENCE_UNKNOWN_KEY_ID"), r.err());
        assertEquals(0, rows(db));

        // Even a production keys file with a key under the development key's ID: the signature does not match.
        write("production-trusted-keys.json", new TestLicences(DemoFilesGenerator.DEV_KEY_ID).trustedKeys().toJson());
        Result forged = run(db, "apply", A, "--keys", dir.resolve("production-trusted-keys.json").toString());
        assertEquals(SetupCommand.EXIT_REFUSED, forged.exit());
        assertTrue(forged.err().startsWith("SETUP_LICENCE_REFUSED: LICENCE_FORGED"), forged.err());
    }

    @Test
    void theDmlOnlyUserRunsSetupAndSetupNeverCreatesTables() throws Exception {
        demo();
        Pg empty = Pg.empty();
        Result r = run(Map.of(SetupCommand.DB_URL, empty.url(), SetupCommand.DB_USER, empty.owner.getUser(),
                SetupCommand.DB_PASSWORD, empty.owner.getPassword()), "apply", A);
        assertEquals(SetupCommand.EXIT_REFUSED, r.exit(), r.all());
        assertTrue(r.err().startsWith("SETUP_TABLES_NOT_READY: LIC_TABLES_MISSING"), r.err());
        assertEquals(List.of(), empty.tables(), "nothing created, even as the schema owner");

        Pg db = Pg.migrated();
        Map<String, String> dml = db.appEnv(PASSWORD);
        Result ok = run(dml, "apply", A);
        assertEquals(0, ok.exit(), ok.all());
        try (Connection c = db.appDataSource(PASSWORD).getConnection();
             PreparedStatement ps = c.prepareStatement("create table setup_probe (id int)")) {
            assertThrows(java.sql.SQLException.class, ps::execute, "the user setup ran as cannot create tables");
        }
    }

    @Test
    void aSlotDroppedFromANewerLicenceIsKeptAsNotInLicence() throws Exception {
        demo();
        Pg db = Pg.migrated();
        TestLicences keys = new TestLicences("test-key-1");
        write("test-trusted-keys.json", keys.trustedKeys().toJson());
        write("licence-demo.json", keys.sign(licence("LIC-TEST-0100", 1, List.of(S1, S2))));
        assertEquals(0, run(db, "apply", A, "--keys", dir.resolve("test-trusted-keys.json").toString()).exit());

        write("licence-demo.json", keys.sign(licence("LIC-TEST-0101", 2, List.of(S1))));
        write("role-mapping-product-a.csv", "entity_id,entity_name,group,role\nENT-DEMO-0001,,Demo Finance Readers,READER\n");
        Result r = run(db, "apply", A, "--keys", dir.resolve("test-trusted-keys.json").toString(),
                "--remove-unlisted-grants");
        assertEquals(0, r.exit(), r.all());
        assertTrue(r.out().contains("update      licence LIC-TEST-0101 v2 replaces LIC-TEST-0100 v1"), r.out());
        assertTrue(r.out().contains("update      entity slot ENT-DEMO-0002 NOT_ACTIVATED -> NOT_IN_LICENCE (kept, never deleted"),
                r.out());
        assertEquals(List.of(S1 + " NOT_ACTIVATED", S2 + " NOT_IN_LICENCE"),
                db.strings("select slot_id || ' ' || status from idn_entity order by 1"), "never deleted");

        // An older licence is refused before anything is written.
        write("licence-demo.json", keys.sign(licence("LIC-TEST-0100", 1, List.of(S1, S2))));
        Result older = run(db, "apply", A, "--keys", dir.resolve("test-trusted-keys.json").toString());
        assertEquals(SetupCommand.EXIT_REFUSED, older.exit());
        assertTrue(older.err().startsWith("SETUP_LICENCE_OLDER: "), older.err());
    }

    private static Licence licence(String id, long version, List<String> slots) {
        List<EntitySlot> s = slots.stream().map(slot -> new EntitySlot(slot,
                List.of(new Allowance(A, AllowanceType.PAGES_PER_MONTH, 100)))).toList();
        return new Licence(id, version, "Demo Customer Ltd", TENANT, List.of(A, "PRODUCT_B"), s,
                LicenceType.SUBSCRIPTION, LicenceKind.PRODUCTION, LocalDate.parse("2026-01-01"),
                LocalDate.parse("2027-12-31"), null, Deployment.ON_PREM, "test-key-1", NOW);
    }

    @Test
    void aDatabaseOfAnotherCustomerOrAConfigOfAnotherCustomerIsRefused() throws Exception {
        demo();
        Pg db = Pg.migrated();
        db.exec("insert into idn_customer (tenant_id, name) values ('TEN-SOMEONE-ELSE', 'Someone Else')");
        Result r = run(db, "plan", A);
        assertEquals(SetupCommand.EXIT_REFUSED, r.exit(), r.all());
        assertTrue(r.err().startsWith("SETUP_OTHER_CUSTOMER_INSTALLED: "), r.err());

        Pg clean = Pg.migrated();
        write("platform-tenant.properties", "platform.licensing.tenant-id=TEN-SOMEONE-ELSE\n");
        Result config = run(clean, "apply", A);
        assertEquals(SetupCommand.EXIT_REFUSED, config.exit(), config.all());
        assertTrue(config.err().startsWith("SETUP_CONFIG_OTHER_TENANT: "), config.err());
        assertEquals(0, rows(clean));
    }

    @Test
    void twoRunsAtOnceDoNotInterleave() throws Exception {
        demo();
        Pg db = Pg.migrated();
        Map<String, String> env = db.appEnv(PASSWORD);
        try (Connection holder = db.ownerConnection()) {
            try (PreparedStatement ps = holder.prepareStatement("select pg_advisory_lock(?)")) {
                ps.setLong(1, SetupRun.LOCK_KEY);
                ps.execute();
            }
            CompletableFuture<Result> waiting = CompletableFuture.supplyAsync(() -> run(env, "apply", A));
            assertThrows(TimeoutException.class, () -> waiting.get(1500, TimeUnit.MILLISECONDS),
                    "a second run waits for the lock");
            assertEquals(0, rows(db));
            try (PreparedStatement ps = holder.prepareStatement("select pg_advisory_unlock(?)")) {
                ps.setLong(1, SetupRun.LOCK_KEY);
                ps.execute();
            }
            assertEquals(0, waiting.get(30, TimeUnit.SECONDS).exit());
        }
    }

    @Test
    void noSecretInAnyOutput() throws Exception {
        demo();
        Pg db = Pg.migrated();
        List<String> outputs = new ArrayList<>();
        String pastedSecret = "client-secret-value-9a8b7c";

        Result ok = run(db, "apply", A);
        assertEquals(0, ok.exit(), ok.all());
        outputs.add(ok.all());

        Map<String, String> wrongPassword = new java.util.HashMap<>(db.appEnv(PASSWORD));
        wrongPassword.put(SetupCommand.DB_PASSWORD, "Wrong-Pw-55e1-NEVER-PRINTED");
        Result unreachable = run(wrongPassword, "plan", A);
        assertEquals(SetupCommand.EXIT_REFUSED, unreachable.exit(), unreachable.all());
        assertTrue(unreachable.err().startsWith("SETUP_DB_UNREACHABLE: "), unreachable.err());
        outputs.add(unreachable.all());

        Map<String, String> passwordInUrl = new java.util.HashMap<>(db.appEnv(PASSWORD));
        passwordInUrl.put(SetupCommand.DB_URL, "jdbc:postgresql://127.0.0.1:1/nodb?password=" + PASSWORD);
        Result noDb = run(passwordInUrl, "plan", A);
        assertEquals(SetupCommand.EXIT_REFUSED, noDb.exit(), noDb.all());
        outputs.add(noDb.all());

        String signin = read("signin-demo.json");
        write("signin-demo.json", signin.replace("\"browserClientId\": \"demo-product-a-web\",",
                "\"browserClientId\": \"demo-product-a-web\", \"clientSecret\": \"" + pastedSecret + "\","));
        Result pasted = run(db, "apply", A);
        assertEquals(SetupCommand.EXIT_REFUSED, pasted.exit(), pasted.all());
        assertTrue(pasted.err().contains("SETUP_SIGNIN_INVALID") && pasted.err().contains("[clientSecret]"), pasted.err());
        outputs.add(pasted.all());
        write("signin-demo.json", signin);

        outputs.add(read("setup-runs.log"));
        outputs.add(read("platform-tenant.properties"));
        outputs.addAll(db.strings("select coalesce(secret_reference, '') from idn_login_connection"));
        for (String text : outputs) {
            for (String secret : List.of(PASSWORD, "Wrong-Pw-55e1-NEVER-PRINTED", pastedSecret)) {
                assertFalse(text.contains(secret), "a secret was printed: " + text);
            }
        }
        assertFalse(read("setup-runs.log").contains("Demo Finance"), "the run log holds IDs and codes, no group names");
    }

    @Test
    void wrongOptionsAndHubModeAreRefused() throws Exception {
        demo();
        Pg db = Pg.migrated();
        assertEquals(SetupCommand.EXIT_REFUSED, run(db, "install", A).exit());
        Result unknown = run(db, "apply", A, "--db-password", "value-not-shown-42");
        assertTrue(unknown.err().startsWith("SETUP_BAD_OPTION: Unknown option '--db-password'"), unknown.err());
        assertFalse(unknown.err().contains("value-not-shown-42"), "values of unknown options are not shown");

        Result notInLicence = run(db, "apply", "PRODUCT_C", "--product-roles", dir.resolve("product-roles-product-a.json").toString());
        assertTrue(notInLicence.err().startsWith("SETUP_PRODUCT_NOT_IN_LICENCE: "), notInLicence.err());

        Result wrongRoles = run(db, "apply", A, "--product-roles", dir.resolve("product-roles-product-b.json").toString());
        assertTrue(wrongRoles.err().startsWith("SETUP_PRODUCT_ROLES_INVALID: "), wrongRoles.err());

        write("signin-demo.json", read("signin-demo.json").replace("\"direct\"", "\"hub\""));
        Result hub = run(db, "apply", A);
        assertEquals(SetupCommand.EXIT_REFUSED, hub.exit());
        assertTrue(hub.err().startsWith("SETUP_MODE_NOT_SUPPORTED: "), hub.err());

        Result noDb = run(Map.of(), "apply", A);
        assertTrue(noDb.err().startsWith("SETUP_SIGNIN_INVALID") || noDb.err().startsWith("SETUP_MODE_NOT_SUPPORTED"),
                "files are checked before the database settings");
        assertEquals(0, rows(db));
    }

    @Test
    void missingDatabaseSettingsAreNamed() throws Exception {
        demo();
        Result r = run(Map.of(SetupCommand.DB_URL, "jdbc:postgresql://localhost/x"), "plan", A);
        assertEquals(SetupCommand.EXIT_REFUSED, r.exit());
        assertTrue(r.err().startsWith("SETUP_DB_SETTINGS_MISSING: Not set in the environment: PLATFORM_SETUP_DB_USER, "
                + "PLATFORM_SETUP_DB_PASSWORD."), r.err());
    }

    @Test
    void theAppActivatesASlotAfterSetupAndARerunLeavesItAlone() throws Exception {
        demo();
        Pg db = Pg.migrated();
        assertEquals(0, run(db, "apply", A).exit());
        db.exec("update idn_entity set status = 'ACTIVE', display_name = 'Demo Finance', activated_by = 'admin-1', "
                + "activated_at = now() where slot_id = '" + S1 + "'");
        Result r = run(db, "apply", A);
        assertEquals(0, r.exit(), r.all());
        assertTrue(r.out().contains("unchanged   entity slot ENT-DEMO-0001 (ACTIVE)"), r.out());
        assertEquals(List.of("ACTIVE Demo Finance"), db.strings("select status || ' ' || display_name from idn_entity "
                + "where slot_id = '" + S1 + "'"), "activation and name are never touched");
        // Now a mapping row may name it, and must name it right.
        write("role-mapping-product-a.csv", read("role-mapping-product-a.csv").replace("ENT-DEMO-0001,,Demo Finance Readers",
                "ENT-DEMO-0001,Wrong Name,Demo Finance Readers"));
        Result wrongName = run(db, "plan", A);
        assertTrue(wrongName.err().contains("ENTITY_NAME_MISMATCH"), wrongName.err());
        assertNotNull(UUID.fromString(db.strings("select entity_uuid::text from idn_entity limit 1").get(0)));
        assertEquals(URI.create("https://login.demo.example/oauth2/default").toString(),
                db.strings("select distinct issuer_uri from idn_group_grant").get(0));
    }

    // ---- the customer's keys ----

    @Test
    void applyCreatesTheCustomersKeysAndARerunLeavesThem() throws Exception {
        demo();
        Pg db = Pg.migrated();
        CustomerId customer = CustomerId.fromTenantId(TENANT);

        Result plan = run(db, "plan", A);
        assertEquals(0, plan.exit(), plan.all());
        assertTrue(plan.out().contains("  create      customer keys " + TENANT + " (" + customer + ") in key service "
                + InMemoryKeyService.ID), plan.out());
        assertEquals(CryptoError.CRYPTO_KEY_MISSING, assertThrows(CryptoException.class,
                () -> keys.wrappingKey(customer)).code(), "plan creates no keys");

        Result apply = run(db, "apply", A);
        assertEquals(0, apply.exit(), apply.all());
        assertEquals(new com.documentplatform.crypto.KeyService.KeyVersions(1, 1), keys.wrappingKey(customer));
        assertEquals(new com.documentplatform.crypto.KeyService.KeyVersions(1, 1), keys.fingerprintKey(customer));
        assertTrue(Files.readAllLines(dir.resolve("setup-runs.log")).get(1).endsWith(" keys=created"));

        // A rotated key stays as it is: a rerun (this product or the next) never makes new keys.
        keys.rotate(customer);
        Result again = run(db, "apply", A);
        assertEquals(0, again.exit(), again.all());
        assertTrue(again.out().contains("  unchanged   customer keys " + TENANT), again.out());
        assertEquals(2, keys.wrappingKey(customer).latest());
        Result b = run(Pg.migrated(), "apply", "PRODUCT_B");
        assertEquals(0, b.exit(), b.all());
        assertEquals(2, keys.wrappingKey(customer).latest());
        assertTrue(Files.readAllLines(dir.resolve("setup-runs.log")).get(3).endsWith(" keys=present"));
    }

    @Test
    void aKeyServiceProblemRefusesTheRunBeforeTheDatabaseChanges() throws Exception {
        demo();
        Pg db = Pg.migrated();
        keys.down(true);
        Result down = run(db, "apply", A);
        assertEquals(SetupCommand.EXIT_REFUSED, down.exit(), down.all());
        assertTrue(down.err().contains("SETUP_KEY_SERVICE_FAILED") && down.err().contains("CRYPTO_KEY_SERVICE_UNREACHABLE"),
                down.err());
        assertEquals(0, rows(db), "nothing written to the database");

        keys.down(false).denied(true);
        Result denied = run(db, "apply", A);
        assertTrue(denied.err().contains("CRYPTO_PERMISSION_DENIED"), denied.err());
        assertEquals(0, rows(db));

        // --no-customer-keys: the step is skipped, everything else is done.
        Result skipped = run(db, "apply", A, "--no-customer-keys");
        assertEquals(0, skipped.exit(), skipped.all());
        assertTrue(skipped.out().contains("  skipped     customer keys " + TENANT), skipped.out());
        assertTrue(rows(db) > 0);
    }

    @Test
    void theVaultSettingsComeFromTheEnvironmentAndAreNeverPrinted() throws Exception {
        demo();
        Pg db = Pg.migrated();
        connector = SetupKeys::vault;
        Result missing = run(db, "apply", A);
        assertEquals(SetupCommand.EXIT_REFUSED, missing.exit(), missing.all());
        assertTrue(missing.err().contains("SETUP_KEY_SERVICE_SETTINGS_MISSING") && missing.err().contains(
                SetupKeys.VAULT_ADDR), missing.err());

        Map<String, String> env = new java.util.HashMap<>(db.appEnv(PASSWORD));
        env.put(SetupKeys.VAULT_ADDR, "http://127.0.0.1:9");
        Result noLogin = run(env, "apply", A);
        assertTrue(noLogin.err().contains("No key service login"), noLogin.err());

        String token = "hvs.SETUP-TOKEN-NEVER-PRINTED-8c1f";
        env.put(SetupKeys.VAULT_TOKEN, token);
        env.put(SetupKeys.VAULT_ROLE_ID, "role-id-x");
        Result two = run(env, "apply", A);
        assertTrue(two.err().contains("More than one key service login"), two.err());

        env.remove(SetupKeys.VAULT_ROLE_ID);
        Result unreachable = run(env, "apply", A);
        assertEquals(SetupCommand.EXIT_REFUSED, unreachable.exit(), unreachable.all());
        assertTrue(unreachable.err().contains("CRYPTO_KEY_SERVICE_UNREACHABLE"), unreachable.err());
        assertEquals(0, rows(db));
        for (Result r : List.of(missing, noLogin, two, unreachable)) {
            assertFalse(r.all().contains(token) || r.all().contains("role-id-x"), r.all());
        }
        assertFalse(read("setup-runs.log").contains(token));
    }
}
