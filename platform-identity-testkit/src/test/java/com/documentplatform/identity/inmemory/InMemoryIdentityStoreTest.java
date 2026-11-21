package com.documentplatform.identity.inmemory;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.identity.IdentityConnectionStatus;
import com.documentplatform.identity.IdentityProviderType;
import com.documentplatform.identity.admission.ConnectionKey;
import com.documentplatform.identity.admission.EntityId;
import com.documentplatform.identity.mapping.Grant;
import com.documentplatform.identity.store.ChangeSource;
import com.documentplatform.identity.store.EntityStatus;
import com.documentplatform.identity.store.LicensedSlots;
import com.documentplatform.identity.store.LoginConnection;
import com.documentplatform.identity.store.SlotActivation;
import com.documentplatform.identity.store.SlotSync;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.documentplatform.identity.store.ActivationOutcome.Result.*;
import static org.junit.jupiter.api.Assertions.*;

/** The in-memory store follows the same rules as the Postgres one. */
class InMemoryIdentityStoreTest {

    static final String TENANT = "TEN-TEST-0001";
    static final String S1 = "ENT-TEST-0001";
    static final String S2 = "ENT-TEST-0002";
    static final URI ISSUER = URI.create("https://login.example.test");
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-21T10:00:00Z"), ZoneOffset.UTC);
    static final ConnectionKey KEY_A = new ConnectionKey("okta", ISSUER, "api://a");

    private final InMemoryIdentityStore store = new InMemoryIdentityStore(CLOCK);
    private final SlotActivation activation = new SlotActivation(store,
            () -> Optional.of(new LicensedSlots.Current(TENANT, Set.of(S1, S2))), CLOCK);

    private void install() {
        store.saveCustomer(TENANT, "Test Customer");
        store.syncSlots(TENANT, List.of(S1, S2));
        store.insert(connection("PRODUCT_A", "api://a"), ChangeSource.SETUP, "setup");
    }

    private static LoginConnection connection(String product, String audience) {
        return new LoginConnection(UUID.randomUUID(), TENANT, product, "okta", IdentityProviderType.OIDC, ISSUER, null,
                audience, null, IdentityConnectionStatus.ACTIVE, 1);
    }

    @Test
    void activationRules() {
        install();
        assertEquals(ACTIVATED, activation.activate(S1, "Alpha", "admin-1").result());
        assertEquals(ALREADY_ACTIVATED, activation.activate(S1, "Alpha", "admin-1").result());
        assertEquals(NAME_TAKEN, activation.activate(S2, "alpha", "admin-1").result());
        assertEquals(NOT_IN_LICENCE, activation.activate("ENT-OTHER-1", "Other", "admin-1").result());
        assertEquals(NAME_INVALID, activation.activate(S2, " ", "admin-1").result());
        assertEquals(NO_LICENCE, new SlotActivation(store, Optional::empty, CLOCK).activate(S2, "Beta", "admin-1").result());
        assertEquals(EntityUuid.fromSlotId(S1).toString(), store.activated(TENANT).get(S1));
        assertEquals(RENAMED, activation.rename(S1, "Alpha Ltd", "admin-1").result());
        assertEquals(NOT_ACTIVATED, activation.rename(S2, "Beta", "admin-1").result());
    }

    @Test
    void notInLicenceAndBack() {
        install();
        activation.activate(S1, "Alpha", "admin-1");
        SlotSync sync = store.syncSlots(TENANT, List.of(S2));
        assertEquals(List.of(S1), sync.notInLicence());
        assertEquals(EntityStatus.NOT_IN_LICENCE, store.entity(S1).orElseThrow().status());
        assertEquals(NOT_IN_LICENCE, store.activate(S1, "x", "a", CLOCK.instant()).result());
        assertEquals(Map.of(), store.activated(TENANT));
        assertEquals(List.of(S1), store.syncSlots(TENANT, List.of(S1, S2)).backInLicence());
        assertEquals(EntityStatus.ACTIVE, store.entity(S1).orElseThrow().status());
    }

    @Test
    void oneConnectionForTwoEntitiesAndGrantsPerProduct() {
        install();
        store.insert(connection("PRODUCT_B", "api://b"), ChangeSource.SETUP, "setup");
        activation.activate(S1, "Alpha", "admin-1");
        activation.activate(S2, "Beta", "admin-1");
        store.add(S1, "PRODUCT_A", ISSUER, List.of(new Grant("staff", "READER")));
        store.add(S1, "PRODUCT_B", ISSUER, List.of(new Grant("staff", "AUDITOR")));
        EntityId e1 = EntityId.of(EntityUuid.fromSlotId(S1)), e2 = EntityId.of(EntityUuid.fromSlotId(S2));

        InMemoryIdentityStore.Lookup a = store.lookup("PRODUCT_A");
        assertEquals(Set.of(e1, e2), a.find(KEY_A));
        assertEquals(Map.of(e1, Map.of("staff", Set.of("READER"))), a.activeGrants(KEY_A));
        assertEquals(Map.of(e1, Map.of("staff", Set.of("AUDITOR"))),
                store.lookup("PRODUCT_B").activeGrants(new ConnectionKey("okta", ISSUER, "api://b")));
        assertEquals(Optional.empty(), a.findActive("okta", ISSUER, "api://a"), "two entities: the person chooses");
        store.setStatus(S2, EntityStatus.SUSPENDED);
        assertEquals(e1.value(), a.findActive("okta", ISSUER, "api://a").orElseThrow().tenantId());
        assertEquals(Set.of(), a.find(new ConnectionKey("okta", ISSUER, "")));

        assertEquals(Set.of(e1), store.machineBindings("PRODUCT_A", client -> Set.of(e1.value(), e2.value()))
                .find(KEY_A, "scanner"));
    }

    @Test
    void connectionHistoryAndOneActive() {
        install();
        LoginConnection active = store.active(TENANT, "PRODUCT_A").orElseThrow();
        assertThrows(IllegalStateException.class,
                () -> store.insert(connection("PRODUCT_A", "api://x"), ChangeSource.APP, "admin-1"));
        assertEquals(2, store.update(active, 1, ChangeSource.APP, "admin-1").orElseThrow().configurationVersion());
        assertEquals(Optional.empty(), store.update(active, 1, ChangeSource.SETUP, "setup"));
        assertEquals(List.of(ChangeSource.SETUP, ChangeSource.APP),
                store.history(active.connectionId()).stream().map(c -> c.source()).toList());
    }
}
