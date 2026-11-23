package com.documentplatform.identity.jdbc;

import com.documentplatform.identity.OidcConnectionBinding;
import com.documentplatform.identity.OidcConnectionBindingResolver;
import com.documentplatform.identity.admission.ActiveEntityCandidates;
import com.documentplatform.identity.admission.ConnectionKey;
import com.documentplatform.identity.admission.EntityGroupGrants;
import com.documentplatform.identity.admission.EntityId;
import com.documentplatform.identity.admission.PlatformGroupGrants;
import com.documentplatform.identity.store.LoginConnection;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The sign-in lookups of platform-identity-core for one product, on the {@code idn_} tables. A customer's ACTIVE login
 * connection for the product is shared by all its entities, so every ACTIVE entity of that customer is a candidate.
 *
 * <ul>
 *   <li>{@link ActiveEntityCandidates}: the ACTIVE entities of every customer with an ACTIVE connection for this
 *       product that matches provider ID, issuer and audience exactly.</li>
 *   <li>{@link EntityGroupGrants}: those entities' grants for this product recorded for this issuer.</li>
 *   <li>{@link PlatformGroupGrants}: the customer's platform-wide grants for this product and issuer (identity V4).</li>
 *   <li>{@link OidcConnectionBindingResolver}: a binding only when exactly one connection matches and its customer has
 *       exactly one ACTIVE entity (the binding names that entity). Otherwise empty: the person chooses an entity
 *       through {@code EntityMembershipResolver}, which uses the two lookups above. Fails closed on ambiguity.</li>
 * </ul>
 * Values are compared exactly as given; blank provider ID or audience gives no result. When the tables cannot be read,
 * the candidates and grants lookups return null (the library then refuses) and the resolver throws.
 */
