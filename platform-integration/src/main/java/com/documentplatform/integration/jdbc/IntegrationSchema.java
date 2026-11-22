package com.documentplatform.integration.jdbc;

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

import static com.documentplatform.integration.jdbc.IntegrationSchemaException.Problem.*;

/**
 * The integration tables in the app's database ({@code int_integration}, {@code int_integration_version}): their SQL
 * file and the check the app makes at startup.
 *
 * <p><b>Database rule:</b> the tables are created and changed only by the app's schema step, as a separate Flyway run
 * with location {@link #LOCATION} and history table {@link #HISTORY_TABLE}. App startup never creates tables:
 * {@link #verify(DataSource)} only reads, and stops with a specific message if a table is missing, at another version,
 * or not usable by the app's database user, who needs only DML rights ({@link #RIGHTS}).
 */
public final class IntegrationSchema {

    public static final String LOCATION = "db/platform/integration";
    public static final String HISTORY_TABLE = "int_schema_history";
    public static final int VERSION = 1;
    public static final String VERSION_TABLE = "int_schema_version";
    /** Table → the rights the app's database user needs on it. */
    public static final Map<String, List<String>> RIGHTS;

    static {
        Map<String, List<String>> r = new LinkedHashMap<>();
        r.put("int_integration", List.of("SELECT", "INSERT", "UPDATE"));
        r.put("int_integration_version", List.of("SELECT", "INSERT"));
        RIGHTS = java.util.Collections.unmodifiableMap(r);
    }

    private static final List<String> SCRIPTS = List.of("V1__integration.sql");
    private static final String RUN_STEP = "run the integration schema step (Flyway location " + LOCATION
            + ", history table " + HISTORY_TABLE + ") before starting the app";

    private IntegrationSchema() {
    }

    public static List<String> scripts() {
        return SCRIPTS;
    }

    public static String script(String name) {
        if (!SCRIPTS.contains(name)) throw new IllegalArgumentException("Unknown migration file");
        try (InputStream in = IntegrationSchema.class.getClassLoader().getResourceAsStream(LOCATION + "/" + name)) {
            if (in == null) throw new IllegalStateException("Migration file missing from the classpath: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Checks, without changing anything, that the tables are there at {@link #VERSION} and usable. */
    public static void verify(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            List<String> missing = new ArrayList<>();
            for (String t : RIGHTS.keySet()) {
                if (!exists(c, t)) missing.add(t);
            }
            if (!exists(c, VERSION_TABLE)) {
                if (missing.size() == RIGHTS.size()) {
                    throw new IntegrationSchemaException(INT_TABLES_MISSING, "Integration tables missing (expected "
                            + "version " + VERSION + "): " + String.join(", ", missing) + " do not exist in the "
                            + "database schema the app connects to; " + RUN_STEP + ".");
                }
                throw new IntegrationSchemaException(INT_VERSION_UNKNOWN, "Integration tables at an unknown version "
                        + "(table " + VERSION_TABLE + " missing), expected version " + VERSION + ": " + RUN_STEP + ".");
            }
            require(c, VERSION_TABLE, List.of("SELECT"));
            int version;
            try (PreparedStatement ps = c.prepareStatement("select max(version) from " + VERSION_TABLE);
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                version = rs.getInt(1);
                if (rs.wasNull()) {
                    throw new IntegrationSchemaException(INT_VERSION_UNKNOWN, "Integration tables at an unknown "
                            + "version (" + VERSION_TABLE + " is empty), expected version " + VERSION + ": " + RUN_STEP
                            + ".");
                }
            }
            if (version < VERSION) {
                throw new IntegrationSchemaException(INT_VERSION_OLDER, "Integration tables at version " + version
                        + ", expected version " + VERSION + ": " + RUN_STEP + ".");
            }
            if (version > VERSION) {
                throw new IntegrationSchemaException(INT_VERSION_NEWER, "Integration tables at version " + version
                        + ", newer than this app expects (version " + VERSION + "): install the app release that "
                        + "matches the database, or restore the database.");
            }
            if (!missing.isEmpty()) {
                throw new IntegrationSchemaException(INT_TABLES_INCOMPLETE, "Integration tables missing at version "
                        + version + ": " + String.join(", ", missing) + "; " + RUN_STEP + ".");
            }
            for (Map.Entry<String, List<String>> e : RIGHTS.entrySet()) {
                require(c, e.getKey(), e.getValue());
            }
        } catch (SQLException e) {
            throw new IntegrationSchemaException(INT_CHECK_FAILED, "Integration tables could not be checked: the "
                    + "database refused the check (SQL state " + e.getSQLState() + ").");
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
                    if (!rs.getBoolean(1)) lacking.add(right);
                }
            }
        }
        if (!lacking.isEmpty()) {
            throw new IntegrationSchemaException(INT_PRIVILEGES_MISSING, "The app's database user lacks "
                    + String.join(", ", lacking) + " on " + table + ": grant SELECT, INSERT, UPDATE on "
                    + "int_integration, SELECT, INSERT on int_integration_version and SELECT on " + VERSION_TABLE
                    + " to that user.");
        }
    }
}
