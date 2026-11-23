package com.documentplatform.eraselog.jdbc;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static com.documentplatform.eraselog.jdbc.EraseLogSchemaException.Problem.*;

/**
 * The erase log's table in the app's database ({@code erl_position}, the applied position): its SQL file and the
 * check the app makes at startup.
 *
 * <p><b>Database rule:</b> the table is created and changed only by the app's schema step, as a separate Flyway run
 * with location {@link #LOCATION} and history table {@link #HISTORY_TABLE}. App startup never creates tables or
 * inserts rows: {@link #verify(DataSource)} only reads, and stops with a specific message if the table is missing, at
 * another version, or not usable by the app's database user, who needs only SELECT, INSERT and UPDATE.
 */
public final class EraseLogSchema {

    public static final String LOCATION = "db/platform/erase-log";
    public static final String HISTORY_TABLE = "erl_schema_history";
    public static final int VERSION = 2;
    public static final String VERSION_TABLE = "erl_schema_version";
    public static final List<String> TABLES = List.of("erl_position");
    public static final List<String> RIGHTS = List.of("SELECT", "INSERT", "UPDATE");

    private static final List<String> SCRIPTS = List.of("V1__erase_log.sql", "V2__erase_log_replay_guard.sql");
    private static final String RUN_STEP = "run the erase log schema step (Flyway location " + LOCATION
            + ", history table " + HISTORY_TABLE + ") before starting the app";

    private EraseLogSchema() {
    }

    public static List<String> scripts() {
        return SCRIPTS;
    }

    public static String script(String name) {
        if (!SCRIPTS.contains(name)) {
            throw new IllegalArgumentException("Unknown migration file");
        }
        try (InputStream in = EraseLogSchema.class.getClassLoader().getResourceAsStream(LOCATION + "/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Migration file missing from the classpath: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Checks, without changing anything, that {@code erl_position} is there at {@link #VERSION} and usable. */
    public static void verify(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            boolean table = exists(c, "erl_position");
            if (!exists(c, VERSION_TABLE)) {
                if (!table) {
                    throw new EraseLogSchemaException(ERL_TABLES_MISSING, "Erase log table missing (expected version "
                            + VERSION + "): erl_position does not exist in the database schema the app connects to; "
                            + RUN_STEP + ".");
                }
                throw new EraseLogSchemaException(ERL_VERSION_UNKNOWN, "Erase log table at an unknown version (table "
                        + VERSION_TABLE + " missing), expected version " + VERSION + ": " + RUN_STEP + ".");
            }
            require(c, VERSION_TABLE, List.of("SELECT"));
            int version;
            try (PreparedStatement ps = c.prepareStatement("select max(version) from " + VERSION_TABLE);
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                version = rs.getInt(1);
                if (rs.wasNull()) {
                    throw new EraseLogSchemaException(ERL_VERSION_UNKNOWN, "Erase log table at an unknown version ("
                            + VERSION_TABLE + " is empty), expected version " + VERSION + ": " + RUN_STEP + ".");
                }
            }
            if (version < VERSION) {
                throw new EraseLogSchemaException(ERL_VERSION_OLDER, "Erase log table at version " + version
                        + ", expected version " + VERSION + ": " + RUN_STEP + ".");
            }
            if (version > VERSION) {
                throw new EraseLogSchemaException(ERL_VERSION_NEWER, "Erase log table at version " + version
                        + ", newer than this app expects (version " + VERSION + "): install the app release that "
                        + "matches the database, or restore the database.");
            }
            if (!table) {
                throw new EraseLogSchemaException(ERL_TABLES_INCOMPLETE, "Erase log table erl_position missing at "
                        + "version " + version + "; " + RUN_STEP + ".");
            }
            require(c, "erl_position", RIGHTS);
        } catch (SQLException e) {
            throw new EraseLogSchemaException(ERL_CHECK_FAILED, "Erase log table could not be checked: the database "
                    + "refused the check (SQL state " + e.getSQLState() + ").");
        }
    }

    private static boolean exists(Connection c, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select to_regclass(?) is not null")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private static void require(Connection c, String table, List<String> rights) throws SQLException {
        List<String> lacking = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("select has_table_privilege(to_regclass(?), ?)")) {
            for (String right : rights) {
                ps.setString(1, table);
                ps.setString(2, right);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (!rs.getBoolean(1)) {
                        lacking.add(right);
                    }
                }
            }
        }
        if (!lacking.isEmpty()) {
            throw new EraseLogSchemaException(ERL_PRIVILEGES_MISSING, "The app's database user lacks "
                    + String.join(", ", lacking) + " on " + table + ": grant SELECT, INSERT and UPDATE on erl_position "
                    + "(and SELECT on " + VERSION_TABLE + ") to that user.");
        }
    }
}
