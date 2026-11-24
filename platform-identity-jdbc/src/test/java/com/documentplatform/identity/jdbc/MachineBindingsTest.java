package com.documentplatform.identity.jdbc;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.identity.admission.ConnectionKey;
import com.documentplatform.identity.admission.EntityId;
import com.documentplatform.identity.store.ChangeSource;
import com.documentplatform.identity.store.MachineBinding;
import com.documentplatform.identity.store.MachineBindingChange;
import com.documentplatform.identity.store.MachineBindingStatus;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.Set;

import static com.documentplatform.identity.jdbc.PostgresIdentityStoresTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Integration client bindings in Postgres ({@code idn_machine_binding}, V2) with the DML-only app user. */
class MachineBindingsTest {

    private static final ConnectionKey KEY = new ConnectionKey("okta", ISSUER, "api://product-a");
    private static final EntityId E1 = EntityId.of(EntityUuid.fromSlotId(S1));

    /** A customer with S1, S2 activated (S3 not), and an ACTIVE PRODUCT_A connection; DML-only user. */
    private static DataSource setUp(Pg db) {
        DataSource app = db.appUser(DML);
        PostgresEntityStore entities = new PostgresEntityStore(app);
        entities.saveCustomer(TENANT, "Test Customer");
        entities.syncSlots(TENANT, List.of(S1, S2, S3));
        entities.activate(S1, "Alpha", "admin-1", T);
        entities.activate(S2, "Beta", "admin-1", T);
        new PostgresLoginConnectionStore(app).insert(connection(TENANT, "PRODUCT_A", "api://product-a"),
                ChangeSource.SETUP, "setup");
        return app;
    }

    @Test
    void storeVersionsAndHistoryWithTheDmlOnlyUser() {
        Pg db = Pg.migrated();
        DataSource app = setUp(db);
        IdentitySchema.verify(app);
        PostgresMachineBindingStore store = new PostgresMachineBindingStore(app);

        MachineBinding v1 = store.insertBinding(MachineBinding.create(TENANT, "PRODUCT_A", "0oaLoan01", S1,
                "loan-system", "Loan system"), ChangeSource.SETUP, "setup");
        assertEquals(1, v1.version());
        assertEquals(List.of(v1), store.bindings(TENANT, "PRODUCT_A"));
        assertEquals(v1, store.activeBinding("PRODUCT_A", "0oaLoan01").orElseThrow());
        assertTrue(store.activeBinding("PRODUCT_A", "0OALOAN01").isEmpty(), "client IDs are case-sensitive");
        assertTrue(store.activeBinding("PRODUCT_B", "0oaLoan01").isEmpty(), "per product");

        MachineBinding off = store.updateBinding(v1.switchedOff(), 1, ChangeSource.SETUP, "setup").orElseThrow();
        assertEquals(MachineBindingStatus.INACTIVE, off.status());
        assertTrue(store.activeBinding("PRODUCT_A", "0oaLoan01").isEmpty());
        assertTrue(store.updateBinding(off.activeFor(S2, "loan-system", null), 1, ChangeSource.APP, "it-admin")
                .isEmpty(), "stale version");
        MachineBinding moved = store.updateBinding(off.activeFor(S2, "loan-system", null), 2, ChangeSource.APP,
                "it-admin").orElseThrow();
        assertEquals(EntityUuid.fromSlotId(S2), moved.entityUuid());

        List<MachineBindingChange> history = store.bindingHistory(v1.bindingId());
        assertEquals(List.of(1L, 2L, 3L), history.stream().map(MachineBindingChange::version).toList());
        assertEquals(List.of(MachineBindingStatus.ACTIVE, MachineBindingStatus.INACTIVE, MachineBindingStatus.ACTIVE),
                history.stream().map(MachineBindingChange::status).toList());
        assertEquals(List.of(S1, S1, S2), history.stream().map(MachineBindingChange::slotId).toList());
        assertEquals(ChangeSource.APP, history.get(2).changeSource());
        assertEquals("it-admin", history.get(2).changedBy());

        // One binding per customer, product and client ID; the entity slot must be stored.
        assertThrows(IllegalStateException.class, () -> store.insertBinding(MachineBinding.create(TENANT,
                "PRODUCT_A", "0oaLoan01", S3, "loan-system", null), ChangeSource.SETUP, "setup"));
        assertThrows(IllegalStateException.class, () -> store.insertBinding(MachineBinding.create(TENANT,
                "PRODUCT_A", "other", "ENT-NOT-STORED", "loan-system", null), ChangeSource.SETUP, "setup"));
        assertThrows(IllegalStateException.class, () -> db.exec("insert into idn_machine_binding (binding_id, "
                + "tenant_id, product_code, client_id, slot_id, entity_uuid, source_key, status, version) values ("
                + "gen_random_uuid(), '" + TENANT + "', 'PRODUCT_A', '0oaLoan01', '" + S3 + "', '"
                + EntityUuid.fromSlotId(S3) + "', 'x', 'INACTIVE', 1)"), "the unique index holds even without the code");
        assertThrows(IllegalStateException.class, () -> db.exec("update idn_machine_binding set client_id = 'has space'"),
                "the table refuses a client ID with a space");

        // The runtime user cannot change the tables.
        assertThrows(IllegalStateException.class, () -> Pg.exec(app, "alter table idn_machine_binding add column x int"));
        assertThrows(IllegalStateException.class, () -> Pg.exec(app, "create table idn_extra (x int)"));
    }

