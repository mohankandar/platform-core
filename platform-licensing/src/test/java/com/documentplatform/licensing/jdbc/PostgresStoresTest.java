package com.documentplatform.licensing.jdbc;

import com.documentplatform.licensing.AllowanceType;
import com.documentplatform.licensing.LicenceStore;
import com.documentplatform.licensing.usage.ChainCheck;
import com.documentplatform.licensing.usage.ChainHead;
import com.documentplatform.licensing.usage.UsageEntry;
import com.documentplatform.licensing.usage.UsageRecord;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.documentplatform.licensing.jdbc.LicensingSchemaException.Problem.*;
import static org.junit.jupiter.api.Assertions.*;

/** The Postgres stores, run as the DML-only app user the install guide asks for. */
class PostgresStoresTest {

    private static final String DML = "select, insert, update, delete";
    private static final YearMonth NOV = YearMonth.of(2026, 11);

    private static UsageEntry entry(long amount) {
        return new UsageEntry("ENT-0001", "PRODUCT_A", AllowanceType.PAGES_PER_MONTH, NOV, amount, true,
                Instant.parse("2026-11-21T10:00:00Z"));
    }

    @Test
    void theLedgerChainsAndMovesTheHead() {
        Pg db = Pg.migrated();
        PostgresUsageLedger ledger = new PostgresUsageLedger(db.appUser(DML));
        assertTrue(ledger.head().isEmpty());
        assertTrue(ledger.verifyChain().intact());
        UsageRecord r1 = ledger.append(entry(10));
        UsageRecord r2 = ledger.append(entry(20));
        assertEquals(r1.hash(), r2.previousHash());
        assertEquals(new ChainHead(2, r2.hash()), ledger.head().orElseThrow());
        assertEquals(List.of(r1, r2), ledger.records());
        assertTrue(ledger.verifyChain().intact());
        assertEquals(30, ledger.usage("ENT-0001", "PRODUCT_A", AllowanceType.PAGES_PER_MONTH, NOV));
        assertEquals(0, ledger.usage("ENT-0001", "PRODUCT_A", AllowanceType.PAGES_PER_MONTH, NOV.plusMonths(1)));
    }

    @Test
    void peakTypesTakeTheHighestReading() {
        Pg db = Pg.migrated();
        PostgresUsageLedger ledger = new PostgresUsageLedger(db.appUser(DML));
        for (long seats : new long[]{4, 9, 6}) {
            ledger.append(new UsageEntry("ENT-0001", "PRODUCT_A", AllowanceType.CONCURRENT_SEATS, NOV, seats, seats != 9,
                    Instant.parse("2026-11-21T10:00:00Z")));
        }
        assertEquals(9, ledger.usage("ENT-0001", "PRODUCT_A", AllowanceType.CONCURRENT_SEATS, NOV));
        assertEquals(6, ledger.billableUsage("ENT-0001", "PRODUCT_A", AllowanceType.CONCURRENT_SEATS, NOV));
    }

    @Test
    void deletingTheNewestRecordsIsDetected() {
        Pg db = Pg.migrated();
        PostgresUsageLedger ledger = new PostgresUsageLedger(db.appUser(DML));
        for (int i = 1; i <= 5; i++) {
            ledger.append(entry(i));
        }
        db.exec("delete from lic_usage where sequence >= 4");
        ChainCheck c = ledger.verifyChain();
        assertEquals(ChainCheck.Problem.USAGE_CHAIN_HEAD_MISMATCH, c.problem());
        assertEquals(4, c.brokenAtSequence());
        assertTrue(c.message().contains("the newest record(s) 4..5 were removed"), c.message());
    }

    @Test
    void deletingAllRecordsAndEditingTheHeadAreDetected() {
        Pg db = Pg.migrated();
        PostgresUsageLedger ledger = new PostgresUsageLedger(db.appUser(DML));
        ledger.append(entry(1));
        ledger.append(entry(2));
        db.exec("update lic_chain_head set hash = repeat('a', 64)");
        assertEquals(ChainCheck.Problem.USAGE_CHAIN_HEAD_MISMATCH, ledger.verifyChain().problem());
        db.exec("delete from lic_usage");
        assertEquals(ChainCheck.Problem.USAGE_CHAIN_HEAD_MISMATCH, ledger.verifyChain().problem());
    }

    @Test
    void anEditedRowIsDetected() {
        Pg db = Pg.migrated();
        PostgresUsageLedger ledger = new PostgresUsageLedger(db.appUser(DML));
        ledger.append(entry(1));
        ledger.append(entry(2));
        db.exec("update lic_usage set amount = 0 where sequence = 1");
        assertEquals(ChainCheck.Problem.USAGE_CHAIN_RECORD_EDITED, ledger.verifyChain().problem());
    }

