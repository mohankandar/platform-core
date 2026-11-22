package com.documentplatform.integration.spring;

import com.documentplatform.integration.jdbc.IntegrationSchema;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

/** One Postgres container for the test run; each test gets its own schema, migrated by the owner, used by a DML user. */
final class Pg {

    private static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer("postgres:16-alpine");
    private static final AtomicInteger N = new AtomicInteger();

    static {
        CONTAINER.start();
        Runtime.getRuntime().addShutdownHook(new Thread(CONTAINER::stop));
    }

    final String schema = "ints" + N.incrementAndGet() + "_" + ProcessHandle.current().pid();

    private Pg(boolean migrate) {
        exec(admin(null), "create schema " + schema);
        if (migrate) {
            for (String script : IntegrationSchema.scripts()) exec(admin(schema), IntegrationSchema.script(script));
        }
    }

    static Pg migrated() {
        return new Pg(true);
    }

    static Pg empty() {
        return new Pg(false);
    }

    /** The runtime login with only the DML rights the schema file lists. */
    DataSource appUser() {
        String user = schema + "_app";
        exec(admin(null), "create role " + user + " login password 'pw'",
                "grant usage on schema " + schema + " to " + user);
        if (tables()) {
            exec(admin(null), "grant select, insert, update on " + schema + ".int_integration to " + user,
                    "grant select, insert on " + schema + ".int_integration_version to " + user,
                    "grant select on " + schema + "." + IntegrationSchema.VERSION_TABLE + " to " + user);
        }
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(CONTAINER.getJdbcUrl());
        ds.setUser(user);
        ds.setPassword("pw");
        ds.setCurrentSchema(schema);
        return ds;
    }

    private boolean tables() {
        try (Connection c = admin(schema).getConnection(); Statement s = c.createStatement()) {
            return s.executeQuery("select 1 from information_schema.tables where table_schema = '" + schema
                    + "' and table_name = 'int_integration'").next();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void exec(DataSource ds, String... sql) {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            for (String q : sql) s.execute(q);
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static PGSimpleDataSource admin(String schema) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(CONTAINER.getJdbcUrl());
        ds.setUser(CONTAINER.getUsername());
        ds.setPassword(CONTAINER.getPassword());
        if (schema != null) ds.setCurrentSchema(schema);
        return ds;
    }
}
