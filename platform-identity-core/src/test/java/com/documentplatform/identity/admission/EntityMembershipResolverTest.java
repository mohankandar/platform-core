package com.documentplatform.identity.admission;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ported case for case from the original OidcTenantMembershipResolverTest. The product interfaces are single-method,
 * so lambdas replace the Mockito mocks; a lambda that checks its key replaces Mockito's argument matching.
 */
class EntityMembershipResolverTest {
    private static final EntityId A = EntityId.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final EntityId B = EntityId.of(UUID.fromString("00000000-0000-0000-0000-000000000002"));
    private static final URI ISSUER = URI.create("https://identity.example.test/realms/shared");
    private static final ConnectionKey KEY = new ConnectionKey("customer-oidc", ISSUER, "product-a-api");
    private static final AdmissionSettings SETTINGS = new AdmissionSettings(true, true, "customer-oidc", "product-a-api");

    private static ActiveEntityCandidates candidates(Set<EntityId> answer) {
        return key -> KEY.equals(key) ? answer : Set.of();
    }

    private static ActiveEntityCandidates activeCandidates() {
        return candidates(Set.of(A, B));
    }

    private static EntityGroupGrants grants(Map<EntityId, Map<String, Set<String>>> answer) {
        return key -> KEY.equals(key) ? answer : Map.of();
    }

    private static EntityMembershipResolver resolver(EntityGroupGrants grants, ActiveEntityCandidates candidates) {
        return new EntityMembershipResolver(grants, candidates, new TestRoleCatalog(), SETTINGS);
    }

    @Test void isolatesRolesEvenWhenIdpGroupsContainMultipleTenants() {
        var policy = Map.of(A, Map.of("tenant-a", Set.of("DOCUMENT_PROCESSOR")),
                B, Map.of("tenant-b", Set.of("TENANT_IT_ADMIN")));
        var resolver = resolver(grants(policy), activeCandidates());
        var onlyA = resolver.authorizedEntities(ISSUER, Set.of("tenant-a"));
        assertEquals(Set.of(A), onlyA.keySet());
        assertEquals(Set.of("DOCUMENT_PROCESSOR"), onlyA.get(A));
        assertThrows(IdentityAdmissionException.class, () -> resolver.select(B, onlyA));
        var both = resolver.authorizedEntities(ISSUER, Set.of("tenant-a", "tenant-b"));
        assertThrows(IdentityAdmissionException.class, () -> resolver.selectOnlyIfUnambiguous(both));
        assertEquals(Set.of("TENANT_IT_ADMIN"), both.get(B));
    }

    @Test void invalidGrantPolicyIsNotSilentlyTreatedAsNonMembership() {
        var resolver = resolver(grants(Map.of(A, Map.of("tenant-a", Set.of("PLATFORM_OPERATIONS_ADMIN")))), activeCandidates());
        assertThrows(IdentityAdmissionException.class, () -> resolver.authorizedEntities(ISSUER, Set.of("tenant-a")));
    }

    @Test void inactiveTenantCannotBeAdmittedEvenWithGroupGrant() {
        var resolver = resolver(grants(Map.of(B, Map.of("tenant-b", Set.of("DOCUMENT_PROCESSOR")))), candidates(Set.of(A)));
        assertTrue(resolver.authorizedEntities(ISSUER, Set.of("tenant-b")).isEmpty());
    }

    @Test void tenantGrantsFromDifferentIssuerCannotAuthorizeThisToken() {
        List<ConnectionKey> asked = new ArrayList<>();
        EntityGroupGrants recording = key -> { asked.add(key); return Map.of(); };
        var resolver = resolver(recording, activeCandidates());
        assertTrue(resolver.authorizedEntities(ISSUER, Set.of("tenant-a", "tenant-b")).isEmpty());
        assertEquals(List.of(KEY), asked);
    }

    @Test void noActiveBindingIsNotEnoughEvenWithSignedGroup() {
        var resolver = resolver(grants(Map.of(A, Map.of("tenant-a", Set.of("DOCUMENT_PROCESSOR")))), candidates(Set.of()));
        assertTrue(resolver.authorizedEntities(ISSUER, Set.of("tenant-a")).isEmpty());
    }

    @Test void authenticatedGroupForAnotherTenantNeverElevatesSelectedTenant() {
        var resolver = resolver(grants(Map.of(A, Map.of("tenant-a", Set.of("DOCUMENT_PROCESSOR")),
                B, Map.of("tenant-b", Set.of("TENANT_IT_ADMIN")))), activeCandidates());
        var memberships = resolver.authorizedEntities(ISSUER, Set.of("tenant-a", "tenant-b"));
        assertEquals(Set.of("DOCUMENT_PROCESSOR"), memberships.get(A));
        assertEquals(Set.of("TENANT_IT_ADMIN"), memberships.get(B));
        assertEquals(A, resolver.select(A, memberships));
        assertEquals(B, resolver.select(B, memberships));
    }

    @Test void missingPersistenceResultFailsClosed() {
        var nullGrants = resolver(key -> null, candidates(Set.of(A)));
        assertThrows(IdentityAdmissionException.class, () -> nullGrants.authorizedEntities(ISSUER, Set.of("tenant-a")));
        var nullCandidates = resolver(grants(Map.of(A, Map.of("tenant-a", Set.of("DOCUMENT_PROCESSOR")))), key -> null);
        assertThrows(IdentityAdmissionException.class, () -> nullCandidates.authorizedEntities(ISSUER, Set.of("tenant-a")));
    }

    @Test void noGroupsCannotAuthorizeAnyTenant() {
        var resolver = resolver(grants(Map.of(A, Map.of("tenant-a", Set.of("DOCUMENT_PROCESSOR")))), activeCandidates());
        assertTrue(resolver.authorizedEntities(ISSUER, Set.of()).isEmpty());
        assertThrows(IdentityAdmissionException.class,
                () -> resolver.selectOnlyIfUnambiguous(resolver.authorizedEntities(ISSUER, Set.of())));
    }
}
