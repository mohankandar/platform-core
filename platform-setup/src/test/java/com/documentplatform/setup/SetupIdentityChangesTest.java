package com.documentplatform.setup;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.testkit.InMemoryKeyService;
import com.documentplatform.identity.IdentityConnectionStatus;
import com.documentplatform.identity.jdbc.PostgresGroupGrantStore;
import com.documentplatform.identity.jdbc.PostgresLoginConnectionStore;
import com.documentplatform.identity.mapping.Grant;
import com.documentplatform.identity.signin.DevelopmentLicence;
import com.documentplatform.identity.signin.OidcTestSignIn;
import com.documentplatform.identity.store.ChangeSource;
import com.documentplatform.identity.store.ConnectionOutcome;
import com.documentplatform.identity.store.ConnectionValues;
import com.documentplatform.identity.store.LoginConnection;
import com.documentplatform.identity.store.LoginConnections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.Map;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P031 in the setup command: local test issuers (B2), role aliases (B3), the grant history (B4), the customer-keys
 * guard (B5), and the app's login-connection drafts left alone (B1). Against Postgres, as the DML-only user, with the
 * demo files.
 */
class SetupIdentityChangesTest {

    static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    static final String PASSWORD = "Db-Pw-31a7-NEVER-PRINTED";
    static final String TENANT = "TEN-DEMO-0001";
    static final String S1 = "ENT-DEMO-0001";
    static final String A = "PRODUCT_A";
    static final URI ISSUER = URI.create("https://login.demo.example/oauth2/default");
    static final Pattern RUN = Pattern.compile("run ([0-9a-f-]{36})\\.");

    @TempDir
    Path dir;

    final InMemoryKeyService keys = new InMemoryKeyService();

    record Result(int exit, String out, String err) {
        String all() {
            return out + err;
        }

        String runId() {
            Matcher m = RUN.matcher(out);
            assertTrue(m.find(), out);
            return m.group(1);
        }
    }

