package com.documentplatform.identity.admission;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Ported case for case from the original TenantGroupAuthorizationTest. */
class EntityGroupAuthorizationTest {
    private static final EntityId A = EntityId.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final EntityId B = EntityId.of(UUID.fromString("00000000-0000-0000-0000-000000000002"));
    private final EntityGroupAuthorization policy = new EntityGroupAuthorization(new TestRoleCatalog());

    @Test void rolesAreScopedToSelectedTenant() {
        var mappings = Map.of(
                A, Map.of("tenant-a-processors", Set.of("DOCUMENT_PROCESSOR")),
                B, Map.of("tenant-b-admins", Set.of("TENANT_IT_ADMIN")));
        assertEquals(Set.of("DOCUMENT_PROCESSOR"),
                policy.authorizedRoles(A, Set.of("tenant-a-processors", "tenant-b-admins"), mappings));
        assertEquals(Set.of("TENANT_IT_ADMIN"),
                policy.authorizedRoles(B, Set.of("tenant-a-processors", "tenant-b-admins"), mappings));
        assertThrows(IdentityAdmissionException.class,
                () -> policy.authorizedRoles(B, Set.of("tenant-a-processors"), mappings));
    }

    @Test void tenantGroupCannotGrantPlatformOperations() {
        var mappings = Map.of(A, Map.of("operations-group", Set.of("PLATFORM_OPERATIONS_ADMIN")));
        assertThrows(IdentityAdmissionException.class,
                () -> policy.authorizedRoles(A, Set.of("operations-group"), mappings));
    }

    @Test void rejectsMissingTenantPolicyAndAbsentGroups() {
        var mappings = Map.of(A, Map.of("a-group", Set.of("DOCUMENT_PROCESSOR")));
        assertThrows(IdentityAdmissionException.class, () -> policy.authorizedRoles(B, Set.of("a-group"), mappings));
        assertThrows(IdentityAdmissionException.class, () -> policy.authorizedRoles(A, null, mappings));
        assertThrows(IdentityAdmissionException.class, () -> policy.authorizedRoles(A, Set.of(), mappings));
    }
}
