package com.documentplatform.identity.jdbc;

import com.documentplatform.identity.IdentityConnectionStatus;
import com.documentplatform.identity.IdentityProviderType;
import com.documentplatform.identity.mapping.Grant;
import com.documentplatform.identity.store.ChangeSource;
import com.documentplatform.identity.store.LoginConnection;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.documentplatform.identity.jdbc.IdentitySchemaException.Problem.*;
import static org.junit.jupiter.api.Assertions.*;

/** The idn_ tables exist only through the schema step; startup only checks them. */
class IdentitySchemaTest {

    private static final String DML = "select, insert, update, delete";

    @Test
    void theTablesComeOnlyFromTheSchemaRun() {
        Pg empty = Pg.empty();
        IdentitySchemaException e = assertThrows(IdentitySchemaException.class, () -> IdentitySchema.verify(empty.owner));
        assertEquals(IDN_TABLES_MISSING, e.problem());
        assertEquals("Identity tables missing (expected version 5): none of the idn_ tables exist in the database "
                + "schema the app connects to; run the identity schema step (Flyway location db/platform/identity, "
                + "history table idn_schema_history) before starting the app.", e.getMessage());
        assertEquals(List.of(), empty.tables(), "the startup check created nothing");

        Pg migrated = Pg.migrated();
        assertEquals(List.of("idn_customer", "idn_entity", "idn_grant_history", "idn_group_grant", "idn_login_connection",
                "idn_login_connection_history", "idn_machine_binding", "idn_machine_binding_history", "idn_platform_grant",
                "idn_schema_version"),
                migrated.tables());
        assertEquals(5, migrated.count("select max(version) from idn_schema_version"));
        assertEquals(0, migrated.count("select count(*) from idn_customer"), "the schema run inserts no customer data");
        assertEquals("db/platform/identity", IdentitySchema.LOCATION);
        assertEquals("idn_schema_history", IdentitySchema.HISTORY_TABLE);
    }

    @Test
    void startupRefusalCodes() {
        Pg older = Pg.migrated();
        older.exec("update idn_schema_version set version = 0");
        assertEquals(IDN_VERSION_OLDER, problem(older.owner));

        Pg newer = Pg.migrated();
        newer.exec("update idn_schema_version set version = 6");
        IdentitySchemaException n = assertThrows(IdentitySchemaException.class, () -> IdentitySchema.verify(newer.owner));
        assertEquals(IDN_VERSION_NEWER, n.problem());
        assertTrue(n.getMessage().startsWith("Identity tables at version 6, newer than this app expects (version 5)"));

        Pg noVersion = Pg.migrated();
        noVersion.exec("drop table idn_schema_version");
        assertEquals(IDN_VERSION_UNKNOWN, problem(noVersion.owner));

        Pg emptyVersion = Pg.migrated();
        emptyVersion.exec("delete from idn_schema_version");
        assertEquals(IDN_VERSION_UNKNOWN, problem(emptyVersion.owner));

        Pg incomplete = Pg.migrated();
        incomplete.exec("drop table idn_group_grant");
        IdentitySchemaException i = assertThrows(IdentitySchemaException.class, () -> IdentitySchema.verify(incomplete.owner));
        assertEquals(IDN_TABLES_INCOMPLETE, i.problem());
        assertEquals("Identity tables incomplete at version 5, missing: idn_group_grant; run the identity schema step "
                + "(Flyway location db/platform/identity, history table idn_schema_history) before starting the app.",
                i.getMessage());

        Pg noDelete = Pg.migrated();
        IdentitySchemaException rights = assertThrows(IdentitySchemaException.class,
                () -> IdentitySchema.verify(noDelete.appUser("select, insert, update")));
        assertEquals(IDN_PRIVILEGES_MISSING, rights.problem());
        assertTrue(rights.getMessage().startsWith("The app's database user lacks DELETE on idn_customer"),
                rights.getMessage());

        Pg noVersionRead = Pg.migrated();
        noVersionRead.exec("revoke all on idn_schema_version from public");
        DataSource app = noVersionRead.appUser(DML);
        noVersionRead.exec("revoke select on idn_schema_version from " + noVersionRead.schema + "_app");
        assertEquals(IDN_PRIVILEGES_MISSING, problem(app));

        assertDoesNotThrow(() -> IdentitySchema.verify(Pg.migrated().appUser(DML)));
    }

