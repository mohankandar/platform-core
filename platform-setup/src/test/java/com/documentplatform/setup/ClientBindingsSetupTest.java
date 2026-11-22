package com.documentplatform.setup;

import com.documentplatform.crypto.testkit.InMemoryKeyService;
import com.documentplatform.identity.EntityUuid;
import com.documentplatform.identity.admission.ConnectionKey;
import com.documentplatform.identity.admission.EntityId;
import com.documentplatform.identity.jdbc.PostgresMachineEntityBindings;
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
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.documentplatform.setup.SetupCommandTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The client-binding file ({@code docs/design/setup.md} 6.1) through the setup command, end to end against Postgres
 * with the DML-only app user and the demo files.
 */
class ClientBindingsSetupTest {

    static final String HEADER = "client_id,entity_slot,source,description\n";
    static final String LOAN = "0oaDEMOloan0001";
    static final String SCAN = "0oaDEMOscan0002";

    @TempDir
    Path dir;

    final InMemoryKeyService keys = new InMemoryKeyService();

    record Result(int exit, String out, String err) {
        String all() {
            return out + err;
        }
    }

    private void demo() throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(Path.of("demo"))) {
            for (Path f : files) Files.copy(f, dir.resolve(f.getFileName()));
        }
    }

    private Result run(Pg db, String action, String product) {
        String suffix = product.equals(A) ? "a" : "b";
        List<String> args = new ArrayList<>(List.of(action,
                "--licence", dir.resolve("licence-demo.json").toString(),
                "--signin", dir.resolve("signin-demo.json").toString(),
                "--product", product,
                "--keys", dir.resolve("development-trusted-keys.json").toString(),
                "--product-roles", dir.resolve("product-roles-product-" + suffix + ".json").toString(),
                "--config-out", dir.resolve("platform-tenant.properties").toString(),
                "--run-log", dir.resolve("setup-runs.log").toString()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = new SetupCommand(db.appEnv(PASSWORD), Clock.fixed(NOW, ZoneOffset.UTC),
                env -> new SetupKeys.Connection(keys, () -> { })).run(args,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private void bindings(String text) throws IOException {
        Files.writeString(dir.resolve("client-bindings-product-a.csv"), text, StandardCharsets.UTF_8);
    }

    private static List<String> stored(Pg db) {
        return db.strings("select client_id || ' ' || slot_id || ' ' || source_key || ' ' || status || ' v' || version "
                + "from idn_machine_binding order by client_id");
    }

    @Test
    void loadsTheDemoFileAndARerunChangesNothing() throws Exception {
        demo();
        Pg db = Pg.migrated();
        Result plan = run(db, "plan", A);
        assertEquals(0, plan.exit(), plan.all());
        assertTrue(plan.out().contains("create      client binding PRODUCT_A: client \"" + LOAN + "\" -> ENT-DEMO-0001, "
                + "source demo-loan-system"), plan.out());
        assertEquals(0, db.count("select count(*) from idn_machine_binding"), "plan writes nothing");

        Result apply = run(db, "apply", A);
        assertEquals(0, apply.exit(), apply.all());
        assertEquals(List.of(LOAN + " ENT-DEMO-0001 demo-loan-system ACTIVE v1",
                SCAN + " ENT-DEMO-0002 demo-scanner ACTIVE v1"), stored(db));
        assertEquals(EntityUuid.fromSlotId("ENT-DEMO-0001").toString(),
                db.strings("select entity_uuid from idn_machine_binding where client_id = '" + LOAN + "'").get(0));
        assertEquals(List.of("1 ACTIVE SETUP setup", "1 ACTIVE SETUP setup"), db.strings("select version || ' ' "
                + "|| status || ' ' || change_source || ' ' || changed_by from idn_machine_binding_history"));

        Result again = run(db, "apply", A);
        assertEquals(0, again.exit(), again.all());
        assertTrue(again.out().contains("unchanged   client binding PRODUCT_A: client \"" + LOAN + "\""), again.out());
        assertFalse(again.out().contains("client binding PRODUCT_A: client \"" + LOAN + "\" -> ENT-DEMO-0001, source "
                + "demo-loan-system (switched"), again.out());
        assertEquals(2, db.count("select count(*) from idn_machine_binding_history"), "a rerun writes no history");
        assertTrue(Files.readString(dir.resolve("setup-runs.log")).contains("switch_off=0"));

        // PRODUCT_B names no client-binding file: no machine callers, and nothing of PRODUCT_A is touched.
        Result b = run(db, "apply", "PRODUCT_B");
        assertEquals(0, b.exit(), b.all());
        assertEquals(2, db.count("select count(*) from idn_machine_binding where status = 'ACTIVE'"));
    }

    @Test
    void aRemovedLineSwitchesTheBindingOffAndAReAddedOneSwitchesItOn() throws Exception {
        demo();
        Pg db = Pg.migrated();
        assertEquals(0, run(db, "apply", A).exit());
        bindings(HEADER + LOAN + ",ENT-DEMO-0001,demo-loan-system,Demo loan system for entity 1 (demo value)\n");
        Result off = run(db, "apply", A);
        assertEquals(0, off.exit(), off.all());
        assertTrue(off.out().contains("switch off  client binding PRODUCT_A: client \"" + SCAN + "\" -> ENT-DEMO-0002, "
                + "source demo-scanner (not in client-bindings-product-a.csv; kept as INACTIVE, never deleted)"), off.out());
        assertTrue(off.out().contains("1 switched off"), off.out());
        assertEquals(List.of(LOAN + " ENT-DEMO-0001 demo-loan-system ACTIVE v1",
                SCAN + " ENT-DEMO-0002 demo-scanner INACTIVE v2"), stored(db));
        assertTrue(Files.readString(dir.resolve("setup-runs.log")).contains("switch_off=1"));

        // An INACTIVE binding may come back for another entity: it is switched on again, with history.
        bindings(HEADER + LOAN + ",ENT-DEMO-0001,demo-loan-system,Demo loan system for entity 1 (demo value)\n"
                + SCAN + ",ENT-DEMO-0001,demo-scanner,moved\n");
        Result on = run(db, "apply", A);
        assertEquals(0, on.exit(), on.all());
        assertTrue(on.out().contains("update      client binding PRODUCT_A: client \"" + SCAN + "\" -> ENT-DEMO-0001, "
                + "source demo-scanner (switched on again)"), on.out());
        assertEquals(List.of("1 ENT-DEMO-0002 ACTIVE", "2 ENT-DEMO-0002 INACTIVE", "3 ENT-DEMO-0001 ACTIVE"),
                db.strings("select h.version || ' ' || h.slot_id || ' ' || h.status from idn_machine_binding_history h "
                        + "join idn_machine_binding b using (binding_id) where b.client_id = '" + SCAN
                        + "' order by h.version"));

        // A changed description is an update; nothing else changes.
        bindings(HEADER + LOAN + ",ENT-DEMO-0001,demo-loan-system,New text\n" + SCAN + ",ENT-DEMO-0001,demo-scanner,moved\n");
        Result desc = run(db, "apply", A);
        assertTrue(desc.out().contains("(description)"), desc.out());

        // The sign-in file no longer names a file: no machine callers, every binding switched off.
        String signin = Files.readString(dir.resolve("signin-demo.json"));
        Files.writeString(dir.resolve("signin-demo.json"), signin.replace(
                "\"clientBindingsFile\": \"client-bindings-product-a.csv\",", ""));
        Result none = run(db, "apply", A);
        assertEquals(0, none.exit(), none.all());
        assertTrue(none.out().contains("(the sign-in file names no client-binding file; kept as INACTIVE, never "
                + "deleted)"), none.out());
        assertEquals(0, db.count("select count(*) from idn_machine_binding where status = 'ACTIVE'"));
        assertEquals(2, db.count("select count(*) from idn_machine_binding"), "nothing is deleted");
    }

    @Test
    void fileProblemsRefuseTheRunWithLineNumbersAndWriteNothing() throws Exception {
        demo();
        Pg db = Pg.migrated();
        bindings(HEADER
                + LOAN + ",ENT-DEMO-0001,demo-loan-system,\n"   // 2
                + LOAN + ",ENT-DEMO-0002,demo-scanner,\n"       // 3 duplicate
                + "other,ENT-DEMO-0001,unknown-system,\n");     // 4 unknown source
        Result r = run(db, "apply", A);
        assertEquals(SetupCommand.EXIT_REFUSED, r.exit());
        assertTrue(r.err().contains("SETUP_CLIENT_BINDINGS_INVALID") || r.err().contains("client-binding file"), r.err());
        assertTrue(r.err().contains("line 3: DUPLICATE_CLIENT_ID"), r.err());
        assertTrue(r.err().contains("line 4: SOURCE_UNKNOWN"), r.err());
        assertTrue(r.err().contains("What to do: Correct the client-binding file"), r.err());
        assertEquals(0, db.count("select count(*) from idn_customer"), "refused in preflight: nothing written");
        assertTrue(Files.readString(dir.resolve("setup-runs.log")).contains("code=SETUP_CLIENT_BINDINGS_INVALID"));

        bindings("client_id;entity_slot;source;description\n" + LOAN + ";ENT-DEMO-0001;demo-loan-system;\n");
        Result header = run(db, "apply", A);
        assertEquals(SetupCommand.EXIT_REFUSED, header.exit());
        assertTrue(header.err().contains("line 1: HEADER_WRONG"), header.err());

        bindings(HEADER + "x,ENT-DEMO-0001,demo-scanner,client_secret=abc123\n");
        Result secret = run(db, "apply", A);
        assertTrue(secret.err().contains("line 2: SECRET_LIKE"), secret.err());
        assertFalse(secret.all().contains("abc123"), "the secret is never repeated");

        // A slot of the right shape that the licence does not list: found in the plan, still with its line.
        bindings(HEADER + LOAN + ",ENT-DEMO-0001,demo-loan-system,\n" + "other,ENT-DEMO-0099,demo-scanner,\n");
        Result slot = run(db, "apply", A);
        assertEquals(SetupCommand.EXIT_REFUSED, slot.exit());
        assertTrue(slot.err().contains("line 3: SLOT_UNKNOWN: entity slot ENT-DEMO-0099 is not a slot of this "
                + "customer's licence"), slot.err());
        assertEquals(0, db.count("select count(*) from idn_customer"), "the whole database part rolled back");
    }

    @Test
    void aClientBoundToAnotherEntityOrSourceIsRefused() throws Exception {
        demo();
        Pg db = Pg.migrated();
        assertEquals(0, run(db, "apply", A).exit());
        bindings(HEADER + LOAN + ",ENT-DEMO-0002,demo-loan-system,\n" + SCAN + ",ENT-DEMO-0002,demo-loan-system,\n");
        Result r = run(db, "apply", A);
        assertEquals(SetupCommand.EXIT_REFUSED, r.exit(), r.all());
        assertTrue(r.err().contains("line 2: CLIENT_BOUND_ELSEWHERE: client \"" + LOAN + "\" is already bound to "
                + "ENT-DEMO-0001, source demo-loan-system"), r.err());
        assertTrue(r.err().contains("line 3: CLIENT_BOUND_ELSEWHERE"), r.err());
        assertEquals(List.of(LOAN + " ENT-DEMO-0001 demo-loan-system ACTIVE v1",
                SCAN + " ENT-DEMO-0002 demo-scanner ACTIVE v1"), stored(db), "nothing changed");
    }

    @Test
    void anOlderSignInFileWithoutTheNewFieldsStillWorks() throws Exception {
        demo();
        String signin = Files.readString(dir.resolve("signin-demo.json"));
        String older = signin.replaceAll(",\\s*\"clientBindingsFile\"[^\\]]*\\]", "");
        assertNotEquals(signin, older, "the demo sign-in file has the new fields");
        assertFalse(older.contains("sources"));
        Files.writeString(dir.resolve("signin-demo.json"), older);
        Pg db = Pg.migrated();
        Result r = run(db, "apply", A);
        assertEquals(0, r.exit(), r.all());
        assertFalse(r.out().contains("client binding"), r.out());
        assertEquals(0, db.count("select count(*) from idn_machine_binding"));
    }

    @Test
    void theLoadedBindingAdmitsTheClientOnlyOnceTheEntityIsActive() throws Exception {
        demo();
        Pg db = Pg.migrated();
        assertEquals(0, run(db, "apply", A).exit());
        PostgresMachineEntityBindings machines = new PostgresMachineEntityBindings(db.appDataSource(PASSWORD), A);
        ConnectionKey key = new ConnectionKey("demo-okta", URI.create("https://login.demo.example/oauth2/default"),
                "api://demo-product-a");
        assertEquals(Set.of(), machines.find(key, LOAN), "the slot is not activated yet");
        db.exec("update idn_entity set status = 'ACTIVE', display_name = 'Demo Finance', activated_by = 'admin-1', "
                + "activated_at = now() where slot_id = 'ENT-DEMO-0001'");
        assertEquals(Set.of(EntityId.of(EntityUuid.fromSlotId("ENT-DEMO-0001"))), machines.find(key, LOAN));
        assertEquals(Set.of(), machines.find(key, SCAN), "its entity is still not activated");
    }
}
