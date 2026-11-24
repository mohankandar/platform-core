package com.documentplatform.licensing.jdbc;

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

import static com.documentplatform.licensing.jdbc.LicensingSchemaException.Problem.*;

/**
 * The licensing tables (all prefixed {@code lic_}): their SQL files, and the check the app makes at startup.
 *
 * <p><b>Database rule:</b> the tables are created and changed only by the app's schema step, as a separate Flyway run
 * with location {@link #LOCATION} and history table {@link #HISTORY_TABLE}. App startup never creates tables or
 * inserts rows: {@link #verify(DataSource)} only reads, and stops with a specific message if the tables are missing,
 * at another version, or not usable by the app's database user, who needs only SELECT, INSERT, UPDATE and DELETE
 * (fewer on some tables).
 */
public final class LicensingSchema {

    /** Classpath location of the migration files. */
    public static final String LOCATION = "db/platform/licensing";

    /** The Flyway history table of the separate licensing run. */
    public static final String HISTORY_TABLE = "lic_schema_history";

    /** The table version this code needs: the number of the last migration file. */
    public static final int VERSION = 2;

    /** The tables the code reads and writes at runtime. */
    public static final List<String> TABLES = List.of("lic_licence", "lic_last_seen", "lic_clock_reset", "lic_usage",
            "lic_chain_head", "lic_notice_sent", "lic_version_floor");

    /** Holds the version of the files that were run. */
    public static final String VERSION_TABLE = "lic_schema_version";

    private static final List<String> SCRIPTS = List.of("V1__licensing.sql", "V2__licensing_clock_and_versions.sql");
    private static final List<String> RIGHTS = List.of("SELECT", "INSERT", "UPDATE", "DELETE");
    /** Tables the app's user needs fewer rights on (P045, SEC-13). */
    private static final Map<String, List<String>> FEWER_RIGHTS = Map.of(
            "lic_last_seen", List.of("SELECT", "INSERT", "UPDATE"),
            "lic_clock_reset", List.of("SELECT", "INSERT"),
            "lic_version_floor", List.of("SELECT"));
    private static final String RUN_STEP = "run the licensing schema step (Flyway location " + LOCATION
            + ", history table " + HISTORY_TABLE + ") before starting the app";

    private LicensingSchema() {
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
        try (InputStream in = LicensingSchema.class.getClassLoader().getResourceAsStream(LOCATION + "/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Migration file missing from the classpath: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Checks, without changing anything, that the licensing tables are there at {@link #VERSION} in the schema the
     * connection uses, and that the connection's user may read and write them.
     *
     * @throws LicensingSchemaException naming exactly what is wrong and what to do
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
                    throw new LicensingSchemaException(LIC_TABLES_MISSING, "Licensing tables missing (expected version "
                            + VERSION + "): none of the lic_ tables exist in the database schema the app connects to; "
                            + RUN_STEP + ".");
                }
                throw new LicensingSchemaException(LIC_VERSION_UNKNOWN, "Licensing tables at an unknown version (table "
                        + VERSION_TABLE + " missing), expected version " + VERSION + ": " + RUN_STEP + ".");
            }
            requireRights(c, VERSION_TABLE, List.of("SELECT"));
            int version = version(c);
            if (version < VERSION) {
                throw new LicensingSchemaException(LIC_VERSION_OLDER, "Licensing tables at version " + version
                        + ", expected version " + VERSION + ": " + RUN_STEP + ".");
            }
            if (version > VERSION) {
                throw new LicensingSchemaException(LIC_VERSION_NEWER, "Licensing tables at version " + version
                        + ", newer than this app expects (version " + VERSION + "): install the app release that "
                        + "matches the database, or restore the database.");
            }
            if (!missing.isEmpty()) {
                throw new LicensingSchemaException(LIC_TABLES_INCOMPLETE, "Licensing tables incomplete at version "
                        + version + ", missing: " + String.join(", ", missing) + "; " + RUN_STEP + ".");
            }
            for (String table : TABLES) {
                requireRights(c, table, FEWER_RIGHTS.getOrDefault(table, RIGHTS));
            }
        } catch (SQLException e) {
            throw new LicensingSchemaException(LIC_CHECK_FAILED, "Licensing tables could not be checked: the database "
                    + "refused the check (SQL state " + e.getSQLState() + ").", e);
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
                throw new LicensingSchemaException(LIC_VERSION_UNKNOWN, "Licensing tables at an unknown version ("
                        + VERSION_TABLE + " is empty), expected version " + VERSION + ": " + RUN_STEP + ".");
            }
            return v;
        }
    }

    private static void requireRights(Connection c, String table, List<String> rights) throws SQLException {
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
            throw new LicensingSchemaException(LIC_PRIVILEGES_MISSING, "The app's database user lacks "
                    + String.join(", ", lacking) + " on " + table + ": grant them to that user as the licensing "
                    + "install guide lists.");
        }
    }
}
