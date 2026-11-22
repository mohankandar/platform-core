package com.documentplatform.identity.admission;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** Ported case for case from the original SharedIssuerActivationPreflightTest. */
class SharedIssuerPreflightTest {
    private static final EntityId A = EntityId.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final EntityId B = EntityId.of(UUID.fromString("00000000-0000-0000-0000-000000000002"));
    private final SharedIssuerPreflight check = new SharedIssuerPreflight(new TestRoleCatalog());

    @Test void acceptsOnlyExplicitModeWithActiveAuthorizedTenants() {
        check.requireReady(true, true, Set.of(A, B),
                Map.of(A, Set.of("DOCUMENT_PROCESSOR"), B, Set.of("TENANT_IT_ADMIN")));
    }

    @Test void rejectsInactiveAndUnverifiedTenants() {
        assertThrows(IdentityAdmissionException.class, () ->
                check.requireReady(true, true, Set.of(A), Map.of(B, Set.of("DOCUMENT_PROCESSOR"))));
        assertThrows(IdentityAdmissionException.class, () ->
                check.requireReady(true, true, Set.of(A), Map.of()));
        assertThrows(IdentityAdmissionException.class, () ->
                check.requireReady(false, true, Set.of(A), Map.of(A, Set.of("DOCUMENT_PROCESSOR"))));
        assertThrows(IdentityAdmissionException.class, () ->
                check.requireReady(true, false, Set.of(A), Map.of(A, Set.of("DOCUMENT_PROCESSOR"))));
    }

    @Test void refusesPlatformRoleInTenantMembership() {
        assertThrows(IdentityAdmissionException.class, () ->
                check.requireReady(true, true, Set.of(A), Map.of(A, Set.of("PLATFORM_OPERATIONS_ADMIN"))));
    }
}
