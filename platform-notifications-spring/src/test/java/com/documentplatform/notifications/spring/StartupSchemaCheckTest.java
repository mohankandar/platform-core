package com.documentplatform.notifications.spring;

import com.documentplatform.notifications.DeliveryReport;
import com.documentplatform.notifications.DeliveryStatus;
import com.documentplatform.notifications.EventSettings;
import com.documentplatform.notifications.NotificationEvent;
import com.documentplatform.notifications.NotificationSettings;
import com.documentplatform.notifications.Notifier;
import com.documentplatform.notifications.jdbc.NotificationSchemaException;
import com.documentplatform.notifications.jdbc.NotificationSchemaException.Problem;
import com.icegreen.greenmail.util.GreenMail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.documentplatform.notifications.spring.TestApp.TENANT;
import static com.documentplatform.notifications.spring.TestApp.approval;
import static com.documentplatform.notifications.spring.TestApp.await;
import static com.documentplatform.notifications.spring.TestApp.mailProperties;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Database rule: startup only checks the notif_ tables; it never creates or changes them. */
class StartupSchemaCheckTest {

    private final GreenMail mail = TestApp.startMail();

    @AfterEach
    void stop() {
        mail.stop();
    }

    private ApplicationContextRunner app(DataSource ds) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(NotificationsAutoConfiguration.class))
                .withUserConfiguration(TestApp.Beans.class)
                .withBean(DataSource.class, () -> ds)
                .withPropertyValues(mailProperties(mail.getSmtp().getPort()));
    }

    private NotificationSchemaException refused(DataSource ds) {
        NotificationSchemaException[] e = new NotificationSchemaException[1];
        app(ds).run(ctx -> e[0] = (NotificationSchemaException) WiringTest.rootOf(ctx.getStartupFailure(),
                NotificationSchemaException.class));
        return e[0];
    }

    @Test
    void refusesToStartWhenTheSchemaStepWasNeverRunAndCreatesNothing() {
        Db db = Db.empty();
        NotificationSchemaException e = refused(db.owner);
        assertEquals(Problem.TABLES_MISSING, e.problem());
        assertEquals("Notifications tables missing (expected version 1): none of the notif_ tables exist in the "
                + "database schema the app connects to; run the schema step (db/platform/notifications) before "
                + "starting the app.", e.getMessage());
        assertEquals(List.of(), db.tables(), "startup created nothing, even as the schema owner");
    }

    @Test
    void refusesOlderTables() {
        Db db = Db.migrated();
        db.exec("update notif_schema_version set version = 0");
        NotificationSchemaException e = refused(db.owner);
        assertEquals(Problem.VERSION_OLDER, e.problem());
        assertEquals("Notifications tables at version 0, expected version 1: run the schema step "
                + "(db/platform/notifications) before starting the app.", e.getMessage());
        assertEquals(0, db.count("select max(version) from notif_schema_version"), "the version was left alone");
    }

    @Test
    void refusesNewerTables() {
        Db db = Db.migrated();
        db.exec("update notif_schema_version set version = 2");
        NotificationSchemaException e = refused(db.owner);
        assertEquals(Problem.VERSION_NEWER, e.problem());
        assertTrue(e.getMessage().startsWith("Notifications tables at version 2, newer than this app expects "
                + "(version 1)"), e.getMessage());
    }

    @Test
    void refusesTablesWithoutAVersion() {
        Db db = Db.migrated();
        db.exec("drop table notif_schema_version");
        NotificationSchemaException e = refused(db.owner);
        assertEquals(Problem.VERSION_UNKNOWN, e.problem());
        assertTrue(e.getMessage().contains("table notif_schema_version missing"), e.getMessage());
    }

    @Test
    void refusesIncompleteTables() {
        Db db = Db.migrated();
        db.exec("drop table notif_condition", "drop table notif_summary_item");
        NotificationSchemaException e = refused(db.owner);
        assertEquals(Problem.TABLES_INCOMPLETE, e.problem());
        assertEquals("Notifications tables incomplete at version 1, missing: notif_summary_item, notif_condition; "
                + "run the schema step (db/platform/notifications) before starting the app.", e.getMessage());
    }

    @Test
    void refusesAUserWhoCannotDelete() {
        Db db = Db.migrated();
        NotificationSchemaException e = refused(db.appUser("select, insert, update"));
        assertEquals(Problem.PRIVILEGES_MISSING, e.problem());
        assertTrue(e.getMessage().startsWith("The app's database user lacks DELETE on notif_tenant: grant SELECT, "
                + "INSERT, UPDATE and DELETE"), e.getMessage());
    }

    @Test
    void runsWithOnlySelectInsertUpdateDeleteRights() {
        Db db = Db.migrated();
        DataSource dmlOnly = db.appUser("select, insert, update, delete");
        app(dmlOnly).withPropertyValues("platform.notifications.retention=0s").run(ctx -> {
            assertNull(ctx.getStartupFailure());
            NotificationSettings settings = ctx.getBean(NotificationSettings.class);
            Notifier n = ctx.getBean(Notifier.class);
            settings.connectChannels(TENANT, TestApp.tenant());
            settings.setTechnicalEvent(TENANT, TestApp.REJECTED,
                    EventSettings.defaultsFor(settings.catalogue().require(TestApp.REJECTED)));
            assertEquals(DeliveryStatus.PENDING, n.publish(approval("LN 1")).get(0).status());
            await(Duration.ofSeconds(10), "the e-mail", () -> mail.getReceivedMessages().length == 1);

            // A summary item (identity column), a condition, then the summary, the alert and the purge of finished
            // messages: everything the app does at runtime, as this user.
            assertEquals(DeliveryStatus.QUEUED_FOR_SUMMARY, n.publish(NotificationEvent.builder(TestApp.REJECTED,
                    TENANT).count("files", 3).build()).get(0).status());
            n.conditionFailing(NotificationEvent.builder(TestApp.STORE_DOWN, TENANT).build());
            Instant later = Instant.now().plus(Duration.ofHours(2));
            List<DeliveryReport> reports = n.tick(later);
            assertEquals(List.of(DeliveryStatus.SENT, DeliveryStatus.SENT),
                    reports.stream().map(DeliveryReport::status).toList(), "the alert and the summary");
            n.tick(later.plusSeconds(1));
            assertEquals(0, db.count("select count(*) from notif_outbox"), "purged with DELETE only");
            assertEquals(0, db.count("select count(*) from notif_summary_item"));
        });
    }

    @Test
    void theStartupReportSaysWhatToDo() {
        Db db = Db.empty();
        FailureAnalysis[] analysis = new FailureAnalysis[1];
        app(db.owner).run(ctx -> analysis[0] = new NotificationsFailureAnalyzer().analyze(ctx.getStartupFailure()));
        assertTrue(analysis[0].getDescription().startsWith("Notifications tables missing"));
        assertTrue(analysis[0].getAction().startsWith("Run the app's database schema step"));
    }

    /** xc-schemas-3 (P037): the runtime user has data rights only; every DDL statement is refused by the database. */
    @Test
    void theRuntimeUserCannotRunDdl() throws Exception {
        javax.sql.DataSource app = Db.migrated().appUser("select, insert, update, delete");
        String table = com.documentplatform.notifications.jdbc.NotificationSchema.TABLES.get(0);
        for (String sql : java.util.List.of("create table notif_extra (x int)", "alter table " + table + " add column note text",
                "drop table " + table)) {
            try (java.sql.Connection c = app.getConnection(); java.sql.Statement s = c.createStatement()) {
                java.sql.SQLException e = org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class,
                        () -> s.execute(sql), sql);
                org.junit.jupiter.api.Assertions.assertEquals("42501", e.getSQLState(), sql + ": " + e.getMessage());
            }
        }
    }
}
