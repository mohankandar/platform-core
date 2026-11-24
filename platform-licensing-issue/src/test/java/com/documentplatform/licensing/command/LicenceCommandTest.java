package com.documentplatform.licensing.command;

import com.documentplatform.licensing.Allowance;
import com.documentplatform.licensing.AllowanceType;
import com.documentplatform.licensing.Deployment;
import com.documentplatform.licensing.EntitySlot;
import com.documentplatform.licensing.Fixtures;
import com.documentplatform.licensing.LastSeenReset;
import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.LicenceBuild;
import com.documentplatform.licensing.LicenceKind;
import com.documentplatform.licensing.LicenceType;
import com.documentplatform.licensing.jdbc.Pg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The customer-side licence command against Postgres, connecting as the DML-only app user. */
class LicenceCommandTest {

    private static final String TENANT = "TEN-0000-0000-0000-0001";

    @TempDir
    Path dir;
    private Pg db;
    private final Fixtures.MutableClock clock = new Fixtures.MutableClock(Instant.parse("2026-11-21T10:00:00Z"));
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws Exception {
        db = Pg.migrated();
        db.appUser("select, insert, update, delete");
    }

    static Licence licence(String id, long version, String tenant, String starts, String expires) {
        return new Licence(id, version, "Example Customer Ltd", tenant, List.of("PRODUCT_A"),
                List.of(new EntitySlot("ENT-0000-0000-0000-0001",
                                List.of(new Allowance("PRODUCT_A", AllowanceType.PAGES_PER_MONTH, 10000))),
                        new EntitySlot("ENT-0000-0000-0000-0002",
                                List.of(new Allowance("PRODUCT_A", AllowanceType.NAMED_SEATS, 5)))),
                LicenceType.SUBSCRIPTION, LicenceKind.PRODUCTION, LocalDate.parse(starts), LocalDate.parse(expires),
                null, Deployment.ON_PREM, Fixtures.KEY_ID_1, Instant.parse("2026-11-21T10:00:00Z"));
    }

    private Path file(String name, String text) throws Exception {
        Path p = dir.resolve(name);
        Files.writeString(p, text);
        return p;
    }

    private Path licenceFile(Licence l) throws Exception {
        return file(l.licenceId() + ".json", com.documentplatform.licensing.issue.LicenceSigner.sign(l, Fixtures.KEY_1.getPrivate()));
    }

