package com.documentplatform.monitoring.checks;

import com.documentplatform.errors.HasPlatformCode;
import com.documentplatform.monitoring.DependencyCheck;
import com.documentplatform.monitoring.MonitoringCode;
import com.documentplatform.monitoring.Verdict;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The app's database: reachable, the login accepted, and every platform schema in use at the version this release
 * expects (each module's own read-only check, for example {@code IdentitySchema::verify}, whose exception carries the
 * exact code: tables missing, version older or newer, rights missing). Required at start-up.
 */
public final class DatabaseCheck implements DependencyCheck {

    /** One platform schema's start-up check (read only). */
    public record SchemaVerify(String schema, Consumer<DataSource> verify) {
        public SchemaVerify {
            Objects.requireNonNull(schema, "schema");
            Objects.requireNonNull(verify, "verify");
        }
    }

    private final String name;
    private final DataSource ds;
    private final List<SchemaVerify> schemas;
    private final boolean required;

    public DatabaseCheck(String name, DataSource ds, List<SchemaVerify> schemas, boolean required) {
        this.name = Objects.requireNonNull(name, "name");
        this.ds = Objects.requireNonNull(ds, "ds");
        this.schemas = List.copyOf(schemas);
        this.required = required;
    }

    public DatabaseCheck(DataSource ds, List<SchemaVerify> schemas) {
        this("database", ds, schemas, true);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean requiredAtStartup() {
        return required;
    }

    /** The schemas checked, by name. */
    public List<String> schemas() {
        return schemas.stream().map(SchemaVerify::schema).toList();
    }

    @Override
    public Verdict check() {
        try (Connection c = ds.getConnection()) {
            if (!c.isValid(5)) {
                return Verdict.down(MonitoringCode.MON_DB_UNREACHABLE, Map.of("database", name, "sqlState", "-"));
            }
        } catch (SQLException e) {
            String state = e.getSQLState() == null ? "-" : e.getSQLState();
            return Verdict.down(state.startsWith("28") ? MonitoringCode.MON_DB_LOGIN_REFUSED
                    : MonitoringCode.MON_DB_UNREACHABLE, Map.of("database", name, "sqlState", state));
        }
        for (SchemaVerify s : schemas) {
            try {
                s.verify().accept(ds);
            } catch (RuntimeException e) {
                if (e instanceof HasPlatformCode h) return Verdict.down(h.platformCode(), Map.of("schema", s.schema()));
                return Verdict.down(MonitoringCode.MON_CHECK_FAILED, Map.of("check", name + " " + s.schema(),
                        "error", e.getClass().getName()));
            }
        }
        return Verdict.working();
    }
}
