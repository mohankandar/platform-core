package com.documentplatform.identity.jdbc;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.identity.store.ActivationOutcome;
import com.documentplatform.identity.store.EntityRecord;
import com.documentplatform.identity.store.EntityStatus;
import com.documentplatform.identity.store.EntityStore;
import com.documentplatform.identity.store.SlotSync;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static com.documentplatform.identity.store.ActivationOutcome.Result.*;

/**
 * {@link EntityStore} on tables {@code idn_customer} and {@code idn_entity}. DML only; never deletes a row.
 *
 * <p>Activation and renaming lock the customer's row first, so two Business Admins naming entities at once cannot
 * end up with the same name, and a slot is activated once.
 */
public final class PostgresEntityStore implements EntityStore {

    private static final String COLUMNS = "slot_id, entity_uuid, tenant_id, status, display_name, activated_by, "
            + "activated_at";

    private final Db db;

    public PostgresEntityStore(DataSource ds) {
        this.db = Db.of(ds);
    }

    private PostgresEntityStore(Db db) {
        this.db = db;
    }

    /**
     * A store on a connection the caller owns: the app activates a slot and inserts its own entity row in one
     * transaction, then commits. The connection is never closed or committed here while a transaction is open.
     */
    public static PostgresEntityStore onConnection(Connection c) {
        return new PostgresEntityStore(Db.of(c));
    }

    @Override
    public Change saveCustomer(String tenantId, String name) {
        EntityUuid.requireTenantId(tenantId);
        Objects.requireNonNull(name, "name");
        if (name.isBlank() || name.length() > 200 || name.chars().anyMatch(ch -> ch < 0x20)) {
            throw new IllegalArgumentException("customer name must be 1 to 200 printable characters");
        }
        return db.inTransaction("save customer", c -> {
            try (PreparedStatement ps = c.prepareStatement("insert into idn_customer (tenant_id, name) values (?, ?) "
                    + "on conflict (tenant_id) do update set name = excluded.name, updated_at = now() "
                    + "where idn_customer.name <> excluded.name returning (xmax = 0) as inserted")) {
                ps.setString(1, tenantId);
                ps.setString(2, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Change.UNCHANGED;
                    return rs.getBoolean(1) ? Change.CREATED : Change.UPDATED;
                }
            }
        });
    }

