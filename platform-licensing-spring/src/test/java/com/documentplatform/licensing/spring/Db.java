package com.documentplatform.licensing.spring;

import com.documentplatform.licensing.jdbc.LicensingSchema;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** One Postgres container for the run; a schema per test; the schema step as owner; a DML-only app user. */
final class Db {

    private static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer("postgres:16-alpine");
    private static final AtomicInteger N = new AtomicInteger();

    static {
        CONTAINER.start();
        Runtime.getRuntime().addShutdownHook(new Thread(CONTAINER::stop));
    }

    final String schema = "ls" + N.incrementAndGet();
    final PGSimpleDataSource owner;

    private Db() {
        exec(admin(null), "create schema " + schema);
        owner = admin(schema);
    }

    static Db empty() {
        return new Db();
    }

    static Db migrated() {
        Db db = new Db();
        for (String script : LicensingSchema.scripts()) {
            db.exec(LicensingSchema.script(script));
        }
        return db;
    }

    void exec(String... sql) {
        exec(owner, sql);
    }

    /** A login with only SELECT, INSERT, UPDATE, DELETE on the lic_ tables and SELECT on the version table. */
    DataSource dmlUser() {
        String user = schema + "_app";
        List<String> sql = new ArrayList<>(List.of("create role " + user + " login password 'pw'",
                "grant usage on schema " + schema + " to " + user));
        for (String table : LicensingSchema.TABLES) {
            sql.add("grant select, insert, update, delete on " + schema + "." + table + " to " + user);
        }
        sql.add("grant select on " + schema + "." + LicensingSchema.VERSION_TABLE + " to " + user);
        exec(admin(null), sql.toArray(String[]::new));
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(CONTAINER.getJdbcUrl());
        ds.setUser(user);
        ds.setPassword("pw");
        ds.setCurrentSchema(schema);
        return ds;
    }

    List<String> tables() {
        try (Connection c = owner.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select table_name from information_schema.tables "
                     + "where table_schema = current_schema() order by 1")) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    String text(String sql) {
        try (Connection c = owner.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void exec(DataSource ds, String... sql) {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            for (String q : sql) {
                s.execute(q);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
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
