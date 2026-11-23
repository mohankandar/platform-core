package com.documentplatform.identity.jdbc;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.identity.mapping.Grant;
import com.documentplatform.identity.store.ChangeSource;
import com.documentplatform.identity.store.GrantAuthor;
import com.documentplatform.identity.store.GrantChange;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static com.documentplatform.identity.store.GrantChange.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

/** P031 B4: idn_grant_history (identity V3), written with every grant change, insert only, read by the app user. */
class GrantHistoryTest {

    static final String TENANT = "TEN-TEST-0001";
    static final String S1 = "ENT-TEST-0001";
    static final String S2 = "ENT-TEST-0002";
    static final URI ISSUER = URI.create("https://login.example.test/oauth2/default");

    static DataSource installed(Pg db) {
        DataSource app = db.appUser(PostgresIdentityStoresTest.DML);
        PostgresEntityStore e = new PostgresEntityStore(app);
        e.saveCustomer(TENANT, "Test Customer");
        e.syncSlots(TENANT, List.of(S1, S2));
        return app;
    }

    @Test
    void everyGrantChangeWritesItsRowsAndTheAppReadsThemPerEntity() {
        Pg db = Pg.migrated();
        DataSource app = installed(db);
        PostgresGroupGrantStore grants = new PostgresGroupGrantStore(app);
        Instant before = Instant.now().minusSeconds(5);

        assertEquals(2, grants.add(S1, "PRODUCT_A", ISSUER, List.of(new Grant("a", "READER"), new Grant("b", "EDITOR")),
                GrantAuthor.setup("setup", "run-1")));
        assertEquals(1, grants.add(S2, "PRODUCT_A", ISSUER, List.of(new Grant("a", "READER")),
                GrantAuthor.setup("setup", "run-1")));
        assertEquals(0, grants.add(S1, "PRODUCT_A", ISSUER, List.of(new Grant("a", "READER")),
                GrantAuthor.setup("setup", "run-2")), "a rerun adds nothing and writes no row");
        assertTrue(grants.changeRole(S1, "PRODUCT_A", ISSUER, "a", "READER", "AUDITOR", GrantAuthor.app("user-7")));
        assertEquals(1, grants.remove(S1, "PRODUCT_A", ISSUER, List.of(new Grant("b", "EDITOR")),
                GrantAuthor.app("user-7")));
        assertEquals(1, grants.add(S1, "PRODUCT_B", ISSUER, List.of(new Grant("c", "READER")))); // old method

        List<GrantChange> h = new PostgresGrantHistory(app).forEntity(EntityUuid.fromSlotId(S1), before,
                Instant.now().plusSeconds(5));
        assertEquals(List.of(ADDED, ADDED, CHANGED, REMOVED, ADDED), h.stream().map(GrantChange::change).toList());
        GrantChange first = h.get(0);
        assertEquals(TENANT, first.tenantId());
        assertEquals(S1, first.slotId());
        assertEquals(ISSUER, first.issuer());
        assertEquals(ChangeSource.SETUP, first.source());
        assertEquals("run-1", first.setupRunId());
        assertNull(first.roleBefore());
        GrantChange changed = h.get(2);
        assertEquals("a", changed.group());
        assertEquals("READER", changed.roleBefore());
        assertEquals("AUDITOR", changed.roleAfter());
        assertEquals("user-7", changed.changedBy());
        assertNull(changed.setupRunId());
        assertEquals(GrantAuthor.UNNAMED_APP_USER, h.get(4).changedBy());
        assertEquals("PRODUCT_B", h.get(4).productCode());
        assertEquals(List.of(), new PostgresGrantHistory(app).forEntity(EntityUuid.fromSlotId(S1),
                Instant.now().plusSeconds(60), Instant.now().plusSeconds(120)));
        assertEquals(1, new PostgresGrantHistory(app).forEntity(EntityUuid.fromSlotId(S2), before,
                Instant.now().plusSeconds(5)).size());
    }

