package com.documentplatform.identity.jdbc;

import com.documentplatform.identity.store.LoginConnection;
import com.documentplatform.identity.store.MachineBinding;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The built-in {@link MachineClients} for one product, over {@code idn_machine_binding} (loaded by the setup command
 * from the client-binding file): the entity of the client's ACTIVE binding. {@link PostgresMachineEntityBindings} then
 * keeps it only while the entity is ACTIVE and the customer's login connection for the key is ACTIVE. An app with its
 * own integration table can still supply its own {@code MachineClients}.
 */
public final class PostgresMachineClients implements MachineClients {

    private static final System.Logger LOG = System.getLogger(PostgresMachineClients.class.getName());

    private final PostgresMachineBindingStore store;
    private final String productCode;

    public PostgresMachineClients(DataSource ds, String productCode) {
        this.store = new PostgresMachineBindingStore(Objects.requireNonNull(ds, "ds"));
        this.productCode = LoginConnection.requireProductCode(productCode);
    }

    /** @return the entity of the client's ACTIVE binding (none or one); null when the table could not be read */
    @Override
    public Set<UUID> entitiesWithEnabledClient(String clientPrincipal) {
        if (clientPrincipal == null || clientPrincipal.isBlank()) return Set.of();
        // P045, SEC-16: a token principal in certificate form never reaches a certificate binding.
        if (com.documentplatform.identity.push.PushCaller.isFingerprint(clientPrincipal)) return Set.of();
        try {
            return store.activeBinding(productCode, clientPrincipal).map(b -> Set.of(b.entityUuid())).orElse(Set.of());
        } catch (IdentityStoreException e) {
            LOG.log(System.Logger.Level.WARNING, "Client bindings of {0} could not be read: {1}", productCode,
                    e.getMessage());
            return null;
        }
    }

    /** The client's ACTIVE binding, for its source key (the app checks the entity through the admission first). */
    public Optional<MachineBinding> activeBinding(String clientPrincipal) {
        if (clientPrincipal == null || clientPrincipal.isBlank()) return Optional.empty();
        return store.activeBinding(productCode, clientPrincipal);
    }
}
