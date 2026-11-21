package com.documentplatform.identity.jdbc;

import com.documentplatform.identity.IdentityConnectionStatus;
import com.documentplatform.identity.IdentityProviderType;
import com.documentplatform.identity.store.ChangeSource;
import com.documentplatform.identity.store.ConnectionChange;
import com.documentplatform.identity.store.LoginConnection;
import com.documentplatform.identity.store.LoginConnectionStore;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link LoginConnectionStore} on tables {@code idn_login_connection} and {@code idn_login_connection_history}. Every
 * write adds a history row in the same transaction. DML only; never deletes.
 */
public final class PostgresLoginConnectionStore implements LoginConnectionStore {

    private static final String COLUMNS = "connection_id, tenant_id, product_code, provider_id, provider_type, "
            + "issuer_uri, browser_client_id, api_audience, secret_reference, status, configuration_version";

    private final Db db;

    public PostgresLoginConnectionStore(DataSource ds) {
        this.db = Db.of(ds);
    }

    private PostgresLoginConnectionStore(Db db) {
        this.db = db;
    }

    /** A store on a connection the caller owns (for example the setup command's single transaction). */
    public static PostgresLoginConnectionStore onConnection(Connection c) {
        return new PostgresLoginConnectionStore(Db.of(c));
    }

    @Override
    public Optional<LoginConnection> active(String tenantId, String productCode) {
        return db.read("read active login connection", c -> {
            try (PreparedStatement ps = c.prepareStatement("select " + COLUMNS + " from idn_login_connection "
                    + "where tenant_id = ? and product_code = ? and status = 'ACTIVE'")) {
                ps.setString(1, tenantId);
                ps.setString(2, productCode);
                return one(ps);
            }
        });
    }

    @Override
    public Optional<LoginConnection> find(UUID connectionId) {
        return db.read("read login connection", c -> read(c, connectionId));
    }