    @Test
    void oneChangeOfOneSlotPairsARemovedAndAnAddedRoleAsChanged() {
        Pg db = Pg.migrated();
        DataSource app = installed(db);
        PostgresGroupGrantStore grants = new PostgresGroupGrantStore(app);
        grants.add(S1, "PRODUCT_A", ISSUER, List.of(new Grant("a", "READER")), GrantAuthor.setup("setup", "run-1"));
        assertEquals(2, grants.change(S1, "PRODUCT_A", ISSUER, List.of(new Grant("a", "EDITOR")),
                List.of(new Grant("a", "READER")), GrantAuthor.setup("setup", "run-2")));
        assertEquals(Set.of(new Grant("a", "EDITOR")), grants.grants(S1, "PRODUCT_A", ISSUER));
        assertEquals(List.of("ADDED", "CHANGED"), db.strings("select change from idn_grant_history order by history_id"));
        assertEquals(List.of("run-1", "run-2"), db.strings("select setup_run_id from idn_grant_history order by history_id"));
    }

    @Test
    void theHistoryIsInsertOnlyEvenForTheOwner() {
        Pg db = Pg.migrated();
        DataSource app = installed(db);
        new PostgresGroupGrantStore(app).add(S1, "PRODUCT_A", ISSUER, List.of(new Grant("a", "READER")),
                GrantAuthor.app("user-1"));
        for (String sql : List.of("update idn_grant_history set changed_by = 'someone-else'",
                "delete from idn_grant_history", "truncate idn_grant_history")) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> db.exec(sql), sql);
            assertTrue(String.valueOf(e.getCause().getMessage()).contains("insert-only"), sql);
            assertThrows(IllegalStateException.class, () -> Pg.exec(app, sql), sql);
        }
        assertEquals(1, db.count("select count(*) from idn_grant_history"));
        // A setup row names its run; an app row does not.
        assertThrows(IllegalStateException.class, () -> db.exec("insert into idn_grant_history (tenant_id, "
                + "product_code, slot_id, entity_uuid, issuer_uri, external_group, role_after, change, change_source, "
                + "changed_by) values ('" + TENANT + "', 'PRODUCT_A', '" + S1 + "', gen_random_uuid(), 'x', 'g', 'R', "
                + "'ADDED', 'SETUP', 'setup')"));
    }

    @Test
    void theAppUserNeedsOnlySelectAndInsertOnTheHistory() {
        Pg db = Pg.migrated();
        DataSource app = db.appUser(PostgresIdentityStoresTest.DML);
        db.exec("revoke update, delete on idn_grant_history from " + db.schema + "_app");
        IdentitySchema.verify(app);
        db.exec("revoke insert on idn_grant_history from " + db.schema + "_app");
        IdentitySchemaException e = assertThrows(IdentitySchemaException.class, () -> IdentitySchema.verify(app));
        assertEquals(IdentitySchemaException.Problem.IDN_PRIVILEGES_MISSING, e.problem());
        assertTrue(e.getMessage().contains("INSERT on idn_grant_history"), e.getMessage());
    }

    @Test
    void aVersion2DatabaseIsRefusedUntilV3AndV4AreRunAndKeepsItsData() {
        Pg db = Pg.empty();
        db.exec(IdentitySchema.script("V1__identity.sql"), IdentitySchema.script("V2__machine_binding.sql"));
        db.exec("insert into idn_customer (tenant_id, name) values ('" + TENANT + "', 'Kept')");
        IdentitySchemaException older = assertThrows(IdentitySchemaException.class, () -> IdentitySchema.verify(db.owner));
        assertEquals(IdentitySchemaException.Problem.IDN_VERSION_OLDER, older.problem());
        db.exec(IdentitySchema.script("V3__grant_history.sql"));
        // A history row written at V3 is an ENTITY row after V4; the V4 step changes no row.
        db.exec("insert into idn_grant_history (tenant_id, product_code, slot_id, entity_uuid, issuer_uri, "
                + "external_group, role_after, change, change_source, changed_by) values ('" + TENANT + "', "
                + "'PRODUCT_A', 'ENT-TEST-0001', gen_random_uuid(), 'https://login.example.test/', 'g', 'READER', "
                + "'ADDED', 'APP', 'app')");
        assertEquals(IdentitySchemaException.Problem.IDN_VERSION_OLDER,
                assertThrows(IdentitySchemaException.class, () -> IdentitySchema.verify(db.owner)).problem());
        db.exec(IdentitySchema.script("V4__platform_grants.sql"));
        IdentitySchema.verify(db.owner);
        assertEquals(1, db.count("select count(*) from idn_customer"));
        assertEquals(1, db.count("select count(*) from idn_grant_history where scope = 'ENTITY'"));
        assertEquals(4, IdentitySchema.VERSION);
    }
}
