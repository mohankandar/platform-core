package com.documentplatform.identity.jdbc;

import com.documentplatform.identity.admission.ActiveEntityCandidates;
import com.documentplatform.identity.admission.ConnectionKey;
import com.documentplatform.identity.admission.EntityId;
import com.documentplatform.identity.admission.MachineEntityBindings;

import javax.sql.DataSource;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * {@link MachineEntityBindings} for one product: the client's bindings ({@link MachineClients}: built in over
 * {@code idn_machine_binding}, or the app's own enabled machine integrations), kept only where the entity is ACTIVE and its customer has the ACTIVE login connection for
 * the key. Returns null when either side cannot be read (the library then refuses).
 */
public final class PostgresMachineEntityBindings implements MachineEntityBindings {

    private final ActiveEntityCandidates candidates;
    private final MachineClients clients;

    /** Over the built-in client bindings ({@code idn_machine_binding}, loaded by the setup command). */
    public PostgresMachineEntityBindings(DataSource ds, String productCode) {
        this(ds, productCode, new PostgresMachineClients(ds, productCode));
    }

    /** Over the app's own integration table. */
    public PostgresMachineEntityBindings(DataSource ds, String productCode, MachineClients clients) {
        this.candidates = new PostgresIdentityLookup(ds, productCode);
        this.clients = Objects.requireNonNull(clients, "clients");
    }

    @Override
    public Set<EntityId> find(ConnectionKey key, String clientPrincipal) {
        if (clientPrincipal == null || clientPrincipal.isBlank()) return Set.of();
        Set<EntityId> active = candidates.find(key);
        if (active == null) return null;
        if (active.isEmpty()) return Set.of();
        Set<UUID> bound = clients.entitiesWithEnabledClient(clientPrincipal);
        if (bound == null) return null;
        Set<EntityId> out = new LinkedHashSet<>();
        for (UUID entity : bound) {
            if (entity != null && active.contains(EntityId.of(entity))) out.add(EntityId.of(entity));
        }
        return Set.copyOf(out);
    }
}