    @Test
    void concurrentAppendsNeverShareASequence() throws Exception {
        Pg db = Pg.migrated();
        PostgresUsageLedger ledger = new PostgresUsageLedger(db.appUser(DML));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<UsageRecord>> futures = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                long amount = i;
                futures.add(pool.submit(() -> ledger.append(entry(amount))));
            }
            for (Future<UsageRecord> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }
        assertEquals(40, ledger.records().size());
        assertTrue(ledger.verifyChain().intact(), ledger.verifyChain().message());
    }

    @Test
    void lastSeenOnlyMovesForwardExceptThroughAReset() {
        Pg db = Pg.migrated();
        PostgresLastSeenStore store = new PostgresLastSeenStore(db.appUser(DML));
        Instant t = Instant.parse("2026-11-21T10:00:00Z");
        assertTrue(store.lastSeen().isEmpty());
        store.advanceTo(t);
        store.advanceTo(t.minusSeconds(3600));
        assertEquals(t, store.lastSeen().orElseThrow());
        store.advanceTo(t.plusSeconds(5));
        assertEquals(t.plusSeconds(5), store.lastSeen().orElseThrow());
        assertFalse(store.resetApplied("RST-1"));
        store.resetTo(t.minusSeconds(86400), "RST-1");
        assertEquals(t.minusSeconds(86400), store.lastSeen().orElseThrow());
        assertTrue(store.resetApplied("RST-1"));
        assertThrows(LicensingStoreException.class, () -> store.resetTo(t, "RST-1"), "a reset works once");
        assertEquals(t.minusSeconds(86400), store.lastSeen().orElseThrow());
    }

    @Test
    void licenceSlotsAndPromotion() {
        Pg db = Pg.migrated();
        PostgresLicenceStore store = new PostgresLicenceStore(db.appUser(DML));
        Instant now = Instant.parse("2026-11-21T10:00:00Z");
        LicenceStore.Stored v1 = new LicenceStore.Stored("{v1}", "LIC-1", 1, "TEN-1", LocalDate.parse("2026-01-01"), now);
        LicenceStore.Stored v2 = new LicenceStore.Stored("{v2}", "LIC-2", 2, "TEN-1", LocalDate.parse("2027-01-01"), now);
        store.put(LicenceStore.Slot.CURRENT, v1);
        store.put(LicenceStore.Slot.WAITING, v2);
        assertEquals(v1, store.get(LicenceStore.Slot.CURRENT).orElseThrow());
        store.promoteWaiting("LIC-OTHER");
        assertEquals(v1, store.get(LicenceStore.Slot.CURRENT).orElseThrow(), "only the named licence is promoted");
        store.promoteWaiting("LIC-2");
        assertEquals(v2, store.get(LicenceStore.Slot.CURRENT).orElseThrow());
        assertTrue(store.get(LicenceStore.Slot.WAITING).isEmpty());
        store.promoteWaiting("LIC-2"); // a second node: nothing happens
        assertEquals(v2, store.get(LicenceStore.Slot.CURRENT).orElseThrow());
    }

    @Test
    void schemaCheckCodes() {
        Pg empty = Pg.empty();
        assertEquals(LIC_TABLES_MISSING, problem(empty.owner));
        assertEquals(List.of(), empty.tables(), "the check created nothing");

        Pg older = Pg.migrated();
        older.exec("update lic_schema_version set version = 0");
        assertEquals(LIC_VERSION_OLDER, problem(older.owner));

        Pg newer = Pg.migrated();
        newer.exec("update lic_schema_version set version = 2");
        assertEquals(LIC_VERSION_NEWER, problem(newer.owner));

        Pg noVersion = Pg.migrated();
        noVersion.exec("drop table lic_schema_version");
        assertEquals(LIC_VERSION_UNKNOWN, problem(noVersion.owner));

        Pg incomplete = Pg.migrated();
        incomplete.exec("drop table lic_chain_head");
        LicensingSchemaException e = assertThrows(LicensingSchemaException.class, () -> LicensingSchema.verify(incomplete.owner));
        assertEquals(LIC_TABLES_INCOMPLETE, e.problem());
        assertEquals("Licensing tables incomplete at version 1, missing: lic_chain_head; run the licensing schema step "
                + "(Flyway location db/platform/licensing, history table lic_schema_history) before starting the app.",
                e.getMessage());

        Pg noDelete = Pg.migrated();
        LicensingSchemaException rights = assertThrows(LicensingSchemaException.class,
                () -> LicensingSchema.verify(noDelete.appUser("select, insert, update")));
        assertEquals(LIC_PRIVILEGES_MISSING, rights.problem());
        assertTrue(rights.getMessage().startsWith("The app's database user lacks DELETE on lic_licence"));

        assertDoesNotThrow(() -> LicensingSchema.verify(Pg.migrated().appUser(DML)));
    }

    @Test
    void noTableHoldsSecretsPricesOrDocumentData() {
        Pg db = Pg.migrated();
        List<String> columns = new ArrayList<>();
        try (var c = db.owner.getConnection(); var s = c.createStatement();
             var rs = s.executeQuery("select table_name || '.' || column_name from information_schema.columns "
                     + "where table_schema = current_schema() order by 1")) {
            while (rs.next()) {
                columns.add(rs.getString(1));
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        for (String col : columns) {
            for (String word : List.of("price", "cost", "currency", "secret", "password", "private", "key", "document",
                    "content", "token")) {
                assertFalse(col.contains(word), col);
            }
        }
    }

    private static LicensingSchemaException.Problem problem(DataSource ds) {
        return assertThrows(LicensingSchemaException.class, () -> LicensingSchema.verify(ds)).problem();
    }
}
