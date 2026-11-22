package com.documentplatform.eraselog;

import com.documentplatform.eraselog.jdbc.EraseLogSchema;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One Postgres container for the test run; each test gets its own schema. {@link #migrated()} runs the SQL file as the
 * schema owner (the schema step); {@link #appUser} is the runtime login with only the rights it is granted.
 */
final class Pg {

    private static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer("postgres:16-alpine");
    private static final AtomicInteger N = new AtomicInteger();

    static {
        CONTAINER.start();
        Runtime.getRuntime().addShutdownHook(new Thread(CONTAINER::stop));
    }

    final String schema = "erl" + N.incrementAndGet() + "_" + ProcessHandle.current().pid();
    final PGSimpleDataSource owner;

    private Pg() {
        exec(admin(null), "create schema " + schema);
        owner = admin(schema);
    }

    static Pg empty() {
        return new Pg();
    }

    static Pg migrated() {
        Pg db = new Pg();
        for (String script : EraseLogSchema.scripts()) {
            db.exec(EraseLogSchema.script(script));
        }
        return db;
    }

    void exec(String... sql) {
        exec(owner, sql);
    }

    /** A login with only {@code rights} on erl_position and SELECT on the version table. Its password is "pw". */
    DataSource appUser(String rights) {
        String user = schema + "_app";
        exec(admin(null), "create role " + user + " login password 'pw'",
                "grant usage on schema " + schema + " to " + user,
                "grant " + rights + " on " + schema + ".erl_position to " + user,
                "grant select on " + schema + "." + EraseLogSchema.VERSION_TABLE + " to " + user);
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(CONTAINER.getJdbcUrl());
        ds.setUser(user);
        ds.setPassword("pw");
        ds.setCurrentSchema(schema);
        return ds;
    }

    static void exec(DataSource ds, String... sql) {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            for (String q : sql) {
                s.execute(q);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static PGSimpleDataSource admin(String schema) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(CONTAINER.getJdbcUrl());
        ds.setUser(CONTAINER.getUsername());
        ds.setPassword(CONTAINER.getPassword());
        if (schema != null) {
            ds.setCurrentSchema(schema);
        }
        return ds;
    }
}