    private int run(String... args) {
        out.reset();
        err.reset();
        List<String> all = new ArrayList<>(List.of(args));
        all.addAll(List.of("--jdbc-url", db.jdbcUrl(), "--db-user", db.appUserName()));
        return new LicenceCommand(o -> LicenceCommand.jdbc(o, Map.of(LicenceCommand.PASSWORD_VARIABLE, "pw"), clock,
                LicenceBuild.of(Fixtures.trustKey1(), false, Instant.EPOCH)))
                .run(all, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void statusWithoutALicenceSaysWhatToDo() {
        assertEquals(1, run("status"));
        assertTrue(err().startsWith("LICENCE_MISSING: "), err());
        assertTrue(err().contains("What to do: Store the licence file with the licence command"), err());
    }

    @Test
    void storeFirstThenAgainIsIdempotent() throws Exception {
        Path f = licenceFile(licence("LIC-0001", 1, TENANT, "2026-01-01", "2027-11-30"));
        assertEquals(0, run("store", "--file", f.toString()), err());
        assertTrue(out().startsWith("Stored licence LIC-0001 v1 (first licence)."), out());
        assertTrue(out().contains("Licence LIC-0001 v1 for Example Customer Ltd (tenant " + TENANT
                + "): SUBSCRIPTION, PRODUCTION, 2 entities, expires 2027-11-30. State VALID."), out());
        assertEquals(0, run("store", "--file", f.toString()));
        assertTrue(out().startsWith("Licence LIC-0001 v1 was already stored; nothing changed."), out());
        assertEquals(1, db.count("select count(*) from lic_licence"));
    }

    @Test
    void anotherCustomersLicenceIsRefusedAndNothingChanges() throws Exception {
        assertEquals(0, run("store", "--file", licenceFile(licence("LIC-0001", 1, TENANT, "2026-01-01", "2027-11-30")).toString()));
        assertEquals(1, run("store", "--file", licenceFile(licence("LIC-0009", 2, "TEN-9999-9999-9999-9999", "2026-01-01", "2028-11-30")).toString()));
        assertTrue(err().startsWith("LICENCE_WRONG_CUSTOMER: "), err());
        assertEquals(1, db.count("select count(*) from lic_licence where licence_id = 'LIC-0001'"), "the stored licence is untouched");
        assertEquals(1, db.count("select count(*) from lic_licence"));
    }

    @Test
    void anEarlyRenewalWaitsAndThenTakesOver() throws Exception {
        assertEquals(0, run("store", "--file", licenceFile(licence("LIC-0001", 1, TENANT, "2026-01-01", "2026-12-31")).toString()));
        assertEquals(0, run("store", "--file", licenceFile(licence("LIC-0002", 2, TENANT, "2027-01-01", "2027-12-31")).toString()), err());
        assertTrue(out().startsWith("Stored licence LIC-0002 v2; it takes over on 2027-01-01. The current licence stays in force until then."), out());
        assertTrue(out().contains("Licence LIC-0001 v1"), "the current licence is still in force: " + out());
        assertTrue(out().contains("The renewed licence LIC-0002 v2 is stored and takes over on 2027-01-01."), out());

        clock.set(Instant.parse("2027-01-01T00:00:01Z"));
        assertEquals(0, run("status"));
        assertTrue(out().startsWith("Licence LIC-0002 v2"), out());
        assertEquals(0, db.count("select count(*) from lic_licence where slot = 'WAITING'"));
    }

    @Test
    void anOlderVersionIsRefused() throws Exception {
        assertEquals(0, run("store", "--file", licenceFile(licence("LIC-0002", 2, TENANT, "2026-01-01", "2027-12-31")).toString()));
        assertEquals(1, run("store", "--file", licenceFile(licence("LIC-0001", 1, TENANT, "2026-01-01", "2026-12-31")).toString()));
        assertTrue(err().startsWith("LICENCE_OLDER_VERSION: "), err());
        assertEquals(1, run("store", "--file", licenceFile(licence("LIC-0003", 2, TENANT, "2026-01-01", "2027-12-31")).toString()));
        assertTrue(err().startsWith("LICENCE_VERSION_CONFLICT: "), err());
    }

    @Test
    void aForgedFileIsRefusedBeforeStoring() throws Exception {
        Path forged = file("forged.json", com.documentplatform.licensing.issue.LicenceSigner.sign(licence("LIC-0001", 1, TENANT, "2026-01-01", "2099-12-31"),
                com.documentplatform.licensing.issue.SigningKeys.generate().getPrivate()));
        assertEquals(1, run("store", "--file", forged.toString()));
        assertTrue(err().startsWith("LICENCE_FORGED: "), err());
        assertEquals(0, db.count("select count(*) from lic_licence"));
    }

    @Test
    void aPoisonedLastSeenIsFlaggedAndASignedResetRecovers() throws Exception {
        assertEquals(0, run("store", "--file", licenceFile(licence("LIC-0001", 1, TENANT, "2026-01-01", "2027-11-30")).toString()));
        db.exec("update lic_last_seen set last_seen = '2031-01-01T00:00:00Z'"); // a wrong future clock was seen once
        assertEquals(0, run("status"));
        assertTrue(out().contains("State EXPIRED_GRACE_OVER"), "dates use the poisoned time: " + out());
        assertTrue(out().contains("The server clock is 1501 days 14 hours behind"), out());

        Instant now = clock.instant();
        LastSeenReset reset = new LastSeenReset("RST-0000-0001", TENANT, now, now.plus(Duration.ofDays(7)), now,
                Fixtures.KEY_ID_1);
        Path resetFile = file("reset.json", com.documentplatform.licensing.issue.ResetSigner.sign(reset, Fixtures.KEY_1.getPrivate()));
        assertEquals(1, run("apply-reset", "--file", resetFile.toString()), "the everyday user may not (P045, SEC-13)");
        assertTrue(err().startsWith("RESET_NEEDS_OWNER: "), err());
        assertEquals(0, runAsOwner("apply-reset", "--file", resetFile.toString()), err());
        assertTrue(out().startsWith("Reset applied"), out());
        assertTrue(out().contains("State VALID"), out());
        assertFalse(out().contains("server clock is"), out());
        assertEquals(0, run("apply-reset", "--file", resetFile.toString()));
        assertTrue(out().startsWith("This reset was applied before"), out());
    }

    @Test
    void aResetForAnotherCustomerIsRefused() throws Exception {
        assertEquals(0, run("store", "--file", licenceFile(licence("LIC-0001", 1, TENANT, "2026-01-01", "2027-11-30")).toString()));
        Instant now = clock.instant();
        Path resetFile = file("reset.json", com.documentplatform.licensing.issue.ResetSigner.sign(new LastSeenReset("RST-0000-0002", "TEN-OTHER", now, now.plusSeconds(60),
                now, Fixtures.KEY_ID_1),Fixtures.KEY_1.getPrivate()));
        assertEquals(1, run("apply-reset", "--file", resetFile.toString()));
        assertTrue(err().startsWith("RESET_WRONG_CUSTOMER: "), err());
    }

    @Test
    void missingTablesAndBadOptionsAreReported() throws Exception {
        db.exec("drop table lic_chain_head");
        assertEquals(1, run("status"));
        assertTrue(err().startsWith("Licensing tables incomplete at version 2"), err());
        assertEquals(2, run("frobnicate"));
        assertEquals(2, run("store"));
        assertTrue(err().contains("store needs --file"), err());
    }

    private int runAsOwner(String... args) {
        out.reset();
        err.reset();
        List<String> all = new ArrayList<>(List.of(args));
        all.addAll(List.of("--jdbc-url", db.jdbcUrl(), "--db-user", db.owner.getUser()));
        return new LicenceCommand(o -> LicenceCommand.jdbc(o, Map.of(LicenceCommand.PASSWORD_VARIABLE,
                db.owner.getPassword()), clock, LicenceBuild.of(Fixtures.trustKey1(), false, Instant.EPOCH)))
                .run(all, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    /** Runs the command with this JDBC URL and password variable (not the test's usual ones). */
    private int runWith(String jdbcUrl, String password, String... args) {
        out.reset();
        err.reset();
        List<String> all = new ArrayList<>(List.of(args));
        all.addAll(List.of("--jdbc-url", jdbcUrl, "--db-user", db.appUserName()));
        return new LicenceCommand(o -> LicenceCommand.jdbc(o, Map.of(LicenceCommand.PASSWORD_VARIABLE, password), clock,
                LicenceBuild.of(Fixtures.trustKey1(), false, Instant.EPOCH)))
                .run(all, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    /**
     * A wrong password, a password in the JDBC URL's query and one in its user-info: the command says the tables
     * could not be checked (SQL state only) and never prints or logs the password, from the variable or the URL.
     */
    @Test
    void noPasswordInAnyOutput() throws Exception {
        String wrong = "Wrong-Pw-6d2e-NEVER-PRINTED";
        String inQuery = "Url-Query-Pw-81af-NEVER-PRINTED";
        String inUserInfo = "Url-UserInfo-Pw-c3b7-NEVER-PRINTED";
        List<String> outputs = new ArrayList<>();
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
        java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
        java.util.logging.Logger platform = java.util.logging.Logger.getLogger("com.documentplatform");
        java.util.logging.Level before = platform.getLevel();
        root.addHandler(capture); // what any logger prints at its usual level
        platform.setLevel(java.util.logging.Level.ALL); // and everything the platform logs
        try {
            assertEquals(1, runWith(db.jdbcUrl(), wrong, "status"));
            assertTrue(err().startsWith("Licensing tables could not be checked: the database refused the check "
                    + "(SQL state 28P01)."), err());
            outputs.add(out() + err());

            assertEquals(1, runWith(db.jdbcUrl() + "&password=" + inQuery, "", "status"));
            assertTrue(err().startsWith("Licensing tables could not be checked"), err());
            outputs.add(out() + err());

            assertEquals(1, runWith("jdbc:postgresql://127.0.0.1:1/nodb?password=" + inQuery, wrong, "status"));
            assertTrue(err().startsWith("Licensing tables could not be checked"), err());
            outputs.add(out() + err());

            assertEquals(1, runWith("jdbc:postgresql://planted_user:" + inUserInfo + "@127.0.0.1:1/nodb", wrong,
                    "status"));
            assertTrue(err().startsWith("Licensing tables could not be checked"), err());
            outputs.add(out() + err());
        } finally {
            root.removeHandler(capture);
            platform.setLevel(before);
        }
        outputs.addAll(lines);
        for (String text : outputs) {
            for (String secret : List.of(wrong, inQuery, inUserInfo, "NEVER-PRINTED")) {
                assertFalse(text.contains(secret), "a password was printed: " + text);
            }
        }
    }
}