    private void demo() throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(Path.of("demo"))) {
            for (Path f : files) Files.copy(f, dir.resolve(f.getFileName()));
        }
    }

    private Result run(Pg db, String action, String... extra) {
        List<String> args = new ArrayList<>(List.of(action,
                "--licence", dir.resolve("licence-demo.json").toString(),
                "--signin", dir.resolve("signin-demo.json").toString(),
                "--product", A,
                "--keys", dir.resolve("development-trusted-keys.json").toString(),
                "--product-roles", dir.resolve("product-roles-product-a.json").toString(),
                "--config-out", dir.resolve("platform-tenant.properties").toString(),
                "--run-log", dir.resolve("setup-runs.log").toString()));
        for (int i = 0; i < extra.length; i++) {
            int at = args.indexOf(extra[i]);
            if (at >= 0 && i + 1 < extra.length && !extra[i + 1].startsWith("--")) {
                args.set(at + 1, extra[++i]);
            } else {
                args.add(extra[i]);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = new SetupCommand(db.appEnv(PASSWORD), Clock.fixed(NOW, ZoneOffset.UTC),
                env -> new SetupKeys.Connection(keys, () -> { }), TestBuild.take(args)).run(args,
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
                + "+ (select count(*) from idn_group_grant) + (select count(*) from idn_grant_history)");
    }

    private void issuer(String uri) throws IOException {
        write("signin-demo.json", read("signin-demo.json").replace(ISSUER.toString(), uri));
    }

    // ---- B2 local test issuers

    @Test
    void anHttpIssuerOnThisMachineIsAcceptedWithTheDevelopmentKeyLicence() throws Exception {
        demo();
        issuer("http://localhost:8080/realms/demo");
        Pg db = Pg.migrated();
        Result r = run(db, "apply");
        assertEquals(0, r.exit(), r.all());
        assertEquals(URI.create("http://localhost:8080/realms/demo"), new PostgresLoginConnectionStore(
                db.appDataSource(PASSWORD)).active(TENANT, A).orElseThrow().issuer());
    }

    @Test
    void anHttpIssuerIsRefusedWithoutADevelopmentKeyOrOffThisMachine() throws Exception {
        demo();
        Pg db = Pg.migrated();
        // The same key, not marked as a development key.
        write("plain-keys.json", read("development-trusted-keys.json").replaceAll("\\s*\"development\": true,", ""));
        issuer("http://127.0.0.1:8080/realms/demo");
        Result notDev = run(db, "apply", "--keys", dir.resolve("plain-keys.json").toString());
        assertEquals(SetupCommand.EXIT_REFUSED, notDev.exit(), notDev.all());
        assertTrue(notDev.err().contains("SETUP_HTTP_ISSUER_NOT_ALLOWED") && notDev.err().contains("is not signed by a "
                + "development key"), notDev.err());
        assertTrue(read("setup-runs.log").contains("code=SETUP_HTTP_ISSUER_NOT_ALLOWED"));
        assertEquals(0, rows(db));
        // https with the same keys file works as before.
        demo2();
        assertEquals(0, run(db, "apply", "--keys", dir.resolve("plain-keys.json").toString()).exit());

        Pg other = Pg.migrated();
        demo2();
        issuer("http://login.demo.example/oauth2/default");
        Result remote = run(other, "apply");
        assertEquals(SetupCommand.EXIT_REFUSED, remote.exit(), remote.all());
        assertTrue(remote.err().contains("SETUP_HTTP_ISSUER_NOT_ALLOWED") && remote.err().contains("not on this machine"),
                remote.err());
        assertEquals(0, rows(other));
        demo2();
        issuer("ftp://login.demo.example/oauth2/default");
        assertTrue(run(other, "plan").err().contains("SETUP_SIGNIN_INVALID"), "other schemes as before");
    }

    /** The demo sign-in file again (https issuer). */
    private void demo2() throws IOException {
        Files.copy(Path.of("demo", "signin-demo.json"), dir.resolve("signin-demo.json"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    // ---- B3 role aliases

    @Test
    void aMappingFileMayUseAnAliasWhichIsStoredAsItsRole() throws Exception {
        demo();
        write("product-roles-product-a.json", read("product-roles-product-a.json")
                .replace("\"platformRoles\"", "\"aliases\": {\"Reviewer\": \"READER\", \"Checker\": \"AUDITOR\"},\n  \"platformRoles\""));
        write("role-mapping-product-a.csv", read("role-mapping-product-a.csv")
                .replace("Demo Finance Readers,READER", "Demo Finance Readers,Reviewer"));
        Pg db = Pg.migrated();
        Result plan = run(db, "plan");
        assertEquals(0, plan.exit(), plan.all());
        assertTrue(plan.out().contains("create      grant " + S1 + " " + A + ": group \"Demo Finance Readers\" -> "
                + "Reviewer -> READER"), plan.out());
        assertTrue(plan.out().contains("checked     role alias \"Reviewer\" -> READER (1 row of role-mapping-product-a.csv; "
                + "stored as READER)"), plan.out());
        assertFalse(plan.out().contains("Checker"), "an alias the file does not use is not listed");
        assertEquals(0, run(db, "apply").exit());
        assertTrue(new PostgresGroupGrantStore(db.appDataSource(PASSWORD)).grants(S1, A, ISSUER)
                .contains(new Grant("Demo Finance Readers", "READER")));
        Result rerun = run(db, "apply");
        assertEquals(0, rerun.exit(), rerun.all());
        assertTrue(rerun.out().contains("Setup done (0 created, 0 updated"), rerun.out());

        // Unknown names are still refused.
        write("role-mapping-product-a.csv", read("role-mapping-product-a.csv").replace(",Reviewer", ",reviewer"));
        Result unknown = run(db, "plan");
        assertEquals(SetupCommand.EXIT_REFUSED, unknown.exit());
        assertTrue(unknown.err().contains("UNKNOWN_ROLE"), unknown.err());
    }

    @Test
    void badAliasesOrABadCustomerKeysValueRefuseTheProductRolesFile() throws Exception {
        demo();
        String base = read("product-roles-product-a.json");
        Pg db = Pg.migrated();
        for (String bad : List.of("\"aliases\": {\"reader\": \"EDITOR\"},", "\"aliases\": {\"X\": \"NOPE\"},",
                "\"aliases\": {\"X\": \"PLATFORM_ADMIN\"},", "\"aliases\": {\"X\": \"reader\"},",
                "\"aliases\": {\"X\": \"READER\", \"x\": \"EDITOR\"},", "\"aliases\": {\"PLATFORM_admin\": \"READER\"},",
                "\"aliases\": {\"has space\": \"READER\"},", "\"customerKeys\": \"maybe\",")) {
            write("product-roles-product-a.json", base.replace("\"platformRoles\"", bad + "\n  \"platformRoles\""));
            Result r = run(db, "plan");
            assertEquals(SetupCommand.EXIT_REFUSED, r.exit(), bad + "\n" + r.all());
            assertTrue(r.err().contains("product-roles file is not valid"), bad + "\n" + r.err());
        }
        assertEquals(0, rows(db));
    }

    // ---- B4 grant history

    @Test
    void setupWritesTheGrantHistoryWithItsRunAndARerunWritesNone() throws Exception {
        demo();
        Pg db = Pg.migrated();
        Result first = run(db, "apply");
        assertEquals(0, first.exit(), first.all());
        String run1 = first.runId();
        assertTrue(read("setup-runs.log").contains("run=" + run1));
        assertEquals(4, db.count("select count(*) from idn_grant_history where change = 'ADDED' and change_source = "
                + "'SETUP' and changed_by = 'setup' and setup_run_id = '" + run1 + "' and tenant_id = '" + TENANT + "'"));

        Result again = run(db, "apply");
        assertEquals(0, again.exit());
        assertNotEquals(run1, again.runId());
        assertEquals(4, db.count("select count(*) from idn_grant_history"), "a rerun writes no history");

        // A group's role changes in the file: without the flag the new grant is added, the old kept.
        write("role-mapping-product-a.csv", read("role-mapping-product-a.csv")
                .replace("Demo Finance Readers,READER", "Demo Finance Readers,AUDITOR"));
        Result added = run(db, "apply");
        assertEquals(SetupCommand.EXIT_NEEDS_FLAG, added.exit(), added.all());
        assertEquals(List.of("ADDED"), db.strings("select change from idn_grant_history where setup_run_id = '"
                + added.runId() + "'"));
        // With --remove-unlisted-grants the old one goes: one REMOVED row (the new role was added before).
        Result removed = run(db, "apply", "--remove-unlisted-grants");
        assertEquals(0, removed.exit(), removed.all());
        assertEquals(List.of("REMOVED:READER"), db.strings("select change || ':' || role_before from idn_grant_history "
                + "where setup_run_id = '" + removed.runId() + "'"));

        // Both in one run: one CHANGED row.
        write("role-mapping-product-a.csv", read("role-mapping-product-a.csv")
                .replace("Demo Finance Readers,AUDITOR", "Demo Finance Readers,EDITOR"));
        Result changed = run(db, "apply", "--remove-unlisted-grants");
        assertEquals(0, changed.exit(), changed.all());
        assertEquals(List.of("CHANGED:AUDITOR>EDITOR"), db.strings("select change || ':' || role_before || '>' || "
                + "role_after from idn_grant_history where setup_run_id = '" + changed.runId() + "'"));
    }

    // ---- B5 customer-keys guard

    @Test
    void requiredCustomerKeysRefuseNoCustomerKeys() throws Exception {
        demo();
        write("product-roles-product-a.json", read("product-roles-product-a.json")
                .replace("\"platformRoles\"", "\"customerKeys\": \"required\",\n  \"platformRoles\""));
        Pg db = Pg.migrated();
        Result r = run(db, "apply", "--no-customer-keys");
        assertEquals(SetupCommand.EXIT_REFUSED, r.exit(), r.all());
        assertTrue(r.err().contains("--no-customer-keys is not allowed"), r.err());
        assertTrue(read("setup-runs.log").contains("code=SETUP_CUSTOMER_KEYS_REQUIRED"));
        assertEquals(0, rows(db));
        assertEquals(0, run(db, "apply").exit(), "without the flag it runs");

        write("product-roles-product-a.json", read("product-roles-product-a.json").replace("\"required\"", "\"optional\""));
        assertEquals(0, run(db, "apply", "--no-customer-keys").exit(), "optional allows the flag");
    }

    @Test
    void aCustomerStoredWithoutKeysGetsBothFromTheNextRunAndNothingElseChanges() throws Exception {
        demo();
        Pg db = Pg.migrated();
        CustomerId customer = CustomerId.fromTenantId(TENANT);
        Result skipped = run(db, "apply", "--no-customer-keys");
        assertEquals(0, skipped.exit(), skipped.all());
        assertEquals(CryptoError.CRYPTO_KEY_MISSING,
                assertThrows(CryptoException.class, () -> keys.wrappingKey(customer)).code());
        long before = rows(db);
        String connections = String.join("|", db.strings("select connection_id || ':' || configuration_version "
                + "from idn_login_connection"));

        Result next = run(db, "apply");
        assertEquals(0, next.exit(), next.all());
        assertTrue(next.out().contains("  create      customer keys " + TENANT), next.out());
        assertTrue(next.out().contains("Setup done (0 created, 0 updated, 0 removed, 0 switched off"), next.out());
        assertEquals(1, keys.wrappingKey(customer).latest());
        assertEquals(1, keys.fingerprintKey(customer).latest());
        assertEquals(before, rows(db), "nothing else changed");
        assertEquals(connections, String.join("|", db.strings("select connection_id || ':' || configuration_version "
                + "from idn_login_connection")));
    }

    // ---- B1 drafts are the app's: setup leaves them alone

    @Test
    void setupLeavesTheAppsDraftsAloneAndStillSeesAConnectionChangedInTheApp() throws Exception {
        demo();
        Pg db = Pg.migrated();
        assertEquals(0, run(db, "apply").exit());
        PostgresLoginConnectionStore store = new PostgresLoginConnectionStore(db.appDataSource(PASSWORD));
        LoginConnection setupMade = store.active(TENANT, A).orElseThrow();
        LoginConnections app = new LoginConnections(store, new OidcTestSignIn((u, m, l) -> {
            throw new java.io.IOException("not used");
        }, Clock.systemUTC()), DevelopmentLicence.NONE);

        // A draft in the app: setup's rerun is unchanged and the draft stays as it is.
        ConnectionOutcome draft = app.createDraft(TENANT, A, setupMade.configurationVersion(),
                new ConnectionValues(setupMade.providerId(), setupMade.providerType(), ISSUER,
                        setupMade.browserClientId(), "api://changed-in-app", null), "it-admin");
        assertEquals(ConnectionOutcome.Result.DRAFT_CREATED, draft.result());
        Result rerun = run(db, "apply");
        assertEquals(0, rerun.exit(), rerun.all());
        assertTrue(rerun.out().contains("unchanged   login connection PRODUCT_A (version 1)"), rerun.out());
        assertEquals(draft.connection(), store.find(draft.connection().connectionId()).orElseThrow());

        // The app activates its draft (as LoginConnections does after a passed test sign-in).
        UUID id = draft.connection().connectionId();
        LoginConnection validated = store.updateTested(withStatus(draft.connection(), IdentityConnectionStatus.VALIDATED), 1,
                ChangeSource.APP, "it-admin").orElseThrow();
        assertEquals(ConnectionOutcome.Result.ACTIVATED, app.activate(id, validated.configurationVersion(), "it-admin")
                .result());
        // ...and starts another draft.
        LoginConnection active = store.active(TENANT, A).orElseThrow();
        ConnectionOutcome second = app.createDraft(TENANT, A, active.configurationVersion(), null, "it-admin");

        Result conflict = run(db, "apply");
        assertEquals(SetupCommand.EXIT_NEEDS_FLAG, conflict.exit(), conflict.all());
        assertTrue(conflict.out().contains("conflict    login connection PRODUCT_A (version 3) was changed in the app "
                + "since the last setup (apiAudience differ"), conflict.out());

        Result replaced = run(db, "apply", "--replace-connection");
        assertEquals(0, replaced.exit(), replaced.all());
        LoginConnection now = store.active(TENANT, A).orElseThrow();
        assertEquals(id, now.connectionId(), "the ACTIVE connection is replaced in place");
        assertEquals("api://demo-product-a", now.apiAudience());
        assertEquals(second.connection(), store.find(second.connection().connectionId()).orElseThrow(),
                "the draft is left alone");
        assertEquals(IdentityConnectionStatus.DISABLED, store.find(setupMade.connectionId()).orElseThrow().status());
        assertEquals(Set.of("ACTIVE", "DISABLED", "DRAFT"), Set.copyOf(db.strings("select status from idn_login_connection")));
        assertFalse(replaced.all().contains(PASSWORD));
    }

    // ---- P056 (LV-23): going back without a signed-in session

    private Result rollback(Map<String, String> env, String... more) {
        List<String> args = new ArrayList<>(List.of("identity", "rollback-login-connection", "--run-log",
                dir.resolve("rollback-runs.log").toString()));
        args.addAll(List.of(more));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = new SetupCommand(env, Clock.fixed(NOW, ZoneOffset.UTC), e -> {
            throw new AssertionError("no key service is needed");
        }, () -> {
            throw new AssertionError("no licence build is needed for an https issuer");
        }).run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void theRollbackCommandGoesBackToThePreviousConnectionWithTheSetupLoginOnly() throws Exception {
        demo();
        Pg db = Pg.migrated();
        assertEquals(0, run(db, "apply").exit());
        PostgresLoginConnectionStore store = new PostgresLoginConnectionStore(db.appDataSource(PASSWORD));
        LoginConnection good = store.active(TENANT, A).orElseThrow();
        Map<String, String> env = db.appEnv(PASSWORD);
        Result none = rollback(env, "--tenant-id", TENANT, "--product", A, "--apply");
        assertEquals(SetupCommand.EXIT_REFUSED, none.exit());
        assertTrue(none.err().contains("SETUP_NO_PREVIOUS_CONNECTION"), none.err());

        // The app switched to an issuer nobody can sign in to, and a new draft is open.
        LoginConnection bad = store.insert(new LoginConnection(UUID.randomUUID(), TENANT, A, good.providerId(),
                good.providerType(), URI.create("https://wrong.example.test"), good.browserClientId(),
                good.apiAudience(), null, IdentityConnectionStatus.DRAFT, 1), ChangeSource.APP, "it-admin");
        store.activate(bad.connectionId(), 1, ChangeSource.APP, "it-admin").orElseThrow();
        LoginConnection draft = store.insert(new LoginConnection(UUID.randomUUID(), TENANT, A, good.providerId(),
                good.providerType(), URI.create("https://next.example.test"), good.browserClientId(),
                good.apiAudience(), null, IdentityConnectionStatus.DRAFT, 1), ChangeSource.APP, "it-admin");

        Result shown = rollback(env, "--tenant-id", TENANT, "--product", A);
        assertEquals(0, shown.exit(), shown.all());
        assertTrue(shown.out().contains("https://wrong.example.test") && shown.out().contains(good.issuer().toString())
                && shown.out().contains("nothing was changed"), shown.out());
        assertEquals(bad.connectionId(), store.active(TENANT, A).orElseThrow().connectionId());

        Result done = rollback(env, "--tenant-id", TENANT, "--product", A, "--apply");
        assertEquals(0, done.exit(), done.all());
        LoginConnection now = store.active(TENANT, A).orElseThrow();
        assertEquals(good.connectionId(), now.connectionId());
        assertEquals(good.issuer(), now.issuer());
        assertEquals(IdentityConnectionStatus.DISABLED, store.find(bad.connectionId()).orElseThrow().status());
        assertEquals(draft, store.find(draft.connectionId()).orElseThrow(), "the draft is left alone");
        var last = store.history(good.connectionId());
        assertEquals(ChangeSource.SETUP, last.get(last.size() - 1).source());
        assertTrue(last.get(last.size() - 1).changedBy().startsWith("setup rollback, run "), last.toString());
        String log = Files.readString(dir.resolve("rollback-runs.log"));
        assertTrue(log.contains("rollback-login-connection") && log.contains("result=DONE"), log);
        assertFalse((shown.all() + done.all() + log).contains(PASSWORD));
        assertEquals(0, run(db, "apply").exit(), "the sign-in file holds the same connection: unchanged");
    }

    @Test
    void setupDoesNotSilentlyUndoARollback() throws Exception {
        demo();
        Pg db = Pg.migrated();
        assertEquals(0, run(db, "apply").exit());
        PostgresLoginConnectionStore store = new PostgresLoginConnectionStore(db.appDataSource(PASSWORD));
        LoginConnection fileValues = store.active(TENANT, A).orElseThrow();
        LoginConnection other = store.insert(new LoginConnection(UUID.randomUUID(), TENANT, A, fileValues.providerId(),
                fileValues.providerType(), URI.create("https://other.example.test"), fileValues.browserClientId(),
                fileValues.apiAudience(), null, IdentityConnectionStatus.DRAFT, 1), ChangeSource.APP, "it-admin");
        store.activate(other.connectionId(), 1, ChangeSource.APP, "it-admin").orElseThrow();
        assertEquals(0, run(db, "apply", "--replace-connection").exit()); // the file's values again, in place
        assertEquals(0, rollback(db.appEnv(PASSWORD), "--tenant-id", TENANT, "--product", A, "--apply").exit());
        assertEquals(URI.create("https://other.example.test"), store.active(TENANT, A).orElseThrow().issuer());
        Result again = run(db, "apply");
        assertEquals(SetupCommand.EXIT_NEEDS_FLAG, again.exit(), again.all());
        assertTrue(again.out().contains("conflict    login connection PRODUCT_A"), again.out());
        assertEquals(URI.create("https://other.example.test"), store.active(TENANT, A).orElseThrow().issuer());
    }

    @Test
    void theRollbackCommandRefusesBadOptionsAndAnotherCustomer() throws Exception {
        demo();
        Pg db = Pg.migrated();
        assertEquals(0, run(db, "apply").exit());
        Map<String, String> env = db.appEnv(PASSWORD);
        for (String[] bad : new String[][]{{}, {"--product", A}, {"--tenant-id", TENANT}, {"--tenant-id", TENANT,
                "--product", "bad code"}, {"--tenant-id", TENANT, "--product", A, "--licence", "x"}}) {
            Result r = rollback(env, bad);
            assertEquals(SetupCommand.EXIT_REFUSED, r.exit());
            assertTrue(r.err().contains("SETUP_BAD_OPTION"), r.err());
        }
        Result other = rollback(env, "--tenant-id", "TEN-OTHER-0001", "--product", A, "--apply");
        assertEquals(SetupCommand.EXIT_REFUSED, other.exit());
        assertTrue(other.err().contains("SETUP_OTHER_CUSTOMER_INSTALLED"), other.err());
        Result noDb = rollback(Map.of(), "--tenant-id", TENANT, "--product", A);
        assertTrue(noDb.err().contains("SETUP_DB_SETTINGS_MISSING"), noDb.err());
    }

    @Test
    void aConnectionTheAppDisabledIsAConflictUntilReplaceConnectionIsGiven() throws Exception {
        demo();
        Pg db = Pg.migrated();
        assertEquals(0, run(db, "apply").exit());
        PostgresLoginConnectionStore store = new PostgresLoginConnectionStore(db.appDataSource(PASSWORD));
        LoginConnection setupMade = store.active(TENANT, A).orElseThrow();
        store.update(withStatus(setupMade, IdentityConnectionStatus.DISABLED), setupMade.configurationVersion(),
                ChangeSource.APP, "it-admin").orElseThrow();

        Result conflict = run(db, "apply");
        assertEquals(SetupCommand.EXIT_NEEDS_FLAG, conflict.exit(), conflict.all());
        assertTrue(conflict.out().contains("conflict    login connection PRODUCT_A (version 2) was changed in the app "
                + "since the last setup (status differ from the sign-in file); left as it is."), conflict.out());
        assertTrue(store.active(TENANT, A).isEmpty(), "left DISABLED");
        assertEquals(1, db.count("select count(*) from idn_login_connection"), "no second connection made");

        Result replaced = run(db, "apply", "--replace-connection");
        assertEquals(0, replaced.exit(), replaced.all());
        assertTrue(replaced.out().contains("update      login connection PRODUCT_A (version 2) -> version 3: status "
                + "(replaces the change made in the app)"), replaced.out());
        LoginConnection now = store.active(TENANT, A).orElseThrow();
        assertEquals(setupMade.connectionId(), now.connectionId(), "the same connection, ACTIVE again");
        assertEquals(1, db.count("select count(*) from idn_login_connection"));
    }

    private static LoginConnection withStatus(LoginConnection c, IdentityConnectionStatus s) {
        return new LoginConnection(c.connectionId(), c.tenantId(), c.productCode(), c.providerId(), c.providerType(),
                c.issuer(), c.browserClientId(), c.apiAudience(), c.secretReference(), s, c.configurationVersion());
    }

    // ---- P028 B5 platform-wide grants

    @Test
    void starRowsLoadAreRefusedForTheWrongRoleKindAndAreRemovedOnlyWithTheFlag() throws Exception {
        demo();
        Pg db = Pg.migrated();
        String mapping = read("role-mapping-product-a.csv");
        write("role-mapping-product-a.csv", mapping + "*,,Demo Platform Operations,PLATFORM_ADMIN\n");
        Result plan = run(db, "plan");
        assertEquals(0, plan.exit(), plan.all());
        assertTrue(plan.out().contains("create      platform grant PRODUCT_A (every entity): group \"Demo Platform "
                + "Operations\" -> PLATFORM_ADMIN"), plan.out());
        assertEquals(0, db.count("select count(*) from idn_platform_grant"), "plan writes nothing");

        Result apply = run(db, "apply");
        assertEquals(0, apply.exit(), apply.all());
        assertEquals(List.of(TENANT + " PRODUCT_A Demo Platform Operations PLATFORM_ADMIN"), db.strings("select "
                + "tenant_id || ' ' || product_code || ' ' || external_group || ' ' || role from idn_platform_grant"));
        assertEquals(List.of("PLATFORM ADDED " + apply.runId()), db.strings("select scope || ' ' || change || ' ' || "
                + "setup_run_id from idn_grant_history where scope = 'PLATFORM'"));
        assertEquals(0, db.count("select count(*) from idn_grant_history where scope = 'PLATFORM' and "
                + "(slot_id is not null or entity_uuid is not null)"), "a platform row names no entity");
        assertTrue(read("setup-runs.log").contains("run=" + apply.runId()));

        Result again = run(db, "apply");
        assertEquals(0, again.exit(), again.all());
        assertTrue(again.out().contains("unchanged   platform grants PRODUCT_A: 1 grant"), again.out());
        assertEquals(1, db.count("select count(*) from idn_grant_history where scope = 'PLATFORM'"), "no history");

        // The * row removed from the file: kept without the flag, removed with it.
        write("role-mapping-product-a.csv", mapping);
        Result kept = run(db, "apply");
        assertEquals(SetupCommand.EXIT_NEEDS_FLAG, kept.exit(), kept.all());
        assertTrue(kept.out().contains("not removed platform grant PRODUCT_A (every entity): group \"Demo Platform "
                + "Operations\" -> PLATFORM_ADMIN (not in the mapping file); kept."), kept.out());
        assertEquals(1, db.count("select count(*) from idn_platform_grant"));
        Result removed = run(db, "apply", "--remove-unlisted-grants");
        assertEquals(0, removed.exit(), removed.all());
        assertEquals(0, db.count("select count(*) from idn_platform_grant"));
        assertEquals(List.of("PLATFORM REMOVED PLATFORM_ADMIN"), db.strings("select scope || ' ' || change || ' ' || "
                + "role_before from idn_grant_history where setup_run_id = '" + removed.runId() + "'"));

        // The wrong role kind on either kind of row: refused with its own code, nothing written.
        write("role-mapping-product-a.csv", mapping + "*,,Demo Platform Operations,READER\n");
        Result entityRole = run(db, "apply");
        assertNotEquals(0, entityRole.exit());
        assertTrue(entityRole.err().contains("SETUP_ENTITY_ROLE_PLATFORM_WIDE"), entityRole.all());
        assertTrue(entityRole.err().contains("ENTITY_ROLE_PLATFORM_WIDE"), entityRole.all());
        write("role-mapping-product-a.csv", mapping + S1 + ",,Demo Platform Operations,PLATFORM_ADMIN\n");
        Result platformRole = run(db, "apply");
        assertNotEquals(0, platformRole.exit());
        assertTrue(platformRole.err().contains("SETUP_PLATFORM_ROLE_ON_ENTITY"), platformRole.all());
        assertEquals(0, db.count("select count(*) from idn_platform_grant"));
    }
}
