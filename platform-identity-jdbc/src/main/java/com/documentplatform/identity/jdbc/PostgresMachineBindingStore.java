package com.documentplatform.identity.jdbc;

import com.documentplatform.identity.store.ChangeSource;
import com.documentplatform.identity.store.MachineBinding;
import com.documentplatform.identity.store.MachineBindingChange;
import com.documentplatform.identity.store.MachineBindingStatus;
import com.documentplatform.identity.store.MachineBindingStore;

import javax.sql.DataSource;
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
 * Integration client bindings in Postgres ({@code idn_machine_binding}, history in {@code idn_machine_binding_history}),
 * plain JDBC, DML only. Every insert and update writes a history row in the same transaction; nothing is deleted.
 */
public final class PostgresMachineBindingStore implements MachineBindingStore {

    private static final String COLUMNS = "binding_id, tenant_id, product_code, client_id, slot_id, entity_uuid, "
            + "source_key, description, status, version";

    private final Db db;

    public PostgresMachineBindingStore(DataSource ds) {
        this.db = Db.of(ds);
    }

    private PostgresMachineBindingStore(Db db) {
        this.db = db;
    }

    /** A store on a connection the caller owns (for example the setup command's single transaction). */
    public static PostgresMachineBindingStore onConnection(Connection c) {
        return new PostgresMachineBindingStore(Db.of(c));
    }

    @Override
    public List<MachineBinding> bindings(String tenantId, String productCode) {
        return db.read("read client bindings", c -> {
            try (PreparedStatement ps = c.prepareStatement("select " + COLUMNS + " from idn_machine_binding "
                    + "where tenant_id = ? and product_code = ? order by client_id")) {
                ps.setString(1, tenantId);
                ps.setString(2, productCode);
                return all(ps);
            }
        });
    }

    @Override
    public Optional<MachineBinding> activeBinding(String productCode, String clientId) {
        return db.read("read client binding", c -> {
            try (PreparedStatement ps = c.prepareStatement("select " + COLUMNS + " from idn_machine_binding "
                    + "where product_code = ? and client_id = ? and status = 'ACTIVE'")) {
                ps.setString(1, productCode);
                ps.setString(2, clientId);
                List<MachineBinding> found = all(ps);
                // One installation serves one customer, so a client ID is ACTIVE at most once per product.
                return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
            }
        });
    }

    @Override
    public MachineBinding insertBinding(MachineBinding binding, ChangeSource source, String changedBy) {
        requireChange(source, changedBy);
        MachineBinding v1 = binding.withVersion(1);
        return db.inTransaction("insert client binding", c -> {
            requireRow(c, "select 1 from idn_customer where tenant_id = ? for update", v1.tenantId(),
                    "customer " + v1.tenantId() + " is not stored");
            requireRow(c, "select 1 from idn_entity where slot_id = ?", v1.slotId(),
                    "entity slot " + v1.slotId() + " is not stored");
            try (PreparedStatement ps = c.prepareStatement("select 1 from idn_machine_binding where tenant_id = ? "
                    + "and product_code = ? and client_id = ?")) {
                ps.setString(1, v1.tenantId());
                ps.setString(2, v1.productCode());
                ps.setString(3, v1.clientId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        throw new IllegalStateException("client " + v1.clientId() + " already has a binding for "
                                + v1.productCode());
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("insert into idn_machine_binding (" + COLUMNS
                    + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                bind(ps, v1);
                ps.executeUpdate();
            }
            history(c, v1, source, changedBy);
            return v1;
        });
    }

    @Override
    public Optional<MachineBinding> updateBinding(MachineBinding binding, long expectedVersion, ChangeSource source,
                                                  String changedBy) {
        requireChange(source, changedBy);
        MachineBinding next = binding.withVersion(expectedVersion + 1);
        return db.inTransaction("update client binding", c -> {
            MachineBinding stored;
            try (PreparedStatement ps = c.prepareStatement("select " + COLUMNS + " from idn_machine_binding "
                    + "where binding_id = ? for update")) {
                ps.setObject(1, next.bindingId());
                List<MachineBinding> found = all(ps);
                if (found.isEmpty()) return Optional.empty();
                stored = found.get(0);
            }
            if (stored.version() != expectedVersion) return Optional.empty();
            if (!stored.tenantId().equals(next.tenantId()) || !stored.productCode().equals(next.productCode())
                    || !stored.clientId().equals(next.clientId())) {
                throw new IllegalArgumentException("a client binding keeps its customer, product and client ID");
            }
            requireRow(c, "select 1 from idn_entity where slot_id = ?", next.slotId(),
                    "entity slot " + next.slotId() + " is not stored");
            try (PreparedStatement ps = c.prepareStatement("update idn_machine_binding set slot_id = ?, "
                    + "entity_uuid = ?, source_key = ?, description = ?, status = ?, version = ?, updated_at = now() "
                    + "where binding_id = ?")) {
                ps.setString(1, next.slotId());
                ps.setObject(2, next.entityUuid());
                ps.setString(3, next.sourceKey());
                ps.setString(4, next.description());
                ps.setString(5, next.status().name());
                ps.setLong(6, next.version());
                ps.setObject(7, next.bindingId());
                ps.executeUpdate();
            }
            history(c, next, source, changedBy);
            return Optional.of(next);
        });
    }

    @Override
    public List<MachineBindingChange> bindingHistory(UUID bindingId) {
        return db.read("read client binding history", c -> {
            List<MachineBindingChange> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select version, client_id, slot_id, source_key, "
                    + "description, status, change_source, changed_by, changed_at from idn_machine_binding_history "
                    + "where binding_id = ? order by version")) {
                ps.setObject(1, bindingId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new MachineBindingChange(rs.getLong(1), rs.getString(2), rs.getString(3),
                                rs.getString(4), rs.getString(5), MachineBindingStatus.valueOf(rs.getString(6)),
                                ChangeSource.valueOf(rs.getString(7)), rs.getString(8), Sql.instant(rs, "changed_at")));
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

    private static void requireRow(Connection c, String sql, String key, String message) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException(message);
            }
        }
    }

    private static void history(Connection c, MachineBinding b, ChangeSource source, String changedBy)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("insert into idn_machine_binding_history (binding_id, version, "
                + "tenant_id, product_code, client_id, slot_id, source_key, description, status, change_source, "
                + "changed_by) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, b.bindingId());
            ps.setLong(2, b.version());
            ps.setString(3, b.tenantId());
            ps.setString(4, b.productCode());
            ps.setString(5, b.clientId());
            ps.setString(6, b.slotId());
            ps.setString(7, b.sourceKey());
            ps.setString(8, b.description());
            ps.setString(9, b.status().name());
            ps.setString(10, source.name());
            ps.setString(11, changedBy);
            ps.executeUpdate();
        }
    }

    private static void bind(PreparedStatement ps, MachineBinding b) throws SQLException {
        ps.setObject(1, b.bindingId());
        ps.setString(2, b.tenantId());
        ps.setString(3, b.productCode());
        ps.setString(4, b.clientId());
        ps.setString(5, b.slotId());
        ps.setObject(6, b.entityUuid());
        ps.setString(7, b.sourceKey());
        ps.setString(8, b.description());
        ps.setString(9, b.status().name());
        ps.setLong(10, b.version());
    }

    private static List<MachineBinding> all(PreparedStatement ps) throws SQLException {
        List<MachineBinding> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new MachineBinding(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getObject(6, UUID.class), rs.getString(7),
                        rs.getString(8), MachineBindingStatus.valueOf(rs.getString(9)), rs.getLong(10)));
            }
        }
        return out;
    }
}
