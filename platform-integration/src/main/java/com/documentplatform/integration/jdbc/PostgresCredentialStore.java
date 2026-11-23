package com.documentplatform.integration.jdbc;

import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.outbound.CertificateInfo;
import com.documentplatform.integration.credential.CredentialChange;
import com.documentplatform.integration.credential.CredentialSource;
import com.documentplatform.integration.credential.CredentialState;
import com.documentplatform.integration.credential.CredentialStore;
import com.documentplatform.integration.credential.KeystoreRef;
import com.documentplatform.integration.credential.StoredCredential;
import com.documentplatform.integration.store.IntegrationStore;
import com.documentplatform.notifications.secret.SecretRef;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Client certificates and their history in Postgres ({@code int_credential}, {@code int_credential_change}), plain
 * JDBC, DML only. A change is one transaction: the rows (retired ones first, so the "one CURRENT, one NEXT" indexes
 * hold at every statement), then the history row numbered {@code expectedVersion + 1}, whose primary key refuses a
 * second change saved with the same expected version.
 */
public final class PostgresCredentialStore implements CredentialStore {

    private static final String COLUMNS = "id, integration_id, entity_uuid, product, state, source, subject, issuer, "
            + "serial, sha256, valid_from, valid_to, sealed, keystore_path, keystore_password_ref, keystore_alias, "
            + "switch_at, added_by, added_at, retired_at";

    private final DataSource dataSource;

    public PostgresCredentialStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public List<StoredCredential> list(UUID integrationId) {
        return query("select " + COLUMNS + " from int_credential where integration_id = ? order by added_at, id",
                integrationId);
    }

    @Override
    public List<StoredCredential> active() {
        return query("select " + COLUMNS + " from int_credential where state in ('CURRENT', 'NEXT') "
                + "order by integration_id, added_at", null);
    }