    @Override
    public Optional<String> customerName(String tenantId) {
        return db.read("read customer", c -> {
            try (PreparedStatement ps = c.prepareStatement("select name from idn_customer where tenant_id = ?")) {
                ps.setString(1, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public SlotSync syncSlots(String tenantId, Collection<String> licensedSlotIds) {
        EntityUuid.requireTenantId(tenantId);
        Set<String> licensed = new LinkedHashSet<>();
        for (String slot : licensedSlotIds) {
            licensed.add(EntityUuid.requireSlotId(slot));
        }
        return db.inTransaction("sync entity slots", c -> {
            lockCustomer(c, tenantId);
            try (PreparedStatement other = c.prepareStatement(
                    "select slot_id from idn_entity where slot_id = any(?) and tenant_id <> ?")) {
                other.setArray(1, c.createArrayOf("text", licensed.toArray()));
                other.setString(2, tenantId);
                try (ResultSet rs = other.executeQuery()) {
                    if (rs.next()) {
                        throw new IllegalStateException("slot " + rs.getString(1) + " belongs to another customer");
                    }
                }
            }
            Map<String, EntityRecord> stored = new TreeMap<>();
            for (EntityRecord r : readEntities(c, tenantId, true)) {
                stored.put(r.slotId(), r);
            }
            List<String> created = new ArrayList<>(), back = new ArrayList<>(), gone = new ArrayList<>(),
                    unchanged = new ArrayList<>();
            try (PreparedStatement insert = c.prepareStatement("insert into idn_entity (slot_id, entity_uuid, "
                    + "tenant_id, status) values (?, ?, ?, 'NOT_ACTIVATED')");
                 PreparedStatement status = c.prepareStatement(
                         "update idn_entity set status = ?, updated_at = now() where slot_id = ?")) {
                for (String slot : licensed) {
                    EntityRecord r = stored.get(slot);
                    if (r == null) {
                        insert.setString(1, slot);
                        insert.setObject(2, EntityUuid.fromSlotId(slot));
                        insert.setString(3, tenantId);
                        insert.executeUpdate();
                        created.add(slot);
                    } else if (r.status() == EntityStatus.NOT_IN_LICENCE) {
                        status.setString(1, r.activatedAt() != null ? EntityStatus.ACTIVE.name()
                                : EntityStatus.NOT_ACTIVATED.name());
                        status.setString(2, slot);
                        status.executeUpdate();
                        back.add(slot);
                    } else {
                        unchanged.add(slot);
                    }
                }
                for (EntityRecord r : stored.values()) {
                    if (licensed.contains(r.slotId())) continue;
                    if (r.status() == EntityStatus.NOT_IN_LICENCE) {
                        unchanged.add(r.slotId());
                    } else {
                        status.setString(1, EntityStatus.NOT_IN_LICENCE.name());
                        status.setString(2, r.slotId());
                        status.executeUpdate();
                        gone.add(r.slotId());
                    }
                }
            }
            return new SlotSync(created, back, gone, unchanged);
        });
    }

    @Override
    public Optional<EntityRecord> entity(String slotId) {
        return db.read("read entity", c -> {
            try (PreparedStatement ps = c.prepareStatement("select " + COLUMNS + " from idn_entity where slot_id = ?")) {
                ps.setString(1, slotId);
                return one(ps);
            }
        });
    }

    @Override
    public Optional<EntityRecord> entity(UUID entityUuid) {
        return db.read("read entity", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "select " + COLUMNS + " from idn_entity where entity_uuid = ?")) {
                ps.setObject(1, entityUuid);
                return one(ps);
            }
        });
    }

    @Override
    public List<EntityRecord> entities(String tenantId) {
        return db.read("read entities", c -> readEntities(c, tenantId, false));
    }

    @Override
    public Map<String, String> activated(String tenantId) {
        return db.read("read activated slots", c -> {
            Map<String, String> out = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement("select slot_id, entity_uuid from idn_entity "
                    + "where tenant_id = ? and status in ('ACTIVE', 'SUSPENDED') order by slot_id")) {
                ps.setString(1, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString(1), rs.getObject(2, UUID.class).toString());
                    }
                }
            }
            return Map.copyOf(out);
        });
    }

    @Override
    public ActivationOutcome activate(String slotId, String name, String actor, Instant at) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(at, "at");
        return db.inTransaction("activate entity slot", c -> {
            Optional<EntityRecord> before = lockSlot(c, slotId);
            if (before.isEmpty()) return new ActivationOutcome(SLOT_UNKNOWN, slotId, null);
            EntityRecord r = before.get();
            if (r.status() == EntityStatus.NOT_IN_LICENCE) return new ActivationOutcome(NOT_IN_LICENCE, slotId, r);
            if (r.status() != EntityStatus.NOT_ACTIVATED) return new ActivationOutcome(ALREADY_ACTIVATED, slotId, r);
            if (nameTaken(c, r.tenantId(), slotId, name)) return new ActivationOutcome(NAME_TAKEN, slotId, r);
            try (PreparedStatement ps = c.prepareStatement("update idn_entity set status = 'ACTIVE', display_name = ?, "
                    + "activated_by = ?, activated_at = ?, updated_at = now() where slot_id = ?")) {
                ps.setString(1, name);
                ps.setString(2, actor);
                ps.setObject(3, Sql.ts(at));
                ps.setString(4, slotId);
                ps.executeUpdate();
            }
            return new ActivationOutcome(ACTIVATED, slotId, read(c, slotId).orElseThrow());
        });
    }

    @Override
    public ActivationOutcome rename(String slotId, String name) {
        return db.inTransaction("rename entity", c -> {
            Optional<EntityRecord> before = lockSlot(c, slotId);
            if (before.isEmpty()) return new ActivationOutcome(SLOT_UNKNOWN, slotId, null);
            EntityRecord r = before.get();
            if (!r.status().activated()) return new ActivationOutcome(NOT_ACTIVATED, slotId, r);
            if (name.equals(r.displayName())) return new ActivationOutcome(UNCHANGED, slotId, r);
            if (nameTaken(c, r.tenantId(), slotId, name)) return new ActivationOutcome(NAME_TAKEN, slotId, r);
            try (PreparedStatement ps = c.prepareStatement(
                    "update idn_entity set display_name = ?, updated_at = now() where slot_id = ?")) {
                ps.setString(1, name);
                ps.setString(2, slotId);
                ps.executeUpdate();
            }
            return new ActivationOutcome(RENAMED, slotId, read(c, slotId).orElseThrow());
        });
    }

    /** Locks the slot's customer row, then the slot. Empty when the slot is not stored. */
    private static Optional<EntityRecord> lockSlot(Connection c, String slotId) throws SQLException {
        Optional<EntityRecord> r = read(c, slotId);
        if (r.isEmpty()) return r;
        lockCustomer(c, r.get().tenantId());
        try (PreparedStatement ps = c.prepareStatement(
                "select " + COLUMNS + " from idn_entity where slot_id = ? for update")) {
            ps.setString(1, slotId);
            return one(ps);
        }
    }

    private static void lockCustomer(Connection c, String tenantId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "select 1 from idn_customer where tenant_id = ? for update")) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("customer " + tenantId + " is not stored");
            }
        }
    }

    private static boolean nameTaken(Connection c, String tenantId, String slotId, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select 1 from idn_entity where tenant_id = ? "
                + "and slot_id <> ? and lower(display_name) = lower(?)")) {
            ps.setString(1, tenantId);
            ps.setString(2, slotId);
            ps.setString(3, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static Optional<EntityRecord> read(Connection c, String slotId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select " + COLUMNS + " from idn_entity where slot_id = ?")) {
            ps.setString(1, slotId);
            return one(ps);
        }
    }

    private static List<EntityRecord> readEntities(Connection c, String tenantId, boolean lock) throws SQLException {
        List<EntityRecord> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("select " + COLUMNS + " from idn_entity where tenant_id = ? "
                + "order by slot_id" + (lock ? " for update" : ""))) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(record(rs));
            }
        }
        return out;
    }

    private static Optional<EntityRecord> one(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(record(rs)) : Optional.empty();
        }
    }

    private static EntityRecord record(ResultSet rs) throws SQLException {
        return new EntityRecord(rs.getString("slot_id"), rs.getObject("entity_uuid", UUID.class),
                rs.getString("tenant_id"), EntityStatus.valueOf(rs.getString("status")), rs.getString("display_name"),
                rs.getString("activated_by"), Sql.instant(rs, "activated_at"));
    }
}
