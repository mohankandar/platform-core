package com.documentplatform.identity.jdbc;

import com.documentplatform.identity.IdentityConnectionStatus;
import com.documentplatform.identity.admission.ConnectionKey;
import com.documentplatform.identity.admission.EntityGroupAuthorization;
import com.documentplatform.identity.admission.EntityId;
import com.documentplatform.identity.admission.EntityRoleCatalog;
import com.documentplatform.identity.admission.IdentityAdmissionException;
import com.documentplatform.identity.admission.PlatformGroupAuthorization;
import com.documentplatform.identity.admission.PlatformGroupGrants;
import com.documentplatform.identity.inmemory.InMemoryIdentityStore;
import com.documentplatform.identity.mapping.Grant;
import com.documentplatform.identity.store.ChangeSource;
import com.documentplatform.identity.store.GrantAuthor;
import com.documentplatform.identity.store.GrantChange;
import com.documentplatform.identity.store.GrantHistory;
import com.documentplatform.identity.store.LoginConnection;
import com.documentplatform.identity.store.LoginConnectionStore;
import com.documentplatform.identity.store.PlatformGrantChange;
import com.documentplatform.identity.store.PlatformGrantStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.documentplatform.identity.jdbc.PostgresIdentityStoresTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * P028 B5: platform-wide grants ({@code idn_platform_grant}, identity V4) and {@link PlatformGroupAuthorization}, with
 * the same rules in memory and in Postgres (the DML-only app user).
 */
class PlatformGrantsTest {

    static final String AUD = "api://product-a";
    static final ConnectionKey KEY = new ConnectionKey("okta", ISSUER, AUD);
    static final Instant FROM = Instant.parse("2000-01-01T00:00:00Z");
    static final Instant TO = Instant.parse("2100-01-01T00:00:00Z");

    /** Made-up roles: READER per entity, PLATFORM_OPS platform-wide. */
    static final EntityRoleCatalog ROLES = new EntityRoleCatalog() {
        @Override
        public Optional<String> canonicalEntityRole(String name) {
            return List.of("READER", "PLATFORM_OPS").stream().filter(r -> r.equalsIgnoreCase(name)).findFirst();
        }

        @Override
        public boolean isPlatformRole(String role) {
            return role.equals("PLATFORM_OPS");
        }
    };

    record World(PlatformGrantStore store, LoginConnectionStore connections, GrantHistory history,
                 PlatformGroupGrants lookup, Pg db) {
    }

