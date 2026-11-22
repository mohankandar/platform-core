package com.documentplatform.identity.inmemory;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.identity.IdentityConnectionStatus;
import com.documentplatform.identity.IdentityProviderType;
import com.documentplatform.identity.admission.EntityId;
import com.documentplatform.identity.admission.MachineEntityBindings;
import com.documentplatform.identity.store.ChangeSource;
import com.documentplatform.identity.store.LoginConnection;
import com.documentplatform.identity.store.MachineBinding;
import com.documentplatform.identity.store.MachineBindingStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.documentplatform.identity.inmemory.InMemoryIdentityStoreTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Client bindings in memory, with the same rules as the Postgres store and {@code PostgresMachineEntityBindings}. */
class InMemoryMachineBindingsTest {

    @Test
    void sameRulesAsPostgres() {
        InMemoryIdentityStore store = new InMemoryIdentityStore(CLOCK);
        store.saveCustomer(TENANT, "Test Customer");
        store.syncSlots(TENANT, List.of(S1, S2));
        store.insert(new LoginConnection(UUID.randomUUID(), TENANT, "PRODUCT_A", "okta", IdentityProviderType.OIDC,
                ISSUER, null, "api://a", null, IdentityConnectionStatus.ACTIVE, 1), ChangeSource.SETUP, "setup");
        MachineEntityBindings machines = store.machineBindings("PRODUCT_A");

        MachineBinding b = store.insertBinding(MachineBinding.create(TENANT, "PRODUCT_A", "client-1", S1, "loan",
                null), ChangeSource.SETUP, "setup");
        assertThrows(IllegalStateException.class, () -> store.insertBinding(MachineBinding.create(TENANT,
                "PRODUCT_A", "client-1", S2, "loan", null), ChangeSource.SETUP, "setup"));
        assertEquals(Set.of(), machines.find(KEY_A, "client-1"), "entity not activated yet");
        store.activate(S1, "Alpha", "admin-1", Instant.parse("2026-11-21T10:00:00Z"));
        assertEquals(Set.of(EntityId.of(EntityUuid.fromSlotId(S1))), machines.find(KEY_A, "client-1"));

        store.updateBinding(b.switchedOff(), 1, ChangeSource.SETUP, "setup").orElseThrow();
        assertEquals(Set.of(), machines.find(KEY_A, "client-1"));
        assertTrue(store.updateBinding(b, 1, ChangeSource.SETUP, "setup").isEmpty(), "stale version");
        assertEquals(List.of(MachineBindingStatus.ACTIVE, MachineBindingStatus.INACTIVE),
                store.bindingHistory(b.bindingId()).stream().map(h -> h.status()).toList());
        assertEquals(1, store.bindings(TENANT, "PRODUCT_A").size());
    }
}
