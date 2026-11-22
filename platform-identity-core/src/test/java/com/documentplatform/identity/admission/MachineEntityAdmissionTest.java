package com.documentplatform.identity.admission;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Ported case for case from the original OidcMachineTenantAdmissionTest. */
class MachineEntityAdmissionTest {
    private final MachineEntityAdmission admission = new MachineEntityAdmission();
    private static final EntityId A = EntityId.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final EntityId B = EntityId.of(UUID.fromString("00000000-0000-0000-0000-000000000002"));

    @Test void selectsOnlyUniqueBoundTenant() {
        assertEquals(A, admission.requireUnique(Set.of(A)));
    }

    @Test void deniesMissingOrAmbiguousBindings() {
        assertThrows(IdentityAdmissionException.class, () -> admission.requireUnique(null));
        assertThrows(IdentityAdmissionException.class, () -> admission.requireUnique(Set.of()));
        assertThrows(IdentityAdmissionException.class, () -> admission.requireUnique(Set.of(A, B)));
        assertThrows(IdentityAdmissionException.class, () -> admission.requireUnique(
                new java.util.HashSet<>(java.util.Collections.singletonList(null))));
    }
}
