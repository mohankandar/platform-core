package com.documentplatform.identity.jdbc;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.identity.IdentityConnectionStatus;
import com.documentplatform.identity.IdentityProviderType;
import com.documentplatform.identity.OidcConnectionBinding;
import com.documentplatform.identity.admission.AdmissionSettings;
import com.documentplatform.identity.admission.ConnectionKey;
import com.documentplatform.identity.admission.EntityId;
import com.documentplatform.identity.admission.EntityMembershipResolver;
import com.documentplatform.identity.admission.EntityRoleCatalog;
import com.documentplatform.identity.admission.MachineEntityAdmission;
import com.documentplatform.identity.mapping.Grant;
import com.documentplatform.identity.mapping.MappingPlan;
import com.documentplatform.identity.mapping.MappingRow;
import com.documentplatform.identity.mapping.RoleMappingFile;
import com.documentplatform.identity.mapping.SlotTargets;
import com.documentplatform.identity.store.ChangeSource;
import com.documentplatform.identity.store.ConnectionChange;
import com.documentplatform.identity.store.EntityRecord;
import com.documentplatform.identity.store.EntityStatus;
import com.documentplatform.identity.store.EntityStore;
import com.documentplatform.identity.store.LoginConnection;
import com.documentplatform.identity.store.SlotSync;
import com.documentplatform.licensing.spring.notifications.ActivatedSlots;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The Postgres stores and sign-in lookups, run as the DML-only app user the install guide asks for. */
class PostgresIdentityStoresTest {

    static final String DML = "select, insert, update, delete";
    static final String TENANT = "TEN-TEST-0001";
    static final String OTHER_TENANT = "TEN-TEST-0002";
    static final String S1 = "ENT-TEST-0001";
    static final String S2 = "ENT-TEST-0002";
    static final String S3 = "ENT-TEST-0003";
    static final URI ISSUER = URI.create("https://login.example.test/oauth2/default");
    static final Instant T = Instant.parse("2026-11-21T10:00:00Z");

    static final EntityRoleCatalog ROLES = new EntityRoleCatalog() {
        @Override
        public Optional<String> canonicalEntityRole(String configuredName) {
            return Set.of("READER", "EDITOR", "AUDITOR").contains(configuredName) ? Optional.of(configuredName) : Optional.empty();
        }

        @Override
        public boolean isPlatformRole(String canonicalRole) {
            return false;
        }
    };

    static LoginConnection connection(String tenant, String product, String audience) {
        return new LoginConnection(UUID.randomUUID(), tenant, product, "okta", IdentityProviderType.OIDC, ISSUER,
                product.toLowerCase() + "-web", audience, null, IdentityConnectionStatus.ACTIVE, 1);
    }

