package com.documentplatform.identity.push;

import com.documentplatform.identity.admission.ConnectionKey;
import com.documentplatform.identity.admission.EntityId;
import com.documentplatform.identity.admission.MachineSourceBindings;
import com.documentplatform.identity.store.EntityStatus;
import com.documentplatform.identity.store.LoginConnection;
import com.documentplatform.identity.store.MachineBinding;
import com.documentplatform.identity.store.MachineBindingStatus;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** {@link MachineSourceBindings} for one product over a {@link PushCallerDirectory} (the client bindings). */
public final class DirectoryMachineSourceBindings implements MachineSourceBindings {

    private final PushCallerDirectory directory;
    private final String productCode;

    public DirectoryMachineSourceBindings(PushCallerDirectory directory, String productCode) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.productCode = LoginConnection.requireProductCode(productCode);
    }

    @Override
    public Optional<MachineSource> find(ConnectionKey key, String clientPrincipal) {
        if (key == null || key.issuer() == null || blank(key.providerId()) || blank(key.audience())
                || blank(clientPrincipal)) {
            return Optional.empty();
        }
        Set<String> tenants = directory.activeConnections(productCode, key.issuer()).stream()
                .filter(c -> key.providerId().equals(c.providerId()) && key.audience().equals(c.apiAudience()))
                .map(PushCallerDirectory.ActiveConnection::tenantId).collect(Collectors.toSet());
        if (tenants.isEmpty()) return Optional.empty();
        List<MachineBinding> found = directory.bindings(productCode, clientPrincipal).stream()
                .filter(b -> tenants.contains(b.binding().tenantId())
                        && b.binding().status() == MachineBindingStatus.ACTIVE
                        && b.entityStatus() == EntityStatus.ACTIVE)
                .map(PushCallerDirectory.BoundClient::binding).toList();
        if (found.size() != 1) return Optional.empty();
        MachineBinding b = found.get(0);
        return Optional.of(new MachineSource(EntityId.of(b.entityUuid()), b.slotId(), b.sourceKey()));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
