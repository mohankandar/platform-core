package com.documentplatform.setup;

import com.documentplatform.identity.jdbc.IdentitySchema;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One Postgres container for the test run; each test gets its own schema. {@link #migrated()} runs the licensing and
 * identity SQL files as the schema owner (the app's schema step). {@link #appEnv} is the setup's environment for the
 * runtime login, which has only SELECT, INSERT, UPDATE and DELETE on the tables.
 */
final class Pg {

    private static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer("postgres:16-alpine");
    private static final AtomicInteger N = new AtomicInteger();

    static {
        CONTAINER.start();
        Runtime.getRuntime().addShutdownHook(new Thread(CONTAINER::stop));
    }

    final String schema = "setup" + N.incrementAndGet() + "_" + ProcessHandle.current().pid();
    final PGSimpleDataSource owner;
    private String appUser;

    private Pg() {
        exec(admin(null), "create schema " + schema);
        owner = admin(schema);
    }

    static Pg empty() {
        return new Pg();
    }

    static Pg migrated() {
        Pg db = new Pg();
        for (String script : LicensingSchema.scripts()) db.exec(LicensingSchema.script(script));
        for (String script : IdentitySchema.scripts()) db.exec(IdentitySchema.script(script));
        return db;
    }

    /** The DML-only app login (created once per schema) and the setup's environment for it. */
    Map<String, String> appEnv(String password) {
        if (appUser == null) {
            appUser = schema + "_app";
            exec(admin(null), "create role " + appUser + " login password '" + password + "'",
                    "grant usage on schema " + schema + " to " + appUser);
            List<String> grants = new ArrayList<>();
            List<String> tables = new ArrayList<>(LicensingSchema.TABLES);
            tables.addAll(IdentitySchema.TABLES);
            for (String table : tables) {
                grants.add("grant select, insert, update, delete on " + schema + "." + table + " to " + appUser);
            }
            grants.add("grant select on " + schema + "." + LicensingSchema.VERSION_TABLE + " to " + appUser);
            grants.add("grant select on " + schema + "." + IdentitySchema.VERSION_TABLE + " to " + appUser);
            exec(admin(null), grants.toArray(String[]::new));
        }
        return Map.of(SetupCommand.DB_URL, url(), SetupCommand.DB_USER, appUser, SetupCommand.DB_PASSWORD, password);
    }

    String url() {
        String base = CONTAINER.getJdbcUrl();
        return base + (base.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    DataSource appDataSource(String password) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(CONTAINER.getJdbcUrl());
        ds.setUser(appUser);
        ds.setPassword(password);
        ds.setCurrentSchema(schema);
        return ds;
    }

    void exec(String... sql) {
        exec(owner, sql);
    }

    List<String> tables() {
        return strings("select table_name from information_schema.tables where table_schema = current_schema() order by 1");
    }

    List<String> strings(String sql) {
        try (Connection c = owner.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            List<String> out = new ArrayList<>();
            while (rs.next()) out.add(rs.getString(1));
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    long count(String sql) {
        try (Connection c = owner.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A connection of the schema owner, for holding locks in tests. */
    Connection ownerConnection() throws SQLException {
        return owner.getConnection();
    }

    private static void exec(DataSource ds, String... sql) {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            for (String q : sql) s.execute(q);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
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