    @Test
    void theDmlOnlyUserRunsEverythingButCannotChangeTables() {
        Pg db = Pg.migrated();
        DataSource app = db.appUser(DML);
        PostgresEntityStore entities = new PostgresEntityStore(app);
        entities.saveCustomer("TEN-TEST-0001", "Test Customer");
        entities.syncSlots("TEN-TEST-0001", List.of("ENT-TEST-0001"));
        assertFalse(entities.activate("ENT-TEST-0001", "Alpha", "actor-1", java.time.Instant.now()).refused());
        PostgresLoginConnectionStore connections = new PostgresLoginConnectionStore(app);
        LoginConnection c = connections.insert(new LoginConnection(UUID.randomUUID(), "TEN-TEST-0001", "PRODUCT_A",
                "okta", IdentityProviderType.OIDC, URI.create("https://login.example.test/oauth2/default"), "web",
                "api://product-a", null, IdentityConnectionStatus.ACTIVE, 1), ChangeSource.SETUP, "setup");
        assertTrue(connections.update(c, 1, ChangeSource.APP, "actor-1").isPresent(),
                "the history table's identity column needs no sequence grant");
        PostgresGroupGrantStore grants = new PostgresGroupGrantStore(app);
        assertEquals(1, grants.add("ENT-TEST-0001", "PRODUCT_A", c.issuer(), Set.of(new Grant("g", "READER"))));
        assertEquals(1, grants.remove("ENT-TEST-0001", "PRODUCT_A", c.issuer(), Set.of(new Grant("g", "READER"))));

        IllegalStateException ddl = assertThrows(IllegalStateException.class, () -> execAs(app, "create table idn_x (id int)"));
        assertTrue(ddl.getCause().getMessage().contains("permission denied"), ddl.getCause().getMessage());
        assertThrows(IllegalStateException.class, () -> execAs(app, "alter table idn_entity add column x int"));
    }

    @Test
    void noColumnHoldsSecretsTokensOrDocumentData() {
        Pg db = Pg.migrated();
        List<String> columns = db.strings("select table_name || '.' || column_name from information_schema.columns "
                + "where table_schema = current_schema() order by 1");
        for (String col : columns) {
            for (String word : List.of("password", "private", "token", "claim", "document", "content", "price")) {
                assertFalse(col.contains(word), col);
            }
            if (col.contains("secret")) {
                assertTrue(col.endsWith(".secret_reference"), "only references to secrets: " + col);
            }
        }
        // A secret value cannot be stored where the reference goes.
        db.exec("insert into idn_customer (tenant_id, name) values ('TEN-1', 'C')");
        assertThrows(IllegalStateException.class, () -> db.exec("insert into idn_login_connection (connection_id, "
                + "tenant_id, product_code, provider_id, provider_type, issuer_uri, api_audience, secret_reference, status, "
                + "configuration_version) values (gen_random_uuid(), 'TEN-1', 'PRODUCT_A', 'p', 'OIDC', 'https://x.test', "
                + "'aud', 's3cr3t value', 'ACTIVE', 1)"));
    }

    private static void execAs(DataSource ds, String sql) {
        try (var c = ds.getConnection(); var s = c.createStatement()) {
            s.execute(sql);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static IdentitySchemaException.Problem problem(DataSource ds) {
        return assertThrows(IdentitySchemaException.class, () -> IdentitySchema.verify(ds)).problem();
    }
}
