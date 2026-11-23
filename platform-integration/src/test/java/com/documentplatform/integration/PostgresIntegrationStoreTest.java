package com.documentplatform.integration;

import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.jdbc.IntegrationSchema;
import com.documentplatform.integration.jdbc.IntegrationSchemaException;
import com.documentplatform.integration.jdbc.PostgresIntegrationStore;
import com.documentplatform.integration.safety.Installation;
import com.documentplatform.integration.store.ConfigChange;
import com.documentplatform.integration.store.IntegrationAudit;
import com.documentplatform.integration.store.IntegrationConfigs;
import com.documentplatform.integration.store.IntegrationStore;
import com.documentplatform.integration.store.StoredIntegration;
import com.documentplatform.integration.result.AttemptResult;
import com.documentplatform.notifications.secret.SecretRef;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The Postgres store with a DML-only runtime user: versions, history, the startup check. */
class PostgresIntegrationStoreTest {

    private static final class Audit implements IntegrationAudit {
        final List<ConfigChange> changes = new ArrayList<>();

        @Override
        public void changed(ConfigChange change) {
            changes.add(change);
        }

        @Override
        public void tested(AttemptResult result, String by) {
        }
    }

    @Test
    void versionsAndHistoryWithTheDmlOnlyUser() {
        Pg db = Pg.migrated();
        DataSource app = db.appUser();
        IntegrationSchema.verify(app);
        Audit audit = new Audit();
        IntegrationConfigs configs = new IntegrationConfigs(new PostgresIntegrationStore(app), audit,
                Installation.onPremises().withSignInNone(true), Clock.systemUTC());

        IntegrationConfig c = JsonAndConfigTest.sample(new SignIn.BearerFixed(SecretRef.of("int/t1")));
        StoredIntegration v1 = configs.create(c, "alice");
        assertEquals(1, v1.version());
        StoredIntegration v2 = configs.update(c.withSignIn(new SignIn.None()).withName("Renamed"), 1, "bob");
        assertEquals(2, v2.version());
        assertEquals(3, configs.setEnabled(c.id(), false, 2, "carol").version());

        StoredIntegration current = configs.current(c.id()).orElseThrow();
        assertEquals(3, current.version());
        assertFalse(current.config().enabled());
        assertEquals("carol", current.changedBy());
        assertEquals(c, configs.version(c.id(), 1).orElseThrow().config(), "an older version reads back as saved");
        assertTrue(configs.version(c.id(), 9).isEmpty());

        List<ConfigChange> history = configs.history(c.id());
        assertEquals(List.of(1, 2, 3), history.stream().map(ConfigChange::version).toList());
        assertEquals(List.of("alice", "bob", "carol"), history.stream().map(ConfigChange::changedBy).toList());
        assertEquals(List.of("name", "signIn.token", "signIn.type"), history.get(1).changedFields());
        assertEquals(List.of(IntegrationCode.INT_CONFIG_SIGN_IN_NONE_WARNING), history.get(1).warnings());
        assertEquals(c.entity(), history.get(0).entity());
        assertEquals(history, audit.changes);
        assertEquals(1, configs.list(c.entity(), "DEMO").size());
        assertTrue(configs.list(c.entity(), "OTHER").isEmpty());

        IntegrationStore.Conflict stale = assertThrows(IntegrationStore.Conflict.class,
                () -> configs.update(c.withName("Late"), 2, "dave"));
        assertEquals(IntegrationCode.INT_CONFIG_VERSION_CONFLICT, stale.code());
        assertEquals(IntegrationCode.INT_CONFIG_EXISTS,
                assertThrows(IntegrationStore.Conflict.class, () -> configs.create(c, "eve")).code());
    }

