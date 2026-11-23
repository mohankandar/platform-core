package com.documentplatform.identity.jdbc;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.identity.mapping.Grant;
import com.documentplatform.identity.store.GrantAuthor;
import com.documentplatform.identity.store.GrantStep;
import com.documentplatform.identity.store.GroupGrantStore;
import com.documentplatform.identity.store.LoginConnection;
import com.documentplatform.identity.store.PlatformGrantStore;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * {@link GroupGrantStore} on table {@code idn_group_grant} and, since identity V4, {@link PlatformGrantStore} on
 * {@code idn_platform_grant} (platform-wide grants). Every change writes the grant history
 * ({@code idn_grant_history}) in the same transaction. DML only.
 */
public final class PostgresGroupGrantStore implements GroupGrantStore, PlatformGrantStore {

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
        return change(slotId, productCode, issuer, grants, List.of(), GrantAuthor.app(GrantAuthor.UNNAMED_APP_USER));
    }

    @Override
    public int remove(String slotId, String productCode, URI issuer, Collection<Grant> grants) {
        return change(slotId, productCode, issuer, List.of(), grants, GrantAuthor.app(GrantAuthor.UNNAMED_APP_USER));
    }

    @Override
    public int change(String slotId, String productCode, URI issuer, Collection<Grant> add, Collection<Grant> remove,
                      GrantAuthor author) {
        check(slotId, productCode, issuer, add);
        Objects.requireNonNull(remove, "remove");
        Objects.requireNonNull(author, "author");
        if (add.isEmpty() && remove.isEmpty()) return 0;
        return db.inTransaction("change group grants", c -> {
            Slot slot = slot(c, slotId);
            List<Grant> removed = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("delete from idn_group_grant where slot_id = ? "
                    + "and product_code = ? and issuer_uri = ? and external_group = ? and role = ?")) {
                for (Grant g : new LinkedHashSet<>(remove)) {
                    ps.setString(1, slotId);
                    ps.setString(2, productCode);
                    ps.setString(3, issuer.toString());
                    ps.setString(4, g.group());
                    ps.setString(5, g.role());
                    if (ps.executeUpdate() > 0) removed.add(g);
                }
            }
            List<Grant> added = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("insert into idn_group_grant (slot_id, product_code, "
                    + "issuer_uri, external_group, role) values (?, ?, ?, ?, ?) on conflict do nothing")) {
                for (Grant g : new LinkedHashSet<>(add)) {
                    ps.setString(1, slotId);
                    ps.setString(2, productCode);
                    ps.setString(3, issuer.toString());
                    ps.setString(4, g.group());
                    ps.setString(5, g.role());
                    if (ps.executeUpdate() > 0) added.add(g);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("insert into idn_grant_history (tenant_id, product_code, "
                    + "slot_id, entity_uuid, issuer_uri, external_group, role_before, role_after, change, "
                    + "change_source, changed_by, setup_run_id) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (GrantStep step : GrantStep.of(added, removed)) {
                    ps.setString(1, slot.tenantId);
                    ps.setString(2, productCode);
                    ps.setString(3, slotId);
                    ps.setObject(4, slot.entityUuid);
                    ps.setString(5, issuer.toString());
                    ps.setString(6, step.group());
                    ps.setString(7, step.roleBefore());
                    ps.setString(8, step.roleAfter());
                    ps.setString(9, step.kind().name());
                    ps.setString(10, author.source().name());
                    ps.setString(11, author.changedBy());
                    ps.setString(12, author.setupRunId());
                    ps.executeUpdate();
                }
            }
            return added.size() + removed.size();
        });
    }

    private record Slot(String tenantId, UUID entityUuid) {
    }

    private static void check(String slotId, String productCode, URI issuer, Collection<Grant> grants) {
        EntityUuid.requireSlotId(slotId);
        LoginConnection.requireProductCode(productCode);
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(grants, "grants");
    }

    private static Slot slot(Connection c, String slotId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select tenant_id, entity_uuid from idn_entity where slot_id = ?")) {
            ps.setString(1, slotId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("slot " + slotId + " is not stored");
                return new Slot(rs.getString(1), rs.getObject(2, UUID.class));
            }
        }
    }

    // ---- platform-wide grants (identity V4)

    @Override
    public Set<Grant> platformGrants(String tenantId, String productCode, URI issuer) {
        EntityUuid.requireTenantId(tenantId);
        LoginConnection.requireProductCode(productCode);
        Objects.requireNonNull(issuer, "issuer");
        return db.read("read platform grants", c -> {
            Set<Grant> out = new LinkedHashSet<>();
            try (PreparedStatement ps = c.prepareStatement("select external_group, role from idn_platform_grant "
                    + "where tenant_id = ? and product_code = ? and issuer_uri = ? order by external_group, role")) {
                ps.setString(1, tenantId);
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
    public int changePlatform(String tenantId, String productCode, URI issuer, Collection<Grant> add,
                              Collection<Grant> remove, GrantAuthor author) {
        EntityUuid.requireTenantId(tenantId);
        LoginConnection.requireProductCode(productCode);
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(add, "add");
        Objects.requireNonNull(remove, "remove");
        Objects.requireNonNull(author, "author");
        if (add.isEmpty() && remove.isEmpty()) return 0;
        return db.inTransaction("change platform grants", c -> {
            try (PreparedStatement ps = c.prepareStatement("select 1 from idn_customer where tenant_id = ?")) {
                ps.setString(1, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new IllegalStateException("customer " + tenantId + " is not stored");
                }
            }
            List<Grant> removed = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("delete from idn_platform_grant where tenant_id = ? "
                    + "and product_code = ? and issuer_uri = ? and external_group = ? and role = ?")) {
                for (Grant g : new LinkedHashSet<>(remove)) {
                    ps.setString(1, tenantId);
                    ps.setString(2, productCode);
                    ps.setString(3, issuer.toString());
                    ps.setString(4, g.group());
                    ps.setString(5, g.role());
                    if (ps.executeUpdate() > 0) removed.add(g);
                }
            }
            List<Grant> added = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("insert into idn_platform_grant (tenant_id, product_code, "
                    + "issuer_uri, external_group, role) values (?, ?, ?, ?, ?) on conflict do nothing")) {
                for (Grant g : new LinkedHashSet<>(add)) {
                    ps.setString(1, tenantId);
                    ps.setString(2, productCode);
                    ps.setString(3, issuer.toString());
                    ps.setString(4, g.group());
                    ps.setString(5, g.role());
                    if (ps.executeUpdate() > 0) added.add(g);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("insert into idn_grant_history (tenant_id, product_code, "
                    + "scope, issuer_uri, external_group, role_before, role_after, change, change_source, changed_by, "
                    + "setup_run_id) values (?, ?, 'PLATFORM', ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (GrantStep step : GrantStep.of(added, removed)) {
                    ps.setString(1, tenantId);
                    ps.setString(2, productCode);
                    ps.setString(3, issuer.toString());
                    ps.setString(4, step.group());
                    ps.setString(5, step.roleBefore());
                    ps.setString(6, step.roleAfter());
                    ps.setString(7, step.kind().name());
                    ps.setString(8, author.source().name());
                    ps.setString(9, author.changedBy());
                    ps.setString(10, author.setupRunId());
                    ps.executeUpdate();
                }
            }
            return added.size() + removed.size();
        });
    }
}
