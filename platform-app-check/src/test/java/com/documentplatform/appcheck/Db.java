package com.documentplatform.appcheck;

import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/** One Postgres for the run; each test its own schema, migrated by Flyway as an app does it. */
final class Db {

    /** Location → history table, as the install guides say. */
    static final Map<String, String> RUNS = new TreeMap<>(Map.of(
            "db/platform/identity", "idn_schema_history", "db/platform/licensing", "lic_schema_history",
            "db/platform/fields", "fld_schema_history", "db/platform/erase-log", "erl_schema_history",
            "db/platform/integration", "int_schema_history", "db/platform/modelgov", "mg_schema_history",
            "db/platform/notifications", "notif_schema_history"));

    private static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine");
    private static final AtomicInteger N = new AtomicInteger();

    static {
        PG.start();
        Runtime.getRuntime().addShutdownHook(new Thread(PG::stop));
    }

    final String schema = "app" + N.incrementAndGet();
    final PGSimpleDataSource owner = dataSource(schema);

    Db() {
        try (Connection c = dataSource(null).getConnection(); Statement s = c.createStatement()) {
            s.execute("create schema " + schema);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    Flyway flyway(String location, String history) {
        return Flyway.configure().dataSource(owner).schemas(schema).defaultSchema(schema)
                .locations("classpath:" + location).table(history).baselineOnMigrate(true).baselineVersion("0").load();
    }

    Db migrated() {
        RUNS.forEach((location, history) -> flyway(location, history).migrate());
        return this;
    }

    String url() {
        return PG.getJdbcUrl() + (PG.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    String user() {
        return PG.getUsername();
    }

    String password() {
        return PG.getPassword();
    }

    private static PGSimpleDataSource dataSource(String schema) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(PG.getJdbcUrl());
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());
        if (schema != null) ds.setCurrentSchema(schema);
        return ds;
    }
}