public final class PostgresIdentityLookup implements ActiveEntityCandidates, EntityGroupGrants, PlatformGroupGrants,
        OidcConnectionBindingResolver {

    private static final System.Logger LOG = System.getLogger(PostgresIdentityLookup.class.getName());

    /** The ACTIVE connections for the key, joined to the customer's ACTIVE entities. */
    private static final String MATCH = "from idn_login_connection c join idn_entity e on e.tenant_id = c.tenant_id "
            + "and e.status = 'ACTIVE' where c.status = 'ACTIVE' and c.product_code = ? and c.provider_id = ? "
            + "and c.issuer_uri = ? and c.api_audience = ?";

    private final DataSource ds;
    private final String productCode;

    public PostgresIdentityLookup(DataSource ds, String productCode) {
        this.ds = Objects.requireNonNull(ds, "ds");
        this.productCode = LoginConnection.requireProductCode(productCode);
    }

    @Override
    public Set<EntityId> find(ConnectionKey key) {
        if (blank(key)) return Set.of();
        try {
            return Sql.run(ds, "read entity candidates", c -> {
                Set<EntityId> out = new LinkedHashSet<>();
                try (PreparedStatement ps = c.prepareStatement("select e.entity_uuid " + MATCH + " order by e.slot_id")) {
                    bind(ps, key);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) out.add(EntityId.of(rs.getObject(1, UUID.class)));
                    }
                }
                return Set.copyOf(out);
            });
        } catch (IdentityStoreException e) {
            LOG.log(System.Logger.Level.ERROR, "Entity candidates could not be read; sign-in is refused: {0}",
                    e.getMessage());
            return null;
        }
    }

    @Override
    public Map<EntityId, Map<String, Set<String>>> activeGrants(ConnectionKey key) {
        if (blank(key)) return Map.of();
        try {
            return Sql.run(ds, "read group grants", c -> {
                Map<EntityId, Map<String, Set<String>>> out = new LinkedHashMap<>();
                try (PreparedStatement ps = c.prepareStatement("select e.entity_uuid, g.external_group, g.role "
                        + "from idn_login_connection c join idn_entity e on e.tenant_id = c.tenant_id "
                        + "and e.status = 'ACTIVE' join idn_group_grant g on g.slot_id = e.slot_id "
                        + "and g.product_code = c.product_code and g.issuer_uri = c.issuer_uri "
                        + "where c.status = 'ACTIVE' and c.product_code = ? and c.provider_id = ? and c.issuer_uri = ? "
                        + "and c.api_audience = ? order by e.slot_id, g.external_group, g.role")) {
                    bind(ps, key);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.computeIfAbsent(EntityId.of(rs.getObject(1, UUID.class)), e -> new LinkedHashMap<>())
                                    .computeIfAbsent(rs.getString(2), g -> new LinkedHashSet<>())
                                    .add(rs.getString(3));
                        }
                    }
                }
                Map<EntityId, Map<String, Set<String>>> copy = new LinkedHashMap<>();
                out.forEach((entity, groups) -> {
                    Map<String, Set<String>> g = new LinkedHashMap<>();
                    groups.forEach((group, roles) -> g.put(group, Set.copyOf(roles)));
                    copy.put(entity, Map.copyOf(g));
                });
                return Map.copyOf(copy);
            });
        } catch (IdentityStoreException e) {
            LOG.log(System.Logger.Level.ERROR, "Group grants could not be read; sign-in is refused: {0}",
                    e.getMessage());
            return null;
        }
    }

    @Override
    public Map<String, Set<String>> activePlatformGrants(ConnectionKey key) {
        if (blank(key)) return Map.of();
        try {
            return Sql.run(ds, "read platform grants", c -> {
                Map<String, Map<String, Set<String>>> byTenant = new LinkedHashMap<>();
                try (PreparedStatement ps = c.prepareStatement("select g.tenant_id, g.external_group, g.role "
                        + "from idn_login_connection c join idn_platform_grant g on g.tenant_id = c.tenant_id "
                        + "and g.product_code = c.product_code and g.issuer_uri = c.issuer_uri "
                        + "where c.status = 'ACTIVE' and c.product_code = ? and c.provider_id = ? and c.issuer_uri = ? "
                        + "and c.api_audience = ? order by g.tenant_id, g.external_group, g.role")) {
                    bind(ps, key);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            byTenant.computeIfAbsent(rs.getString(1), t -> new LinkedHashMap<>())
                                    .computeIfAbsent(rs.getString(2), g -> new LinkedHashSet<>()).add(rs.getString(3));
                        }
                    }
                }
                if (byTenant.size() > 1) {
                    LOG.log(System.Logger.Level.ERROR, "Platform grants of {0} customers match one login connection; "
                            + "platform roles are refused", byTenant.size());
                    return null;
                }
                Map<String, Set<String>> out = new LinkedHashMap<>();
                byTenant.values().forEach(m -> m.forEach((g, r) -> out.put(g, Set.copyOf(r))));
                return Map.copyOf(out);
            });
        } catch (IdentityStoreException e) {
            LOG.log(System.Logger.Level.ERROR, "Platform grants could not be read; platform roles are refused: {0}",
                    e.getMessage());
            return null;
        }
    }

    @Override
    public Optional<OidcConnectionBinding> findActive(String providerId, URI issuer, String audience) {
        ConnectionKey key = new ConnectionKey(providerId, issuer, audience);
        if (issuer == null || blank(key)) return Optional.empty();
        record Row(UUID connectionId, String tenantId, UUID entity, long version) {
        }
        List<Row> rows = Sql.run(ds, "read login connection", c -> {
            List<Row> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select c.connection_id, c.tenant_id, e.entity_uuid, "
                    + "c.configuration_version " + MATCH)) {
                bind(ps, key);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                                rs.getLong(4)));
                    }
                }
            }
            return out;
        });
        if (rows.size() != 1) {
            if (rows.size() > 1) {
                LOG.log(System.Logger.Level.DEBUG, "No single entity for this login connection ({0} candidates); "
                        + "the person chooses an entity", rows.size());
            }
            return Optional.empty();
        }
        Row r = rows.get(0);
        return Optional.of(new OidcConnectionBinding(r.connectionId(), r.entity(), providerId, issuer, audience,
                r.version()));
    }

    private void bind(PreparedStatement ps, ConnectionKey key) throws java.sql.SQLException {
        ps.setString(1, productCode);
        ps.setString(2, key.providerId());
        ps.setString(3, key.issuer().toString());
        ps.setString(4, key.audience());
    }

    private static boolean blank(ConnectionKey key) {
        return key == null || key.issuer() == null || key.providerId() == null || key.providerId().isBlank()
                || key.audience() == null || key.audience().isBlank();
    }
}