    @Test
    void theBindingCountsOnlyWhileBindingEntityAndConnectionAreActive() {
        Pg db = Pg.migrated();
        DataSource app = setUp(db);
        PostgresMachineBindingStore store = new PostgresMachineBindingStore(app);
        PostgresMachineEntityBindings bindings = new PostgresMachineEntityBindings(app, "PRODUCT_A");

        assertEquals(Set.of(), bindings.find(KEY, "0oaLoan01"), "no binding yet");
        MachineBinding b = store.insertBinding(MachineBinding.create(TENANT, "PRODUCT_A", "0oaLoan01", S1,
                "loan-system", null), ChangeSource.SETUP, "setup");
        assertEquals(Set.of(E1), bindings.find(KEY, "0oaLoan01"));
        assertEquals("loan-system", new PostgresMachineClients(app, "PRODUCT_A").activeBinding("0oaLoan01")
                .orElseThrow().sourceKey());
        assertEquals(Set.of(), bindings.find(KEY, "someone-else"));
        assertEquals(Set.of(), bindings.find(KEY, " "));
        assertEquals(Set.of(), new PostgresMachineEntityBindings(app, "PRODUCT_B").find(KEY, "0oaLoan01"),
                "another product's bindings do not count");

        // Entity not ACTIVE.
        db.exec("update idn_entity set status = 'SUSPENDED' where slot_id = '" + S1 + "'");
        assertEquals(Set.of(), bindings.find(KEY, "0oaLoan01"));
        db.exec("update idn_entity set status = 'ACTIVE' where slot_id = '" + S1 + "'");
        assertEquals(Set.of(E1), bindings.find(KEY, "0oaLoan01"));

        // Connection not ACTIVE.
        db.exec("update idn_login_connection set status = 'DISABLED'");
        assertEquals(Set.of(), bindings.find(KEY, "0oaLoan01"));
        db.exec("update idn_login_connection set status = 'ACTIVE'");
        assertEquals(Set.of(E1), bindings.find(KEY, "0oaLoan01"));

        // Binding switched off.
        store.updateBinding(b.switchedOff(), 1, ChangeSource.SETUP, "setup");
        assertEquals(Set.of(), bindings.find(KEY, "0oaLoan01"));

        // A slot that is not activated yet: bound, but not used until it is ACTIVE.
        store.insertBinding(MachineBinding.create(TENANT, "PRODUCT_A", "0oaLater03", S3, "loan-system", null),
                ChangeSource.SETUP, "setup");
        assertEquals(Set.of(), bindings.find(KEY, "0oaLater03"));
        new PostgresEntityStore(app).activate(S3, "Gamma", "admin-1", T);
        assertEquals(Set.of(EntityId.of(EntityUuid.fromSlotId(S3))), bindings.find(KEY, "0oaLater03"));

        // The table cannot be read: null, so the library refuses.
        db.exec("revoke select on idn_machine_binding from " + db.schema + "_app");
        assertNull(new PostgresMachineClients(app, "PRODUCT_A").entitiesWithEnabledClient("0oaLater03"));
        assertNull(bindings.find(KEY, "0oaLater03"));
    }

    @Test
    void aV1DatabaseIsRefusedUntilV2IsRun() {
        Pg db = Pg.empty();
        db.exec(IdentitySchema.script("V1__identity.sql"));
        IdentitySchemaException older = assertThrows(IdentitySchemaException.class, () -> IdentitySchema.verify(db.owner));
        assertEquals(IdentitySchemaException.Problem.IDN_VERSION_OLDER, older.problem());
        db.exec(IdentitySchema.script("V2__machine_binding.sql"));
        assertEquals(IdentitySchemaException.Problem.IDN_VERSION_OLDER,
                assertThrows(IdentitySchemaException.class, () -> IdentitySchema.verify(db.owner)).problem());
        db.exec(IdentitySchema.script("V3__grant_history.sql"));
        assertEquals(IdentitySchemaException.Problem.IDN_VERSION_OLDER,
                assertThrows(IdentitySchemaException.class, () -> IdentitySchema.verify(db.owner)).problem());
        db.exec(IdentitySchema.script("V4__platform_grants.sql"));
        db.exec(IdentitySchema.script("V5__identity_history_and_search_path.sql"));
        IdentitySchema.verify(db.owner);
    }
}
