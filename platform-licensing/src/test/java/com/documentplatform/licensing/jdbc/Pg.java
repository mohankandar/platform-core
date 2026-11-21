package com.documentplatform.licensing.jdbc;

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

/**
 * One Postgres container for the test run; each test gets its own schema. {@link #migrated()} runs the SQL files as
 * the schema owner (the schema step); {@link #appUser} is the runtime login with only the rights it is granted.
 */
public final class Pg {

    private static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer("postgres:16-alpine");
    private static final AtomicInteger N = new AtomicInteger();

    static {
        CONTAINER.start();
        Runtime.getRuntime().addShutdownHook(new Thread(CONTAINER::stop));
    }

    public final String schema = "lic" + N.incrementAndGet() + "_" + ProcessHandle.current().pid();
    public final PGSimpleDataSource owner;

    private Pg() {
        exec(admin(null), "create schema " + schema);
        owner = admin(schema);
    }

    public static Pg empty() {
        return new Pg();
    }

    public static Pg migrated() {
        Pg db = new Pg();
        for (String script : LicensingSchema.scripts()) {
            db.exec(LicensingSchema.script(script));
        }
        return db;
    }

    public String jdbcUrl() {
        return CONTAINER.getJdbcUrl() + (CONTAINER.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    public void exec(String... sql) {
        exec(owner, sql);
    }

    /** A login with only {@code rights} on the lic_ tables and SELECT on the version table. Its password is "pw". */
    public DataSource appUser(String rights) {
        String user = appUserName();
        exec(admin(null), "create role " + user + " login password 'pw'", "grant usage on schema " + schema + " to " + user);
        List<String> grants = new ArrayList<>();
        for (String table : LicensingSchema.TABLES) {
            grants.add("grant " + rights + " on " + schema + "." + table + " to " + user);
        }
        grants.add("grant select on " + schema + "." + LicensingSchema.VERSION_TABLE + " to " + user);
        exec(admin(null), grants.toArray(String[]::new));
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(CONTAINER.getJdbcUrl());
        ds.setUser(user);
        ds.setPassword("pw");
        ds.setCurrentSchema(schema);
        return ds;
    }

    public String appUserName() {
        return schema + "_app";
    }

    public List<String> tables() {
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

    public long count(String sql) {
        try (Connection c = owner.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
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
