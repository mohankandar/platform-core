package com.documentplatform.identity.admission;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Ported case for case from the original TrustedTenantAdmissionTest. */
class TrustedEntityAdmissionTest {
    private static final EntityId A = EntityId.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final EntityId B = EntityId.of(UUID.fromString("00000000-0000-0000-0000-000000000002"));

    @Test void deniesUntrustedTenant() {
        TrustedEntityAdmission admission = new TrustedEntityAdmission();
        assertEquals(A, admission.requireAuthorized(A, Set.of(A)));
        assertThrows(IdentityAdmissionException.class, () -> admission.requireAuthorized(B, Set.of(A)));
        assertThrows(IdentityAdmissionException.class, () -> admission.requireAuthorized(A, Set.of()));
    }

    @Test void ambiguousCandidatesFailClosed() {
        TrustedEntityAdmission admission = new TrustedEntityAdmission();
        assertThrows(IdentityAdmissionException.class, () -> admission.requireUnambiguous(Set.of(A, B)));
        assertEquals(B, admission.requireAuthorized(B, Set.of(A, B)));
    }
}
