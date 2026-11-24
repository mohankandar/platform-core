package com.documentplatform.fields.jdbc;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.documentplatform.fields.jdbc.FieldSchemaException.Problem.*;

/**
 * The field tables (all prefixed {@code fld_}): their SQL files, and the check the app makes at startup.
 *
 * <p><b>Database rule:</b> the tables are created and changed only by the app's schema step, as a separate Flyway run
 * with location {@link #LOCATION} and history table {@link #HISTORY_TABLE}. App startup never creates tables or
 * inserts rows: {@link #verify(DataSource)} only reads, and stops with a specific message if the tables are missing,
 * at another version, or not usable by the app's database user, who needs only data rights (DML).
 */
public final class FieldSchema {

    /** Classpath location of the migration files. */
    public static final String LOCATION = "db/platform/fields";

    /** The Flyway history table of the separate fields run. */
    public static final String HISTORY_TABLE = "fld_schema_history";

    /** The table version this code needs: the number of the last migration file. */
    public static final int VERSION = 2;

    /** The tables the code reads and writes at runtime. */
    public static final List<String> TABLES = List.of("fld_field_definition", "fld_field_definition_history",
            "fld_field_extension", "fld_record", "fld_value");

    /**
     * The optional file the DBA runs (as the owner of the fld_ tables) to add partial search later, when pg_trgm was
     * not available at the schema step. Not a Flyway file.
     */
    public static final String PARTIAL_SEARCH_SCRIPT = "db/platform/fields-optional/partial-search.sql";

    /** The trigram index partial search uses; without it partial search works as prefix search. */
    public static final String PARTIAL_SEARCH_INDEX = "fld_value_partial";

    /** Holds the version of the files that were run. */
    public static final String VERSION_TABLE = "fld_schema_version";

    /** The rights the code uses on each table (the install guide grants all four DML rights on every table). */
    private static final Map<String, List<String>> RIGHTS = rights();

    private static final List<String> SCRIPTS = List.of("V1__fields.sql", "V2__fields_search_path.sql");
    private static final String RUN_STEP = "run the fields schema step (Flyway location " + LOCATION
            + ", history table " + HISTORY_TABLE + ") before starting the app";

    private FieldSchema() {
    }

    private static Map<String, List<String>> rights() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("fld_field_definition", List.of("SELECT", "INSERT", "UPDATE"));
        m.put("fld_field_definition_history", List.of("SELECT", "INSERT"));
        m.put("fld_field_extension", List.of("SELECT", "INSERT", "UPDATE", "DELETE"));
        m.put("fld_record", List.of("SELECT", "INSERT", "UPDATE", "DELETE"));
        m.put("fld_value", List.of("SELECT", "INSERT", "DELETE"));
        return m;
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
        try (InputStream in = FieldSchema.class.getClassLoader().getResourceAsStream(LOCATION + "/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Migration file missing from the classpath: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Checks, without changing anything, that the field tables are there at {@link #VERSION} in the schema the
     * connection uses, and that the connection's user may read and write them.
     *
     * @throws FieldSchemaException naming exactly what is wrong and what to do
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
                    throw new FieldSchemaException(FLD_TABLES_MISSING, "Field tables missing (expected version "
                            + VERSION + "): none of the fld_ tables exist in the database schema the app connects to; "
                            + RUN_STEP + ".");
                }
                throw new FieldSchemaException(FLD_VERSION_UNKNOWN, "Field tables at an unknown version (table "
                        + VERSION_TABLE + " missing), expected version " + VERSION + ": " + RUN_STEP + ".");
            }
            requireRights(c, VERSION_TABLE, List.of("SELECT"));
            int version = version(c);
            if (version < VERSION) {
                throw new FieldSchemaException(FLD_VERSION_OLDER, "Field tables at version " + version
                        + ", expected version " + VERSION + ": " + RUN_STEP + ".");
            }
            if (version > VERSION) {
                throw new FieldSchemaException(FLD_VERSION_NEWER, "Field tables at version " + version
                        + ", newer than this app expects (version " + VERSION + "): install the app release that "
                        + "matches the database, or restore the database.");
            }
            if (!missing.isEmpty()) {
                throw new FieldSchemaException(FLD_TABLES_INCOMPLETE, "Field tables incomplete at version "
                        + version + ", missing: " + String.join(", ", missing) + "; " + RUN_STEP + ".");
            }
            for (Map.Entry<String, List<String>> e : RIGHTS.entrySet()) {
                requireRights(c, e.getKey(), e.getValue());
            }
        } catch (SQLException e) {
            throw new FieldSchemaException(FLD_CHECK_FAILED, "Field tables could not be checked: the database "
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
                throw new FieldSchemaException(FLD_VERSION_UNKNOWN, "Field tables at an unknown version ("
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
            throw new FieldSchemaException(FLD_PRIVILEGES_MISSING, "The app's database user lacks "
                    + String.join(", ", lacking) + " on " + table + ": grant SELECT, INSERT, UPDATE and DELETE on the "
                    + "fld_ tables (and SELECT on " + VERSION_TABLE + ") to that user.");
        }
    }
}