    /** A customer with slots S1 and S2 activated and an ACTIVE PRODUCT_A connection. */
    private static DataSource twoEntities(Pg db) {
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
    void slotsGetTheirDerivedUuidAndTheSameUuidInEveryDatabase() {
        UUID first = null;
        for (int i = 0; i < 2; i++) {
            PostgresEntityStore store = new PostgresEntityStore(Pg.migrated().appUser(DML));
            store.saveCustomer(TENANT, "Test Customer");
            SlotSync sync = store.syncSlots(TENANT, List.of(S1));
            assertEquals(List.of(S1), sync.created());
            EntityRecord r = store.entity(S1).orElseThrow();
            assertEquals(EntityUuid.fromSlotId(S1), r.entityUuid());
            assertEquals(EntityStatus.NOT_ACTIVATED, r.status());
            assertNull(r.displayName());
            if (first == null) first = r.entityUuid();
            assertEquals(first, r.entityUuid(), "two databases (two products, or hosting) give the same entity UUID");
            assertEquals(r, store.entity(r.entityUuid()).orElseThrow());
        }
    }

    @Test
    void customerAndSlotSyncAreIdempotentAndNeverDelete() {
        Pg db = Pg.migrated();
        PostgresEntityStore store = new PostgresEntityStore(db.appUser(DML));
        assertEquals(EntityStore.Change.CREATED, store.saveCustomer(TENANT, "Test Customer"));
        assertEquals(EntityStore.Change.UNCHANGED, store.saveCustomer(TENANT, "Test Customer"));
        assertEquals(EntityStore.Change.UPDATED, store.saveCustomer(TENANT, "Test Customer Ltd"));
        assertEquals("Test Customer Ltd", store.customerName(TENANT).orElseThrow());

        assertEquals(List.of(S1, S2), store.syncSlots(TENANT, List.of(S1, S2)).created());
        SlotSync again = store.syncSlots(TENANT, List.of(S1, S2));
        assertFalse(again.changed());
        assertEquals(List.of(S1, S2), again.unchanged());
        assertThrows(IllegalStateException.class, () -> store.syncSlots("TEN-NOT-STORED", List.of("ENT-X1")));

        store.saveCustomer(OTHER_TENANT, "Other");
        IllegalStateException taken = assertThrows(IllegalStateException.class,
                () -> store.syncSlots(OTHER_TENANT, List.of(S1)));
        assertEquals("slot " + S1 + " belongs to another customer", taken.getMessage());
        assertEquals(2, db.count("select count(*) from idn_entity"));
    }

    @Test
    void aSlotDroppedFromTheLicenceBecomesNotInLicenceAndComesBackAsItWas() {
        Pg db = Pg.migrated();
        PostgresEntityStore store = new PostgresEntityStore(db.appUser(DML));
        store.saveCustomer(TENANT, "Test Customer");
        store.syncSlots(TENANT, List.of(S1, S2, S3));
        store.activate(S1, "Alpha", "admin-1", T);

        SlotSync newer = store.syncSlots(TENANT, List.of(S3));
        assertEquals(List.of(S1, S2), newer.notInLicence());
        assertEquals(3, db.count("select count(*) from idn_entity"), "nothing is deleted");
        EntityRecord dropped = store.entity(S1).orElseThrow();
        assertEquals(EntityStatus.NOT_IN_LICENCE, dropped.status());
        assertEquals("Alpha", dropped.displayName(), "the name and activation are kept");
        assertFalse(store.activated(TENANT).containsKey(S1), "licensing no longer counts it");
        assertTrue(store.syncSlots(TENANT, List.of(S3)).unchanged().containsAll(List.of(S1, S2, S3)));

        SlotSync back = store.syncSlots(TENANT, List.of(S1, S2, S3));
        assertEquals(List.of(S1, S2), back.backInLicence());
        assertEquals(EntityStatus.ACTIVE, store.entity(S1).orElseThrow().status(), "it had been activated");
        assertEquals(EntityStatus.NOT_ACTIVATED, store.entity(S2).orElseThrow().status(), "it had not");
    }

    @Test
    void activatedSlotsFeedLicensingIncludingSuspendedOnes() {
        Pg db = Pg.migrated();
        DataSource app = twoEntities(db);
        PostgresEntityStore store = new PostgresEntityStore(app);
        db.exec("update idn_entity set status = 'SUSPENDED' where slot_id = '" + S2 + "'");
        ActivatedSlots slots = store::activated; // the licensing interface, implemented by the store
        assertEquals(Map.of(S1, EntityUuid.fromSlotId(S1).toString(), S2, EntityUuid.fromSlotId(S2).toString()),
                slots.activated(TENANT), "S3 is not activated");
        assertEquals(Map.of(), slots.activated(OTHER_TENANT));
    }

    @Test
    void oneLoginConnectionIsSharedByTheCustomersEntities() {
        Pg db = Pg.migrated();
        DataSource app = twoEntities(db);
        PostgresIdentityLookup lookup = new PostgresIdentityLookup(app, "PRODUCT_A");
        ConnectionKey key = new ConnectionKey("okta", ISSUER, "api://product-a");
        EntityId e1 = EntityId.of(EntityUuid.fromSlotId(S1));
        EntityId e2 = EntityId.of(EntityUuid.fromSlotId(S2));

        assertEquals(1, db.count("select count(*) from idn_login_connection"));
        assertEquals(Set.of(e1, e2), lookup.find(key), "both entities, one connection; S3 is not activated");
        assertEquals(Optional.empty(), lookup.findActive("okta", ISSUER, "api://product-a"),
                "two entities: no single binding, the person chooses");

        PostgresGroupGrantStore grants = new PostgresGroupGrantStore(app);
        grants.add(S1, "PRODUCT_A", ISSUER, List.of(new Grant("staff", "READER")));
        grants.add(S2, "PRODUCT_A", ISSUER, List.of(new Grant("staff", "EDITOR"), new Grant("beta-only", "READER")));
        EntityMembershipResolver resolver = new EntityMembershipResolver(lookup, lookup, ROLES,
                new AdmissionSettings(true, true, "okta", "api://product-a"));
        assertEquals(Map.of(e1, Set.of("READER"), e2, Set.of("EDITOR")),
                resolver.authorizedEntities(ISSUER, List.of("staff")), "roles in one entity never come from another");
        assertEquals(Map.of(e2, Set.of("READER")), resolver.authorizedEntities(ISSUER, List.of("beta-only")));

        // Exactness and blanks.
        assertEquals(Set.of(), lookup.find(new ConnectionKey("okta", URI.create(ISSUER + "/"), "api://product-a")));
        assertEquals(Set.of(), lookup.find(new ConnectionKey("", ISSUER, "api://product-a")));
        assertEquals(Map.of(), lookup.activeGrants(new ConnectionKey("okta", ISSUER, " ")));

        // A suspended entity drops out; with one ACTIVE entity left the binding names it.
        db.exec("update idn_entity set status = 'SUSPENDED' where slot_id = '" + S2 + "'");
        assertEquals(Set.of(e1), lookup.find(key));
        OidcConnectionBinding binding = lookup.findActive("okta", ISSUER, "api://product-a").orElseThrow();
        assertEquals(e1.value(), binding.tenantId());
        assertEquals(1, binding.configurationVersion());
    }

    @Test
    void grantsAreSeparatedByProductCode() {
        Pg db = Pg.migrated();
        DataSource app = twoEntities(db);
        new PostgresLoginConnectionStore(app).insert(connection(TENANT, "PRODUCT_B", "api://product-b"),
                ChangeSource.SETUP, "setup");
        PostgresGroupGrantStore grants = new PostgresGroupGrantStore(app);
        grants.add(S1, "PRODUCT_A", ISSUER, List.of(new Grant("staff", "READER")));
        grants.add(S1, "PRODUCT_B", ISSUER, List.of(new Grant("staff", "AUDITOR")));
        EntityId e1 = EntityId.of(EntityUuid.fromSlotId(S1));

        assertEquals(Map.of(e1, Map.of("staff", Set.of("READER"))),
                new PostgresIdentityLookup(app, "PRODUCT_A").activeGrants(new ConnectionKey("okta", ISSUER, "api://product-a")));
        assertEquals(Map.of(e1, Map.of("staff", Set.of("AUDITOR"))),
                new PostgresIdentityLookup(app, "PRODUCT_B").activeGrants(new ConnectionKey("okta", ISSUER, "api://product-b")));
        assertEquals(Map.of(), new PostgresIdentityLookup(app, "PRODUCT_B")
                .activeGrants(new ConnectionKey("okta", ISSUER, "api://product-a")), "PRODUCT_A's audience is not B's");
        assertEquals(Set.of(new Grant("staff", "READER")), grants.grants(S1, "PRODUCT_A", ISSUER));
        assertEquals(Map.of(S1, Set.of(new Grant("staff", "AUDITOR"))), grants.grantsOfCustomer(TENANT, "PRODUCT_B", ISSUER));

        // A grant recorded for another issuer never applies.
        grants.add(S2, "PRODUCT_A", URI.create("https://old-login.example.test"), List.of(new Grant("staff", "EDITOR")));
        assertFalse(new PostgresIdentityLookup(app, "PRODUCT_A")
                .activeGrants(new ConnectionKey("okta", ISSUER, "api://product-a")).containsKey(EntityId.of(EntityUuid.fromSlotId(S2))));
        assertEquals(0, grants.add(S1, "PRODUCT_A", ISSUER, List.of(new Grant("staff", "READER"))), "already there");
        assertThrows(IllegalStateException.class, () -> grants.add("ENT-NOT-STORED", "PRODUCT_A", ISSUER,
                List.of(new Grant("g", "READER"))));
    }

    @Test
    void aMappingFileWithSlotIdsPlansAgainstTheStoredGrantsPerProduct() {
        Pg db = Pg.migrated();
        DataSource app = twoEntities(db);
        PostgresEntityStore entities = new PostgresEntityStore(app);
        PostgresGroupGrantStore grants = new PostgresGroupGrantStore(app);
        grants.add(S1, "PRODUCT_A", ISSUER, List.of(new Grant("old", "READER")));
        grants.add(S1, "PRODUCT_B", ISSUER, List.of(new Grant("b-group", "AUDITOR")));
        SlotTargets slots = slot -> entities.entity(slot).map(r -> new SlotTargets.Slot(r.displayName(),
                r.status() != EntityStatus.NOT_IN_LICENCE));
        RoleMappingFile.Result file = RoleMappingFile.forSlots("test-role-mapping/1", "PRODUCT_A", ROLES, slots)
                .read(("entity_id,entity_name,group,role\n" + S1 + ",Alpha,staff,READER\n" + S3 + ",,staff,READER\n")
                        .getBytes(StandardCharsets.UTF_8));
        assertTrue(file.valid(), file.errors().toString());
        List<MappingRow> rows = file.rows();
        MappingPlan plan = MappingPlan.perProduct(rows, (entity, slot, product) -> grants.grants(slot, product, ISSUER));
        assertEquals(Set.of(new Grant("staff", "READER")), plan.entities().get(0).toAdd());
        assertEquals(Set.of(new Grant("old", "READER")), plan.entities().get(0).toRemove());
        assertEquals(S3, plan.entities().get(1).slotId(), "a slot not activated yet can be prepared");
        for (MappingPlan.EntityChange change : plan.entities()) {
            grants.add(change.slotId(), change.productCode(), ISSUER, change.toAdd());
        }
        assertEquals(Set.of(new Grant("b-group", "AUDITOR")), grants.grants(S1, "PRODUCT_B", ISSUER),
                "the other product's grants are untouched");
        assertFalse(MappingPlan.perProduct(rows, (e, slot, p) -> grants.grants(slot, p, ISSUER)).entities().get(1).changes());
    }

    @Test
    void connectionsKeepHistoryAndOneActivePerCustomerAndProduct() {
        Pg db = Pg.migrated();
        DataSource app = twoEntities(db);
        PostgresLoginConnectionStore store = new PostgresLoginConnectionStore(app);
        LoginConnection active = store.active(TENANT, "PRODUCT_A").orElseThrow();
        assertEquals(1, active.configurationVersion());

        IllegalStateException second = assertThrows(IllegalStateException.class,
                () -> store.insert(connection(TENANT, "PRODUCT_A", "api://other"), ChangeSource.APP, "admin-1"));
        assertEquals("customer " + TENANT + " already has an ACTIVE login connection for PRODUCT_A", second.getMessage());
        LoginConnection draft = new LoginConnection(UUID.randomUUID(), TENANT, "PRODUCT_A", "okta",
                IdentityProviderType.OIDC, ISSUER, null, "api://staged", null, IdentityConnectionStatus.DRAFT, 1);
        assertEquals(1, store.insert(draft, ChangeSource.APP, "admin-1").configurationVersion(), "a staged one is fine");

        LoginConnection changed = new LoginConnection(active.connectionId(), TENANT, "PRODUCT_A", "okta",
                IdentityProviderType.OIDC, ISSUER, "new-web", "api://product-a", "vault:kv/customers/TEN-TEST-0001/login#client_secret",
                IdentityConnectionStatus.ACTIVE, 1);
        LoginConnection v2 = store.update(changed, 1, ChangeSource.APP, "admin-1").orElseThrow();
        assertEquals(2, v2.configurationVersion());
        assertEquals(Optional.empty(), store.update(changed, 1, ChangeSource.SETUP, "setup"), "stale version: nothing written");
        assertEquals(v2, store.find(active.connectionId()).orElseThrow());
        List<ConnectionChange> history = store.history(active.connectionId());
        assertEquals(List.of(1L, 2L), history.stream().map(ConnectionChange::configurationVersion).toList());
        assertEquals(List.of(ChangeSource.SETUP, ChangeSource.APP), history.stream().map(ConnectionChange::source).toList());
        assertEquals("admin-1", history.get(1).changedBy());

        LoginConnection draftActivated = new LoginConnection(draft.connectionId(), TENANT, "PRODUCT_A", "okta",
                IdentityProviderType.OIDC, ISSUER, null, "api://staged", null, IdentityConnectionStatus.ACTIVE, 1);
        assertThrows(IllegalStateException.class, () -> store.update(draftActivated, 1, ChangeSource.APP, "admin-1"),
                "a staged connection cannot become a second ACTIVE one");
        assertThrows(IllegalStateException.class,
                () -> store.insert(connection("TEN-NOT-STORED", "PRODUCT_A", "a"), ChangeSource.SETUP, "setup"));
        assertThrows(IllegalArgumentException.class, () -> new LoginConnection(UUID.randomUUID(), TENANT, "PRODUCT_A",
                "okta", IdentityProviderType.OIDC, ISSUER, null, "aud", "plain-secret-value", IdentityConnectionStatus.ACTIVE, 1),
                "a secret value is refused where the reference goes");
    }

    @Test
    void machineBindingsKeepOnlyActiveEntitiesOfTheConnection() {
        Pg db = Pg.migrated();
        DataSource app = twoEntities(db);
        UUID e1 = EntityUuid.fromSlotId(S1), e3 = EntityUuid.fromSlotId(S3);
        PostgresMachineEntityBindings bindings = new PostgresMachineEntityBindings(app, "PRODUCT_A",
                client -> client.equals("scanner-1") ? Set.of(e1, e3) : Set.of());
        ConnectionKey key = new ConnectionKey("okta", ISSUER, "api://product-a");
        assertEquals(Set.of(EntityId.of(e1)), bindings.find(key, "scanner-1"), "S3 is not activated");
        assertEquals(EntityId.of(e1), new MachineEntityAdmission().requireUnique(bindings.find(key, "scanner-1")));
        assertEquals(Set.of(), bindings.find(key, "unknown"));
        assertEquals(Set.of(), bindings.find(key, " "));
        assertNull(new PostgresMachineEntityBindings(app, "PRODUCT_A", client -> null).find(key, "scanner-1"),
                "the app's table could not be read");
    }

    @Test
    void anUnreadableStoreRefusesSignIn() {
        Pg db = Pg.migrated();
        DataSource noRights = db.appUser("insert");
        PostgresIdentityLookup lookup = new PostgresIdentityLookup(noRights, "PRODUCT_A");
        ConnectionKey key = new ConnectionKey("okta", ISSUER, "api://product-a");
        assertNull(lookup.find(key));
        assertNull(lookup.activeGrants(key));
        IdentityStoreException e = assertThrows(IdentityStoreException.class,
                () -> lookup.findActive("okta", ISSUER, "api://product-a"));
        assertEquals("Identity store: read login connection failed (SQL state 42501)", e.getMessage());
    }
}