    @Override
    public int version(UUID integrationId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "select coalesce(max(version), 0) from int_credential_change where integration_id = ?")) {
            ps.setObject(1, integrationId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw failed(e);
        }
    }

    @Override
    public void save(int expectedVersion, CredentialChange change, List<StoredCredential> rows) {
        if (change.version() != expectedVersion + 1) throw new IllegalArgumentException("The change is numbered next");
        List<StoredCredential> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparing((StoredCredential r) -> r.state() != CredentialState.RETIRED));
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                int current;
                try (PreparedStatement ps = c.prepareStatement("select coalesce(max(version), 0) from "
                        + "int_credential_change where integration_id = ?")) {
                    ps.setObject(1, change.integrationId());
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        current = rs.getInt(1);
                    }
                }
                if (current != expectedVersion) {
                    c.rollback();
                    throw new IntegrationStore.Conflict(IntegrationCode.INT_CREDENTIAL_CONFLICT);
                }
                for (StoredCredential r : ordered) upsert(c, r);
                try (PreparedStatement ps = c.prepareStatement("insert into int_credential_change (integration_id, "
                        + "version, change, credential_id, sha256, changed_by, changed_at) values (?, ?, ?, ?, ?, ?, ?)")) {
                    ps.setObject(1, change.integrationId());
                    ps.setInt(2, change.version());
                    ps.setString(3, change.kind().name());
                    ps.setObject(4, change.credential());
                    ps.setString(5, change.fingerprint());
                    ps.setString(6, change.by());
                    ps.setTimestamp(7, Timestamp.from(change.at()));
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            // A unique index (two changes at once, or a second CURRENT / NEXT): someone else changed it first.
            if ("23505".equals(e.getSQLState())) throw new IntegrationStore.Conflict(IntegrationCode.INT_CREDENTIAL_CONFLICT);
            throw failed(e);
        }
    }

    private static void upsert(Connection c, StoredCredential r) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("update int_credential set state = ?, subject = ?, issuer = ?, "
                + "serial = ?, sha256 = ?, valid_from = ?, valid_to = ?, sealed = ?, switch_at = ?, retired_at = ? "
                + "where id = ?")) {
            ps.setString(1, r.state().name());
            ps.setString(2, r.info().subject());
            ps.setString(3, r.info().issuer());
            ps.setString(4, r.info().serial());
            ps.setString(5, r.info().sha256());
            ps.setTimestamp(6, Timestamp.from(r.info().validFrom()));
            ps.setTimestamp(7, Timestamp.from(r.info().validTo()));
            ps.setString(8, r.sealed());
            ps.setTimestamp(9, ts(r.switchAt()));
            ps.setTimestamp(10, ts(r.retiredAt()));
            ps.setObject(11, r.id());
            if (ps.executeUpdate() == 1) return;
        }
        try (PreparedStatement ps = c.prepareStatement("insert into int_credential (" + COLUMNS + ") values "
                + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, r.id());
            ps.setObject(2, r.integrationId());
            ps.setObject(3, r.entity());
            ps.setString(4, r.product());
            ps.setString(5, r.state().name());
            ps.setString(6, r.source().name());
            ps.setString(7, r.info().subject());
            ps.setString(8, r.info().issuer());
            ps.setString(9, r.info().serial());
            ps.setString(10, r.info().sha256());
            ps.setTimestamp(11, Timestamp.from(r.info().validFrom()));
            ps.setTimestamp(12, Timestamp.from(r.info().validTo()));
            ps.setString(13, r.sealed());
            KeystoreRef k = r.keystore();
            ps.setString(14, k == null ? null : k.path());
            ps.setString(15, k == null ? null : k.password().value());
            ps.setString(16, k == null ? null : k.alias());
            ps.setTimestamp(17, ts(r.switchAt()));
            ps.setString(18, r.addedBy());
            ps.setTimestamp(19, Timestamp.from(r.addedAt()));
            ps.setTimestamp(20, ts(r.retiredAt()));
            ps.executeUpdate();
        }
    }

    @Override
    public List<CredentialChange> history(UUID integrationId) {
        List<CredentialChange> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("select h.version, h.change, h.credential_id, h.sha256, "
                     + "h.changed_by, h.changed_at, k.entity_uuid, k.product from int_credential_change h "
                     + "join int_credential k on k.id = h.credential_id where h.integration_id = ? order by h.version")) {
            ps.setObject(1, integrationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new CredentialChange(integrationId, rs.getObject(7, UUID.class), rs.getString(8),
                            rs.getInt(1), CredentialChange.Kind.valueOf(rs.getString(2)),
                            rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5),
                            rs.getTimestamp(6).toInstant()));
                }
            }
            return out;
        } catch (SQLException e) {
            throw failed(e);
        }
    }

    private List<StoredCredential> query(String sql, UUID integrationId) {
        List<StoredCredential> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (integrationId != null) ps.setObject(1, integrationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(row(rs));
            }
            return out;
        } catch (SQLException e) {
            throw failed(e);
        }
    }

    private static StoredCredential row(ResultSet rs) throws SQLException {
        CredentialSource source = CredentialSource.valueOf(rs.getString("source"));
        KeystoreRef k = source == CredentialSource.KEYSTORE ? new KeystoreRef(rs.getString("keystore_path"),
                SecretRef.of(rs.getString("keystore_password_ref")), rs.getString("keystore_alias")) : null;
        return new StoredCredential(rs.getObject("id", UUID.class), rs.getObject("integration_id", UUID.class),
                rs.getObject("entity_uuid", UUID.class), rs.getString("product"),
                CredentialState.valueOf(rs.getString("state")), source,
                new CertificateInfo(rs.getString("subject"), rs.getString("issuer"), rs.getString("serial"),
                        rs.getString("sha256"), rs.getTimestamp("valid_from").toInstant(),
                        rs.getTimestamp("valid_to").toInstant()),
                rs.getString("sealed"), k, instant(rs.getTimestamp("switch_at")), rs.getString("added_by"),
                rs.getTimestamp("added_at").toInstant(), instant(rs.getTimestamp("retired_at")));
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static IllegalStateException failed(SQLException e) {
        // SQL state only: the statement's values are not repeated.
        return new IllegalStateException("Credential store: the database refused the request (SQL state "
                + e.getSQLState() + ")");
    }
}
