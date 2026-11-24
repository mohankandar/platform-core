package com.documentplatform.notifications.jdbc;

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
import java.util.Map;
import java.util.TreeMap;

import static com.documentplatform.notifications.jdbc.NotificationSchemaException.Problem.CHECK_FAILED;
import static com.documentplatform.notifications.jdbc.NotificationSchemaException.Problem.PRIVILEGES_MISSING;
import static com.documentplatform.notifications.jdbc.NotificationSchemaException.Problem.TABLES_INCOMPLETE;
import static com.documentplatform.notifications.jdbc.NotificationSchemaException.Problem.TABLES_MISSING;
import static com.documentplatform.notifications.jdbc.NotificationSchemaException.Problem.VERSION_NEWER;
import static com.documentplatform.notifications.jdbc.NotificationSchemaException.Problem.VERSION_OLDER;
import static com.documentplatform.notifications.jdbc.NotificationSchemaException.Problem.VERSION_UNKNOWN;

/**
 * The notification tables (all prefixed {@code notif_}): their SQL files, and the check the app makes at startup.
 *
 * <p><b>Database rule:</b> the tables are created and changed only by the app's separate schema step, which the
 * customer runs. With Flyway, add the location {@link #LOCATION}; without, run {@link #scripts()} in order with
 * {@link #script(String)}. App startup never creates tables or inserts rows: it calls {@link #verify(DataSource)},
 * which only reads, and stops with a specific message if the tables are missing, at another version, or not usable by
 * the app's database user. At runtime that user needs only SELECT, INSERT, UPDATE and DELETE on the tables.
 */
public final class NotificationSchema {

    /** Classpath location of the migration files. */
    public static final String LOCATION = "db/platform/notifications";

    /** The table version this code needs: the number of the last migration file. */
    public static final int VERSION = 1;

    /** The tables the code reads and writes at runtime. */
    public static final List<String> TABLES = List.of("notif_tenant", "notif_destination", "notif_event_settings",
            "notif_outbox", "notif_summary_item", "notif_condition", "notif_approval_request");

    /** Holds the version of the files that were run. */
    public static final String VERSION_TABLE = "notif_schema_version";

    private static final List<String> SCRIPTS = List.of("V1__notifications.sql");
    private static final List<String> RIGHTS = List.of("SELECT", "INSERT", "UPDATE", "DELETE");
    private static final String RUN_STEP = "run the schema step (" + LOCATION + ") before starting the app";

    private NotificationSchema() {
    }

    /** File names, in the order they must run. */
    public static List<String> scripts() {
        return SCRIPTS;
    }

    /** The SQL of one migration file. */
    public static String script(String name) {
        if (!SCRIPTS.contains(name)) {
            throw new IllegalArgumentException("Unknown migration file");
        }
        try (InputStream in = NotificationSchema.class.getClassLoader().getResourceAsStream(LOCATION + "/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Migration file missing from the classpath: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Checks, without changing anything, that the notification tables are there at {@link #VERSION} in the schema the
     * connection uses (its search path), and that the connection's user may read and write them.
     *
     * @throws NotificationSchemaException naming exactly what is wrong and what to do
     */
    public static void verify(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            List<String> missing = new ArrayList<>();
            for (String table : TABLES) {
                if (!exists(c, table)) {
                    missing.add(table);
                }
            }
            if (!exists(c, VERSION_TABLE)) {
                if (missing.size() == TABLES.size()) {
                    throw new NotificationSchemaException(TABLES_MISSING, "Notifications tables missing (expected "
                            + "version " + VERSION + "): none of the notif_ tables exist in the database schema the "
                            + "app connects to; " + RUN_STEP + ".");
                }
                throw new NotificationSchemaException(VERSION_UNKNOWN, "Notifications tables at an unknown version "
                        + "(table " + VERSION_TABLE + " missing), expected version " + VERSION + ": " + RUN_STEP + ".");
            }
            requireRights(c, VERSION_TABLE, List.of("SELECT"));
            int version = version(c);
            if (version < VERSION) {
                throw new NotificationSchemaException(VERSION_OLDER, "Notifications tables at version " + version
                        + ", expected version " + VERSION + ": " + RUN_STEP + ".");
            }
            if (version > VERSION) {
                throw new NotificationSchemaException(VERSION_NEWER, "Notifications tables at version " + version
                        + ", newer than this app expects (version " + VERSION + "): install the app release that "
                        + "matches the database, or restore the database.");
            }
            if (!missing.isEmpty()) {
                throw new NotificationSchemaException(TABLES_INCOMPLETE, "Notifications tables incomplete at version "
                        + version + ", missing: " + String.join(", ", missing) + "; " + RUN_STEP + ".");
            }
            for (String table : TABLES) {
                requireRights(c, table, RIGHTS);
            }
        } catch (SQLException e) {
            throw new NotificationSchemaException(CHECK_FAILED, "Notifications tables could not be checked: the "
                    + "database could not be reached or refused the check (SQL state " + e.getSQLState() + ").", e);
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

    private static int version(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select max(version) from " + VERSION_TABLE);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            int v = rs.getInt(1);
            if (rs.wasNull()) {
                throw new NotificationSchemaException(VERSION_UNKNOWN, "Notifications tables at an unknown version ("
                        + VERSION_TABLE + " is empty), expected version " + VERSION + ": " + RUN_STEP + ".");
            }
            return v;
        }
    }

    private static void requireRights(Connection c, String table, List<String> rights) throws SQLException {
        Map<String, Boolean> held = new TreeMap<>();
        try (PreparedStatement ps = c.prepareStatement("select has_table_privilege(to_regclass(?), ?)")) {
            for (String right : rights) {
                ps.setString(1, table);
                ps.setString(2, right);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    held.put(right, rs.getBoolean(1));
                }
            }
        }
        List<String> lacking = rights.stream().filter(r -> !held.get(r)).toList();
        if (!lacking.isEmpty()) {
            throw new NotificationSchemaException(PRIVILEGES_MISSING, "The app's database user lacks "
                    + String.join(", ", lacking) + " on " + table + ": grant SELECT, INSERT, UPDATE and DELETE on the "
                    + "notif_ tables (and SELECT on " + VERSION_TABLE + ") to that user.");
        }
    }
}
