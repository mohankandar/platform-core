package com.documentplatform.identity.jdbc;

import com.documentplatform.identity.store.ChangeSource;
import com.documentplatform.identity.store.GrantChange;
import com.documentplatform.identity.store.GrantHistory;
import com.documentplatform.identity.store.PlatformGrantChange;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link GrantHistory} on table {@code idn_grant_history}: entity grants, and since identity V4 platform-wide grants
 * (scope PLATFORM). Reads only; the DML-only app user is enough.
 */
public final class PostgresGrantHistory implements GrantHistory {

    private final Db db;

    public PostgresGrantHistory(DataSource ds) {
        this.db = Db.of(ds);
    }

    @Override
    public List<GrantChange> forEntity(UUID entityUuid, Instant from, Instant to) {
        Objects.requireNonNull(entityUuid, "entityUuid");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        return db.read("read grant history", c -> {
            List<GrantChange> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select tenant_id, product_code, slot_id, entity_uuid, "
                    + "issuer_uri, external_group, role_before, role_after, change, change_source, changed_by, "
                    + "changed_at, setup_run_id from idn_grant_history where scope = 'ENTITY' and entity_uuid = ? "
                    + "and changed_at >= ? "
                    + "and changed_at < ? order by changed_at, history_id")) {
                ps.setObject(1, entityUuid);
                ps.setObject(2, Sql.ts(from));
                ps.setObject(3, Sql.ts(to));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new GrantChange(rs.getString("tenant_id"), rs.getString("product_code"),
                                rs.getString("slot_id"), rs.getObject("entity_uuid", UUID.class),
                                URI.create(rs.getString("issuer_uri")), rs.getString("external_group"),
                                rs.getString("role_before"), rs.getString("role_after"),
                                GrantChange.Kind.valueOf(rs.getString("change")),
                                ChangeSource.valueOf(rs.getString("change_source")), rs.getString("changed_by"),
                                Sql.instant(rs, "changed_at"), rs.getString("setup_run_id")));
                    }
                }
            }
            return List.copyOf(out);
        });
    }

    @Override
    public List<PlatformGrantChange> forPlatform(String tenantId, String productCode, Instant from, Instant to) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(productCode, "productCode");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        return db.read("read platform grant history", c -> {
            List<PlatformGrantChange> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select tenant_id, product_code, issuer_uri, "
                    + "external_group, role_before, role_after, change, change_source, changed_by, changed_at, "
                    + "setup_run_id from idn_grant_history where scope = 'PLATFORM' and tenant_id = ? "
                    + "and product_code = ? and changed_at >= ? and changed_at < ? order by changed_at, history_id")) {
                ps.setString(1, tenantId);
                ps.setString(2, productCode);
                ps.setObject(3, Sql.ts(from));
                ps.setObject(4, Sql.ts(to));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new PlatformGrantChange(rs.getString("tenant_id"), rs.getString("product_code"),
                                URI.create(rs.getString("issuer_uri")), rs.getString("external_group"),
                                rs.getString("role_before"), rs.getString("role_after"),
                                GrantChange.Kind.valueOf(rs.getString("change")),
                                ChangeSource.valueOf(rs.getString("change_source")), rs.getString("changed_by"),
                                Sql.instant(rs, "changed_at"), rs.getString("setup_run_id")));
                    }
                }
            }
            return List.copyOf(out);
        });
    }
}