    static World world(String kind) {
        if (kind.equals("memory")) {
            InMemoryIdentityStore s = new InMemoryIdentityStore();
            s.saveCustomer(TENANT, "Test Customer");
            s.insert(connection(TENANT, "PRODUCT_A", AUD), ChangeSource.SETUP, "setup");
            return new World(s, s, s, s.lookup("PRODUCT_A"), null);
        }
        Pg db = Pg.migrated();
        DataSource app = db.appUser(DML);
        IdentitySchema.verify(app);
        new PostgresEntityStore(app).saveCustomer(TENANT, "Test Customer");
        PostgresLoginConnectionStore connections = new PostgresLoginConnectionStore(app);
        connections.insert(connection(TENANT, "PRODUCT_A", AUD), ChangeSource.SETUP, "setup");
        return new World(new PostgresGroupGrantStore(app), connections, new PostgresGrantHistory(app),
                new PostgresIdentityLookup(app, "PRODUCT_A"), db);
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "postgres"})
    void storeAndHistoryWithScopePlatform(String kind) {
        World w = world(kind);
        GrantAuthor setup = GrantAuthor.setup("setup", "run-1");
        assertEquals(Set.of(), w.store().platformGrants(TENANT, "PRODUCT_A", ISSUER));
        assertEquals(1, w.store().changePlatform(TENANT, "PRODUCT_A", ISSUER, List.of(new Grant("ops-team",
                "PLATFORM_OPS")), List.of(), setup));
        assertEquals(0, w.store().changePlatform(TENANT, "PRODUCT_A", ISSUER, List.of(new Grant("ops-team",
                "PLATFORM_OPS")), List.of(), setup), "already there");
        assertEquals(Set.of(new Grant("ops-team", "PLATFORM_OPS")), w.store().platformGrants(TENANT, "PRODUCT_A",
                ISSUER));
        assertEquals(Set.of(), w.store().platformGrants(TENANT, "PRODUCT_B", ISSUER), "per product");
        assertEquals(1, w.store().changePlatform(TENANT, "PRODUCT_A", ISSUER, List.of(),
                List.of(new Grant("ops-team", "PLATFORM_OPS")), GrantAuthor.app("it-admin")));
        assertThrows(IllegalStateException.class, () -> w.store().changePlatform("TEN-NOT-STORED", "PRODUCT_A",
                ISSUER, List.of(new Grant("g", "PLATFORM_OPS")), List.of(), setup));

        List<PlatformGrantChange> h = w.history().forPlatform(TENANT, "PRODUCT_A", FROM, TO);
        assertEquals(List.of(GrantChange.Kind.ADDED, GrantChange.Kind.REMOVED), h.stream()
                .map(PlatformGrantChange::change).toList());
        assertEquals("run-1", h.get(0).setupRunId());
        assertEquals(ChangeSource.SETUP, h.get(0).source());
        assertEquals("it-admin", h.get(1).changedBy());
        assertNull(h.get(1).roleAfter());
        assertEquals(List.of(), w.history().forPlatform(TENANT, "PRODUCT_B", FROM, TO));
        if (w.db() != null) {
            assertEquals(List.of("PLATFORM ADDED", "PLATFORM REMOVED"), w.db().strings("select scope || ' ' || change "
                    + "from idn_grant_history where slot_id is null and entity_uuid is null order by history_id"));
            // The table keeps the rule: an ENTITY row has its entity, a PLATFORM row has none; rows never change.
            assertThrows(IllegalStateException.class, () -> w.db().exec("insert into idn_grant_history (tenant_id, "
                    + "product_code, scope, issuer_uri, external_group, role_after, change, change_source, changed_by) "
                    + "values ('" + TENANT + "', 'PRODUCT_A', 'ENTITY', 'x', 'g', 'R', 'ADDED', 'APP', 'a')"));
            assertThrows(IllegalStateException.class, () -> w.db().exec("update idn_grant_history set changed_by = 'x'"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "postgres"})
    void platformRolesFromNoGrantOneGrantAndAConnectionThatIsNotActive(String kind) {
        World w = world(kind);
        PlatformGroupAuthorization auth = new PlatformGroupAuthorization(ROLES, w.lookup());
        assertEquals(Set.of(), auth.platformRoles(KEY, List.of("ops-team")), "no grant");

        w.store().changePlatform(TENANT, "PRODUCT_A", ISSUER, List.of(new Grant("ops-team", "PLATFORM_OPS")),
                List.of(), GrantAuthor.setup("setup", "run-1"));
        assertEquals(Set.of("PLATFORM_OPS"), auth.platformRoles(KEY, List.of("readers", "ops-team")), "one grant");
        assertEquals(Set.of(), auth.platformRoles(KEY, List.of("readers")));
        assertEquals(Set.of(), auth.platformRoles(KEY, List.of("OPS-TEAM")), "groups exactly as sent");
        assertEquals(Set.of(), auth.platformRoles(new ConnectionKey("okta", ISSUER, "api://product-b"),
                List.of("ops-team")), "another audience");
        assertThrows(IdentityAdmissionException.class, () -> auth.platformRoles(KEY, "ops-team"),
                "the groups claim must be a list");

        LoginConnection c = w.connections().active(TENANT, "PRODUCT_A").orElseThrow();
        w.connections().update(new LoginConnection(c.connectionId(), c.tenantId(), c.productCode(), c.providerId(),
                c.providerType(), c.issuer(), c.browserClientId(), c.apiAudience(), c.secretReference(),
                IdentityConnectionStatus.DISABLED, c.configurationVersion()), c.configurationVersion(),
                ChangeSource.APP, "it-admin").orElseThrow();
        assertEquals(Set.of(), auth.platformRoles(KEY, List.of("ops-team")), "the connection is not ACTIVE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "postgres"})
    void aPlatformGrantNamingAnEntityRoleIsRefused(String kind) {
        World w = world(kind);
        w.store().changePlatform(TENANT, "PRODUCT_A", ISSUER, List.of(new Grant("readers", "READER")), List.of(),
                GrantAuthor.app("it-admin"));
        IdentityAdmissionException e = assertThrows(IdentityAdmissionException.class,
                () -> new PlatformGroupAuthorization(ROLES, w.lookup()).platformRoles(KEY, List.of("readers")));
        assertEquals(IdentityAdmissionException.Reason.ENTITY_ROLE_IN_PLATFORM_GRANT, e.reason());
    }

    @Test
    void entityGrantsStillRefusePlatformRoles() {
        EntityId entity = EntityId.of(java.util.UUID.randomUUID());
        IdentityAdmissionException e = assertThrows(IdentityAdmissionException.class,
                () -> new EntityGroupAuthorization(ROLES).authorizedRoles(entity, List.of("ops-team"),
                        Map.of(entity, Map.of("ops-team", Set.of("PLATFORM_OPS")))));
        assertEquals(IdentityAdmissionException.Reason.PLATFORM_ROLE_IN_ENTITY_GRANT, e.reason());
    }

    @Test
    void anUnreadableStoreRefuses() {
        Pg db = Pg.migrated();
        DataSource app = db.appUser(DML);
        PostgresIdentityLookup lookup = new PostgresIdentityLookup(app, "PRODUCT_A");
        db.exec("drop table idn_platform_grant");
        assertNull(lookup.activePlatformGrants(KEY));
        assertThrows(IdentityAdmissionException.class,
                () -> new PlatformGroupAuthorization(ROLES, lookup).platformRoles(KEY, List.of("ops-team")));
    }
}
