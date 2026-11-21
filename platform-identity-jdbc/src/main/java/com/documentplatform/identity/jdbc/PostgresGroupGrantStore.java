package com.documentplatform.identity.jdbc;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.identity.mapping.Grant;
import com.documentplatform.identity.store.GroupGrantStore;
import com.documentplatform.identity.store.LoginConnection;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** {@link GroupGrantStore} on table {@code idn_group_grant}. DML only. */
public final class PostgresGroupGrantStore implements GroupGrantStore {

    private final Db db;

    public PostgresGroupGrantStore(DataSource ds) {
        this.db = Db.of(ds);
    }

    private PostgresGroupGrantStore(Db db) {
        this.db = db;
    }

    /** A store on a connection the caller owns (for example the setup command's single transaction). */
    public static PostgresGroupGrantStore onConnection(Connection c) {
        return new PostgresGroupGrantStore(Db.of(c));
    }

    @Override
    public Set<Grant> grants(String slotId, String productCode, URI issuer) {
        return db.read("read group grants", c -> {
            Set<Grant> out = new LinkedHashSet<>();
            try (PreparedStatement ps = c.prepareStatement("select external_group, role from idn_group_grant "
                    + "where slot_id = ? and product_code = ? and issuer_uri = ? order by external_group, role")) {
                ps.setString(1, slotId);
                ps.setString(2, productCode);
                ps.setString(3, issuer.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new Grant(rs.getString(1), rs.getString(2)));
                }
            }
            return Set.copyOf(out);
        });
    }

    @Override
    public Map<String, Set<Grant>> grantsOfCustomer(String tenantId, String productCode, URI issuer) {
        return db.read("read group grants", c -> {
            Map<String, Set<Grant>> out = new TreeMap<>();
            try (PreparedStatement ps = c.prepareStatement("select g.slot_id, g.external_group, g.role "
                    + "from idn_group_grant g join idn_entity e on e.slot_id = g.slot_id "
                    + "where e.tenant_id = ? and g.product_code = ? and g.issuer_uri = ?")) {
                ps.setString(1, tenantId);
                ps.setString(2, productCode);
                ps.setString(3, issuer.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.computeIfAbsent(rs.getString(1), s -> new LinkedHashSet<>())
                                .add(new Grant(rs.getString(2), rs.getString(3)));
                    }
                }
            }
            out.replaceAll((slot, grants) -> Set.copyOf(grants));
            return Map.copyOf(out);
        });
    }

    @Override
    public int add(String slotId, String productCode, URI issuer, Collection<Grant> grants) {
        check(slotId, productCode, issuer, grants);
        return db.inTransaction("add group grants", c -> {
            requireSlot(c, slotId);
            int added = 0;
            try (PreparedStatement ps = c.prepareStatement("insert into idn_group_grant (slot_id, product_code, "
                    + "issuer_uri, external_group, role) values (?, ?, ?, ?, ?) on conflict do nothing")) {
                for (Grant g : grants) {
                    ps.setString(1, slotId);
                    ps.setString(2, productCode);
                    ps.setString(3, issuer.toString());
                    ps.setString(4, g.group());
                    ps.setString(5, g.role());
                    added += ps.executeUpdate();
                }
            }
            return added;
        });
    }

    @Override
    public int remove(String slotId, String productCode, URI issuer, Collection<Grant> grants) {
        check(slotId, productCode, issuer, grants);
        return db.inTransaction("remove group grants", c -> {
            int removed = 0;
            try (PreparedStatement ps = c.prepareStatement("delete from idn_group_grant where slot_id = ? "
                    + "and product_code = ? and issuer_uri = ? and external_group = ? and role = ?")) {
                for (Grant g : grants) {
                    ps.setString(1, slotId);
                    ps.setString(2, productCode);
                    ps.setString(3, issuer.toString());
                    ps.setString(4, g.group());
                    ps.setString(5, g.role());
                    removed += ps.executeUpdate();
                }
            }
            return removed;
        });
    }

    private static void check(String slotId, String productCode, URI issuer, Collection<Grant> grants) {
        EntityUuid.requireSlotId(slotId);
        LoginConnection.requireProductCode(productCode);
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(grants, "grants");
    }

    private static void requireSlot(Connection c, String slotId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select 1 from idn_entity where slot_id = ?")) {
            ps.setString(1, slotId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("slot " + slotId + " is not stored");
            }
        }
    }
}
