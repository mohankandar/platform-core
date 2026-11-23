package com.documentplatform.identity.jdbc;

import com.documentplatform.identity.push.PushCallerDirectory;
import com.documentplatform.identity.store.EntityStatus;
import com.documentplatform.identity.store.MachineBinding;
import com.documentplatform.identity.store.MachineBindingStatus;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link PushCallerDirectory} on the {@code idn_} tables (plain JDBC, SELECT only): the ACTIVE login connections by
 * product and issuer ({@code idn_login_connection}), and a client's bindings ({@code idn_machine_binding}) with their
 * entity's status ({@code idn_entity}). Values are compared exactly as given. A database error throws
 * {@link IdentityStoreException}; nothing falls back.
 */
public final class PostgresPushCallerDirectory implements PushCallerDirectory {

    private final DataSource ds;

    public PostgresPushCallerDirectory(DataSource ds) {
        this.ds = Objects.requireNonNull(ds, "ds");
    }

    @Override
    public List<ActiveConnection> activeConnections(String productCode, URI issuer) {
        return Sql.run(ds, "read login connections for a push caller", c -> {
            List<ActiveConnection> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select tenant_id, provider_id, issuer_uri, api_audience "
                    + "from idn_login_connection where status = 'ACTIVE' and product_code = ? and issuer_uri = ? "
                    + "order by tenant_id")) {
                ps.setString(1, productCode);
                ps.setString(2, issuer.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new ActiveConnection(rs.getString(1), rs.getString(2), URI.create(rs.getString(3)),
                                rs.getString(4)));
                    }
                }
            }
            return List.copyOf(out);
        });
    }

    @Override
    public boolean hasActiveConnection(String tenantId, String productCode) {
        return Sql.run(ds, "read the login connection of a push caller", c -> {
            try (PreparedStatement ps = c.prepareStatement("select 1 from idn_login_connection where status = 'ACTIVE' "
                    + "and tenant_id = ? and product_code = ?")) {
                ps.setString(1, tenantId);
                ps.setString(2, productCode);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    @Override
    public List<BoundClient> bindings(String productCode, String clientId) {
        return Sql.run(ds, "read client bindings of a push caller", c -> {
            List<BoundClient> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select b.binding_id, b.tenant_id, b.product_code, "
                    + "b.client_id, b.slot_id, b.entity_uuid, b.source_key, b.description, b.status, b.version, "
                    + "e.status from idn_machine_binding b join idn_entity e on e.slot_id = b.slot_id "
                    + "where b.product_code = ? and b.client_id = ? order by b.tenant_id")) {
                ps.setString(1, productCode);
                ps.setString(2, clientId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        MachineBinding b = new MachineBinding(rs.getObject(1, UUID.class), rs.getString(2),
                                rs.getString(3), rs.getString(4), rs.getString(5), rs.getObject(6, UUID.class),
                                rs.getString(7), rs.getString(8), MachineBindingStatus.valueOf(rs.getString(9)),
                                rs.getLong(10));
                        out.add(new BoundClient(b, EntityStatus.valueOf(rs.getString(11))));
                    }
                }
            }
            return List.copyOf(out);
        });
    }
}