    @Override
    public LoginConnection insert(LoginConnection connection, ChangeSource source, String changedBy) {
        requireChange(source, changedBy);
        LoginConnection v1 = connection.withVersion(1);
        return db.inTransaction("insert login connection", c -> {
            requireCustomer(c, v1.tenantId());
            if (read(c, v1.connectionId()).isPresent()) {
                throw new IllegalStateException("login connection " + v1.connectionId() + " already exists");
            }
            if (v1.status() == IdentityConnectionStatus.ACTIVE) {
                requireNoOtherActive(c, v1);
            }
            try (PreparedStatement ps = c.prepareStatement("insert into idn_login_connection (" + COLUMNS
                    + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                bind(ps, v1);
                ps.executeUpdate();
            }
            history(c, v1, source, changedBy);
            return v1;
        });
    }

    @Override
    public Optional<LoginConnection> update(LoginConnection connection, long expectedVersion, ChangeSource source,
                                            String changedBy) {
        requireChange(source, changedBy);
        LoginConnection next = connection.withVersion(expectedVersion + 1);
        return db.inTransaction("update login connection", c -> {
            Optional<LoginConnection> stored;
            try (PreparedStatement lock = c.prepareStatement("select " + COLUMNS + " from idn_login_connection "
                    + "where connection_id = ? for update")) {
                lock.setObject(1, next.connectionId());
                stored = one(lock);
            }
            if (stored.isEmpty() || stored.get().configurationVersion() != expectedVersion) {
                return Optional.empty();
            }
            if (!stored.get().tenantId().equals(next.tenantId()) || !stored.get().productCode().equals(next.productCode())) {
                throw new IllegalArgumentException("a login connection keeps its customer and product");
            }
            if (next.status() == IdentityConnectionStatus.ACTIVE) {
                requireNoOtherActive(c, next);
            }
            try (PreparedStatement ps = c.prepareStatement("update idn_login_connection set provider_id = ?, "
                    + "provider_type = ?, issuer_uri = ?, browser_client_id = ?, api_audience = ?, secret_reference = ?, "
                    + "status = ?, configuration_version = ?, updated_at = now() where connection_id = ?")) {
                ps.setString(1, next.providerId());
                ps.setString(2, next.providerType().name());
                ps.setString(3, next.issuer().toString());
                ps.setString(4, next.browserClientId());
                ps.setString(5, next.apiAudience());
                ps.setString(6, next.secretReference());
                ps.setString(7, next.status().name());
                ps.setLong(8, next.configurationVersion());
                ps.setObject(9, next.connectionId());
                ps.executeUpdate();
            }
            history(c, next, source, changedBy);
            return Optional.of(next);
        });
    }

    @Override
    public List<ConnectionChange> history(UUID connectionId) {
        return db.read("read login connection history", c -> {
            List<ConnectionChange> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select configuration_version, status, change_source, "
                    + "changed_by, changed_at from idn_login_connection_history where connection_id = ? "
                    + "order by configuration_version")) {
                ps.setObject(1, connectionId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new ConnectionChange(rs.getLong(1), IdentityConnectionStatus.valueOf(rs.getString(2)),
                                ChangeSource.valueOf(rs.getString(3)), rs.getString(4), Sql.instant(rs, "changed_at")));
                    }
                }
            }
            return List.copyOf(out);
        });
    }

    private static void requireChange(ChangeSource source, String changedBy) {
        Objects.requireNonNull(source, "source");
        if (changedBy == null || changedBy.isBlank()) throw new IllegalArgumentException("changedBy is required");
    }

    private static void requireCustomer(Connection c, String tenantId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select 1 from idn_customer where tenant_id = ? for update")) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("customer " + tenantId + " is not stored");
            }
        }
    }

    private static void requireNoOtherActive(Connection c, LoginConnection conn) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select 1 from idn_login_connection where tenant_id = ? "
                + "and product_code = ? and status = 'ACTIVE' and connection_id <> ?")) {
            ps.setString(1, conn.tenantId());
            ps.setString(2, conn.productCode());
            ps.setObject(3, conn.connectionId());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    throw new IllegalStateException("customer " + conn.tenantId() + " already has an ACTIVE login "
                            + "connection for " + conn.productCode());
                }
            }
        }
    }

    private static void history(Connection c, LoginConnection conn, ChangeSource source, String changedBy)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("insert into idn_login_connection_history (" + COLUMNS
                + ", change_source, changed_by) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            bind(ps, conn);
            ps.setString(12, source.name());
            ps.setString(13, changedBy);
            ps.executeUpdate();
        }
    }

    private static void bind(PreparedStatement ps, LoginConnection conn) throws SQLException {
        ps.setObject(1, conn.connectionId());
        ps.setString(2, conn.tenantId());
        ps.setString(3, conn.productCode());
        ps.setString(4, conn.providerId());
        ps.setString(5, conn.providerType().name());
        ps.setString(6, conn.issuer().toString());
        ps.setString(7, conn.browserClientId());
        ps.setString(8, conn.apiAudience());
        ps.setString(9, conn.secretReference());
        ps.setString(10, conn.status().name());
        ps.setLong(11, conn.configurationVersion());
    }

    private static Optional<LoginConnection> read(Connection c, UUID connectionId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "select " + COLUMNS + " from idn_login_connection where connection_id = ?")) {
            ps.setObject(1, connectionId);
            return one(ps);
        }
    }

    private static Optional<LoginConnection> one(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return Optional.empty();
            return Optional.of(new LoginConnection(rs.getObject("connection_id", UUID.class), rs.getString("tenant_id"),
                    rs.getString("product_code"), rs.getString("provider_id"),
                    IdentityProviderType.valueOf(rs.getString("provider_type")), URI.create(rs.getString("issuer_uri")),
                    rs.getString("browser_client_id"), rs.getString("api_audience"), rs.getString("secret_reference"),
                    IdentityConnectionStatus.valueOf(rs.getString("status")), rs.getLong("configuration_version")));
        }
    }
}
