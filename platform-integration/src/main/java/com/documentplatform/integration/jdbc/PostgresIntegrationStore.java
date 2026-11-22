package com.documentplatform.integration.jdbc;

import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.integration.config.ConfigJson;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.store.ConfigChange;
import com.documentplatform.integration.store.IntegrationStore;
import com.documentplatform.integration.store.StoredIntegration;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Configurations and their history in Postgres ({@code int_integration}, {@code int_integration_version}), with plain
 * JDBC and DML only. A new version is written in one transaction: the version row first, then the current row, only if
 * it is still at the expected version. The database itself refuses to change or delete a stored version.
 */
public final class PostgresIntegrationStore implements IntegrationStore {

    private final DataSource dataSource;

    public PostgresIntegrationStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public StoredIntegration create(IntegrationConfig config, ConfigChange change) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("insert into int_integration (id, entity_uuid, product,"
                        + " kind, direction, name, enabled, version, created_by, created_at, changed_by, changed_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?) on conflict (id) do nothing")) {
                    ps.setObject(1, config.id());
                    ps.setObject(2, config.entity());
                    ps.setString(3, config.product());
                    ps.setString(4, config.kind().name());
                    ps.setString(5, config.direction().name());
                    ps.setString(6, config.name());
                    ps.setBoolean(7, config.enabled());
                    ps.setString(8, change.changedBy());
                    ps.setTimestamp(9, Timestamp.from(change.changedAt()));
                    ps.setString(10, change.changedBy());
                    ps.setTimestamp(11, Timestamp.from(change.changedAt()));
                    if (ps.executeUpdate() == 0) {
                        c.rollback();
                        throw new Conflict(IntegrationCode.INT_CONFIG_EXISTS);
                    }
                }
                insertVersion(c, config, 1, change);
                c.commit();
                return new StoredIntegration(config, 1, change.changedBy(), change.changedAt());
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw failed(e);
        }
    }

    @Override
    public StoredIntegration update(IntegrationConfig config, int expectedVersion, ConfigChange change) {
        int next = expectedVersion + 1;
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                Integer current = null;
                try (PreparedStatement ps = c.prepareStatement(
                        "select version from int_integration where id = ? for update")) {
                    ps.setObject(1, config.id());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) current = rs.getInt(1);
                    }
                }
                if (current == null) {
                    c.rollback();
                    throw new Conflict(IntegrationCode.INT_CONFIG_NOT_FOUND);
                }
                if (current != expectedVersion) {
                    c.rollback();
                    throw new Conflict(IntegrationCode.INT_CONFIG_VERSION_CONFLICT);
                }
                insertVersion(c, config, next, change);
                try (PreparedStatement ps = c.prepareStatement("update int_integration set version = ?, name = ?,"
                        + " enabled = ?, changed_by = ?, changed_at = ? where id = ? and version = ?")) {
                    ps.setInt(1, next);
                    ps.setString(2, config.name());
                    ps.setBoolean(3, config.enabled());
                    ps.setString(4, change.changedBy());
                    ps.setTimestamp(5, Timestamp.from(change.changedAt()));
                    ps.setObject(6, config.id());
                    ps.setInt(7, expectedVersion);
                    if (ps.executeUpdate() != 1) {
                        c.rollback();
                        throw new Conflict(IntegrationCode.INT_CONFIG_VERSION_CONFLICT);
                    }
                }
                c.commit();
                return new StoredIntegration(config, next, change.changedBy(), change.changedAt());
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) throw new Conflict(IntegrationCode.INT_CONFIG_VERSION_CONFLICT);
            throw failed(e);
        }
    }

    private static void insertVersion(Connection c, IntegrationConfig config, int version, ConfigChange change)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("insert into int_integration_version (integration_id, version,"
                + " config, changed_by, changed_at, changed_fields, warnings) values (?, ?, ?::jsonb, ?, ?, ?, ?)")) {
            ps.setObject(1, config.id());
            ps.setInt(2, version);
            ps.setString(3, ConfigJson.write(config));
            ps.setString(4, change.changedBy());
            ps.setTimestamp(5, Timestamp.from(change.changedAt()));
            ps.setArray(6, c.createArrayOf("text", change.changedFields().toArray()));
            ps.setArray(7, c.createArrayOf("text", change.warnings().stream().map(Enum::name).toArray()));
            ps.executeUpdate();
        }
    }

    @Override
    public Optional<StoredIntegration> current(UUID id) {
        return one("select v.config, v.version, v.changed_by, v.changed_at from int_integration i"
                + " join int_integration_version v on v.integration_id = i.id and v.version = i.version"
                + " where i.id = ?", id, null);
    }

    @Override
    public Optional<StoredIntegration> version(UUID id, int version) {
        return one("select config, version, changed_by, changed_at from int_integration_version"
                + " where integration_id = ? and version = ?", id, version);
    }

    private Optional<StoredIntegration> one(String sql, UUID id, Integer version) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            if (version != null) ps.setInt(2, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(stored(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failed(e);
        }
    }

    @Override
    public List<ConfigChange> history(UUID id) {
        String sql = "select i.entity_uuid, i.product, v.version, v.changed_by, v.changed_at, v.changed_fields,"
                + " v.warnings from int_integration_version v join int_integration i on i.id = v.integration_id"
                + " where v.integration_id = ? order by v.version";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            List<ConfigChange> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    List<IntegrationCode> warnings = new ArrayList<>();
                    for (String w : strings(rs.getArray(7))) warnings.add(IntegrationCode.valueOf(w));
                    out.add(new ConfigChange(id, rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3),
                            rs.getString(4), rs.getTimestamp(5).toInstant(), strings(rs.getArray(6)), warnings));
                }
            }
            return out;
        } catch (SQLException e) {
            throw failed(e);
        }
    }

    @Override
    public List<StoredIntegration> list(UUID entity, String product) {
        String sql = "select v.config, v.version, v.changed_by, v.changed_at from int_integration i"
                + " join int_integration_version v on v.integration_id = i.id and v.version = i.version"
                + " where i.entity_uuid = ? and i.product = ? order by i.created_at, i.id";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, entity);
            ps.setString(2, product);
            List<StoredIntegration> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(stored(rs));
            }
            return out;
        } catch (SQLException e) {
            throw failed(e);
        }
    }

    private static StoredIntegration stored(ResultSet rs) throws SQLException {
        return new StoredIntegration(ConfigJson.read(rs.getString(1)), rs.getInt(2), rs.getString(3),
                rs.getTimestamp(4).toInstant());
    }

    private static List<String> strings(Array a) throws SQLException {
        List<String> out = new ArrayList<>();
        if (a == null) return out;
        for (Object o : (Object[]) a.getArray()) out.add((String) o);
        return out;
    }

    private static IllegalStateException failed(SQLException e) {
        // SQL state only: the statement's values (a configuration) are not repeated.
        return new IllegalStateException("Integration store: the database refused the request (SQL state "
                + e.getSQLState() + ")");
    }
}
