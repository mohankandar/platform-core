package com.documentplatform.appcheck;

import com.documentplatform.eraselog.jdbc.EraseLogSchema;
import com.documentplatform.fields.jdbc.FieldSchema;
import com.documentplatform.identity.jdbc.IdentitySchema;
import com.documentplatform.integration.jdbc.IntegrationSchema;
import com.documentplatform.licensing.jdbc.LicensingSchema;
import com.documentplatform.modelgov.jdbc.ModelGovSchema;
import com.documentplatform.notifications.jdbc.NotificationSchema;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** P051 item 5: every platform schema runs through Flyway 12 as its own run, and our own check accepts the result. */
class FlywayRunsTest {

    @Test
    void everySchemaRunsAsItsOwnFlywayRun() {
        Db db = new Db();
        Db.RUNS.forEach((location, history) -> {
            Flyway f = db.flyway(location, history);
            assertTrue(f.migrate().success, location);
            assertTrue(f.info().applied().length > 0, location);
            for (MigrationInfo i : f.info().all()) assertTrue(i.getState().isApplied(), location + " " + i.getScript());
            assertEquals(0, f.migrate().migrationsExecuted, "a second run does nothing: " + location);
            f.validate();
        });
        IdentitySchema.verify(db.owner);
        LicensingSchema.verify(db.owner);
        FieldSchema.verify(db.owner);
        EraseLogSchema.verify(db.owner);
        IntegrationSchema.verify(db.owner);
        ModelGovSchema.verify(db.owner);
        NotificationSchema.verify(db.owner);
        String version = Flyway.class.getPackage().getImplementationVersion();
        assertTrue(Classpath.version("flyway-core").startsWith("12."), version);
    }
}
