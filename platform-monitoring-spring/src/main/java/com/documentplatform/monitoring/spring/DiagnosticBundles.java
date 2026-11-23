package com.documentplatform.monitoring.spring;

import com.documentplatform.errors.FailureLog;
import com.documentplatform.monitoring.DependencyStatus;
import com.documentplatform.monitoring.DiagnosticBundle;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds the diagnostic bundle for the app's Support screen (a download for an offline customer to send): the
 * configuration from the app's own property sources (the JVM's and the OS environment's left out), secrets removed;
 * the latest check results; the last {@value #FAILURES} failures; the platform schema versions read from their
 * version tables.
 */
public final class DiagnosticBundles {

    public static final int FAILURES = 50;
    /** The version table of every platform schema (P040, install-monitoring-5: model governance's was missing). */
    static final List<String> VERSION_TABLES = List.of("idn_schema_version", "lic_schema_version",
            "notif_schema_version", "fld_schema_version", "erl_schema_version", "int_schema_version",
            "mg_schema_version");

    private final DependencyStatus status;
    private final Environment env;
    private final DataSource ds;
    private final AppVersions app;

    DiagnosticBundles(DependencyStatus status, Environment env, DataSource ds, AppVersions app) {
        this.status = status;
        this.env = env;
        this.ds = ds;
        this.app = app;
    }

    /** Writes the bundle (a zip) to {@code sink}. */
    public DiagnosticBundle.Written write(OutputStream sink) throws IOException {
        return DiagnosticBundle.build(sink, new DiagnosticBundle.Inputs(app.get(), configuration(), status.results(),
                FailureLog.recent(FAILURES), schemaVersions()));
    }

    /** The app's configuration (property sources other than the JVM's system properties and the OS environment). */
    Map<String, String> configuration() {
        Map<String, String> out = new TreeMap<>();
        if (env instanceof ConfigurableEnvironment c) {
            for (PropertySource<?> s : c.getPropertySources()) {
                String n = s.getName();
                if (n.equals("systemProperties") || n.equals("systemEnvironment")) continue;
                if (s instanceof EnumerablePropertySource<?> e) {
                    for (String key : e.getPropertyNames()) {
                        if (out.containsKey(key)) continue;
                        Object v = e.getProperty(key);
                        out.put(key, v == null ? "" : String.valueOf(v));
                    }
                }
            }
        }
        return out;
    }

    Map<String, String> schemaVersions() {
        Map<String, String> out = new LinkedHashMap<>();
        if (ds == null) return out;
        try (Connection c = ds.getConnection()) {
            for (String t : VERSION_TABLES) {
                try (PreparedStatement ps = c.prepareStatement("select to_regclass(?) is not null")) {
                    ps.setString(1, t);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next() || !rs.getBoolean(1)) continue;
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("select max(version) from " + t);
                     ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) out.put(t, String.valueOf(rs.getObject(1)));
                }
            }
        } catch (SQLException e) {
            out.put("database", "not readable (SQL state " + e.getSQLState() + ")");
        }
        return out;
    }
}