    @Test
    void intakeConfigurationsAreStoredWithTheDmlOnlyUser() {
        // P027: API polling and the watched folder are stored and versioned like every kind; nothing else is stored
        // (the cursor state is the app's, drops and claims live in the folder).
        Pg db = Pg.migrated();
        DataSource app = db.appUser();
        IntegrationSchema.verify(app);
        IntegrationConfigs configs = new IntegrationConfigs(new PostgresIntegrationStore(app), IntegrationAudit.NONE,
                Installation.onPremises(), Clock.systemUTC());
        IntegrationConfig poll = IntakeConfigTest.poll();
        IntegrationConfig watch = IntakeConfigTest.watch();
        configs.create(poll, "alice");
        configs.create(watch, "alice");
        assertEquals(poll, configs.current(poll.id()).orElseThrow().config());
        assertEquals(watch, configs.current(watch.id()).orElseThrow().config());
        IntegrationConfig changed = poll.withPoll(poll.poll().withPaging(
                com.documentplatform.integration.config.PollSource.Paging.nextLink("/links/next")));
        configs.update(changed, 1, "bob");
        assertEquals(List.of("poll.paging.mode", "poll.paging.param", "poll.paging.pageSize", "poll.paging.pointer",
                "poll.paging.sizeParam").stream().sorted().toList(),
                configs.history(poll.id()).get(1).changedFields().stream().filter(f -> f.startsWith("poll."))
                        .sorted().toList());
        assertEquals(changed, configs.current(poll.id()).orElseThrow().config());
    }

    @Test
    void theDatabaseKeepsVersionsFrozenAndTheAppUserCannotChangeTables() {
        Pg db = Pg.migrated();
        DataSource app = db.appUser();
        IntegrationConfigs configs = new IntegrationConfigs(new PostgresIntegrationStore(app), IntegrationAudit.NONE,
                Installation.onPremises(), Clock.systemUTC());
        IntegrationConfig c = JsonAndConfigTest.sample(new SignIn.BearerFixed(SecretRef.of("int/t1")));
        configs.create(c, "alice");
        // Even the owner cannot change or delete a stored version, skip a version or delete an integration.
        assertThrows(IllegalStateException.class, () -> db.exec("update int_integration_version set changed_by = 'x'"));
        assertThrows(IllegalStateException.class, () -> db.exec("delete from int_integration_version"));
        assertThrows(IllegalStateException.class, () -> db.exec("delete from int_integration"));
        assertThrows(IllegalStateException.class, () -> db.exec("update int_integration set version = 5"));
        // The runtime user has no DDL and no DELETE.
        assertThrows(IllegalStateException.class, () -> Pg.exec(app, "create table int_extra (x int)"));
        assertThrows(IllegalStateException.class, () -> Pg.exec(app, "drop table int_integration_version"));
        assertThrows(IllegalStateException.class, () -> Pg.exec(app, "delete from int_integration_version"));
    }

    @Test
    void theStartupCheckOnlyReadsAndSaysWhatIsWrong() {
        IntegrationSchemaException missing = assertThrows(IntegrationSchemaException.class,
                () -> IntegrationSchema.verify(Pg.empty().owner));
        assertEquals(IntegrationSchemaException.Problem.INT_TABLES_MISSING, missing.problem());
        assertTrue(missing.getMessage().contains(IntegrationSchema.HISTORY_TABLE));

        Pg older = Pg.migrated();
        older.exec("delete from int_schema_version", "insert into int_schema_version (version) values (0)");
        assertEquals(IntegrationSchemaException.Problem.INT_VERSION_OLDER, assertThrows(
                IntegrationSchemaException.class, () -> IntegrationSchema.verify(older.owner)).problem());
        Pg newer = Pg.migrated();
        newer.exec("insert into int_schema_version (version) values (" + (IntegrationSchema.VERSION + 1) + ")");
        assertEquals(IntegrationSchemaException.Problem.INT_VERSION_NEWER, assertThrows(
                IntegrationSchemaException.class, () -> IntegrationSchema.verify(newer.owner)).problem());

        Pg lacking = Pg.migrated();
        DataSource readOnly = lacking.appUser("select", "select");
        IntegrationSchemaException rights = assertThrows(IntegrationSchemaException.class,
                () -> IntegrationSchema.verify(readOnly));
        assertEquals(IntegrationSchemaException.Problem.INT_PRIVILEGES_MISSING, rights.problem());
        assertTrue(rights.getMessage().contains("INSERT"));

        Pg ok = Pg.migrated();
        IntegrationSchema.verify(ok.owner);
        IntegrationSchema.verify(ok.owner); // twice: nothing was changed
    }
}
