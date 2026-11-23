package com.documentplatform.modelgov.jdbc;

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

import static com.documentplatform.modelgov.jdbc.ModelGovSchemaException.Problem.*;

/**
 * The model-governance tables (all prefixed {@code mg_}): their SQL files, and the check the app makes at startup.
 *
 * <p><b>Database rule:</b> the tables are created and changed only by the app's schema step, as a separate Flyway run
 * with location {@link #LOCATION} and history table {@link #HISTORY_TABLE}. App startup never creates tables or
 * inserts rows: {@link #verify(DataSource)} only reads, and stops with a specific message if the tables are missing,
 * at another version, or not usable by the app's database user, who needs only SELECT, INSERT, UPDATE and DELETE.
 */
public final class ModelGovSchema {

    /** Classpath location of the migration files. */
    public static final String LOCATION = "db/platform/modelgov";

    /** The Flyway history table of the separate model-governance run. */
    public static final String HISTORY_TABLE = "mg_schema_history";

    /** The table version this code needs: the number of the last migration file. */
    public static final int VERSION = 3;

    /** The tables the code reads and writes at runtime. */
    public static final List<String> TABLES = List.of("mg_model", "mg_model_history", "mg_model_use",
            "mg_model_use_history", "mg_threshold", "mg_validation", "mg_validation_measure", "mg_approval",
            "mg_acceptance", "mg_live", "mg_live_history", "mg_monitor_threshold", "mg_measure", "mg_drift",
            "mg_drift_action", "mg_review_notice");

    /** Insert-only tables (their triggers refuse UPDATE and DELETE): the app's user needs SELECT and INSERT only. */
    public static final List<String> INSERT_ONLY_TABLES = List.of("mg_validation", "mg_validation_measure",
            "mg_approval", "mg_acceptance", "mg_live_history", "mg_drift", "mg_drift_action", "mg_review_notice");

    /** Readings: insert only; removed only by {@link #PURGE_FUNCTION} (schema V3). */
    public static final String READINGS_TABLE = "mg_measure";

    /**
     * The retention purge (schema V3, P037): SECURITY DEFINER, owned by the schema owner, never removes readings
     * younger than 30 days. The app's user needs EXECUTE on it (revoked from PUBLIC); DELETE on mg_measure is not needed.
     */
    public static final String PURGE_FUNCTION = "mg_purge_measures";

    /** Holds the version of the files that were run. */
    public static final String VERSION_TABLE = "mg_schema_version";

    private static final List<String> SCRIPTS = List.of("V1__modelgov.sql", "V2__modelgov_monitoring.sql",
            "V3__modelgov_purge_guard.sql");
    private static final List<String> RIGHTS = List.of("SELECT", "INSERT", "UPDATE", "DELETE");
    private static final String RUN_STEP = "run the model-governance schema step (Flyway location " + LOCATION
            + ", history table " + HISTORY_TABLE + ") before starting the app";

    private ModelGovSchema() {
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
        try (InputStream in = ModelGovSchema.class.getClassLoader().getResourceAsStream(LOCATION + "/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Migration file missing from the classpath: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Checks, without changing anything, that the model-governance tables are there at {@link #VERSION} in the schema the
     * connection uses, and that the connection's user may read and write them.
     *
     * @throws ModelGovSchemaException naming exactly what is wrong and what to do
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
                    throw new ModelGovSchemaException(MG_TABLES_MISSING, "Model-governance tables missing (expected version "
                            + VERSION + "): none of the mg_ tables exist in the database schema the app connects to; "
                            + RUN_STEP + ".");
                }
                throw new ModelGovSchemaException(MG_VERSION_UNKNOWN, "Model-governance tables at an unknown version (table "
                        + VERSION_TABLE + " missing), expected version " + VERSION + ": " + RUN_STEP + ".");
            }
            requireRights(c, VERSION_TABLE, List.of("SELECT"));
            int version = version(c);
            if (version < VERSION) {
                throw new ModelGovSchemaException(MG_VERSION_OLDER, "Model-governance tables at version " + version
                        + ", expected version " + VERSION + ": " + RUN_STEP + ".");
            }
            if (version > VERSION) {
                throw new ModelGovSchemaException(MG_VERSION_NEWER, "Model-governance tables at version " + version
                        + ", newer than this app expects (version " + VERSION + "): install the app release that "
                        + "matches the database, or restore the database.");
            }
            if (!missing.isEmpty()) {
                throw new ModelGovSchemaException(MG_TABLES_INCOMPLETE, "Model-governance tables incomplete at version "
                        + version + ", missing: " + String.join(", ", missing) + "; " + RUN_STEP + ".");
            }
            for (String table : TABLES) {
                requireRights(c, table, INSERT_ONLY_TABLES.contains(table) ? List.of("SELECT", "INSERT")
                        : READINGS_TABLE.equals(table) ? List.of("SELECT", "INSERT") : RIGHTS);
            }
            requireExecute(c);
        } catch (SQLException e) {
            throw new ModelGovSchemaException(MG_CHECK_FAILED, "Model-governance tables could not be checked: the database "
                    + "refused the check (SQL state " + e.getSQLState() + ").", e);
        }
    }

    /** EXECUTE on the purge function; at version 3 its absence makes the tables incomplete. */
    private static void requireExecute(Connection c) throws SQLException {
        String signature = PURGE_FUNCTION + "(timestamptz)";
        try (PreparedStatement ps = c.prepareStatement("select to_regprocedure(?) is not null")) {
            ps.setString(1, signature);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (!rs.getBoolean(1)) {
                    throw new ModelGovSchemaException(MG_TABLES_INCOMPLETE, "Model-governance tables incomplete at "
                            + "version " + VERSION + ", missing: function " + signature + "; " + RUN_STEP + ".");
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("select has_function_privilege(?, 'EXECUTE')")) {
            ps.setString(1, signature);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (!rs.getBoolean(1)) {
                    throw new ModelGovSchemaException(MG_PRIVILEGES_MISSING, "The app's database user lacks "
                            + "EXECUTE on function " + signature + ": grant EXECUTE on it to that user (the retention "
                            + "purge of readings).");
                }
            }
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
                throw new ModelGovSchemaException(MG_VERSION_UNKNOWN, "Model-governance tables at an unknown version ("
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
            throw new ModelGovSchemaException(MG_PRIVILEGES_MISSING, "The app's database user lacks "
                    + String.join(", ", lacking) + " on " + table + ": grant SELECT, INSERT, UPDATE and DELETE on the "
                    + "mg_ tables (and SELECT on " + VERSION_TABLE + ") to that user.");
        }
    }
}
