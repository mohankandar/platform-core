package com.documentplatform.identity.admission;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Ported case for case from the original OidcTenantSelectionTest. */
class EntitySelectionTest {
    private final EntitySelection selector = new EntitySelection();
    private static final EntityId A = EntityId.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final EntityId B = EntityId.of(UUID.fromString("00000000-0000-0000-0000-000000000002"));

    @Test void singleAuthorizedTenantDoesNotRequireSelection() {
        assertEquals(A, selector.select(null, Map.of(A, Set.of("DOCUMENT_PROCESSOR"))));
    }

    @Test void multipleMembershipsRequireExplicitChoice() {
        var grants = Map.of(A, Set.of("DOCUMENT_PROCESSOR"), B, Set.of("TENANT_IT_ADMIN"));
        assertThrows(IdentityAdmissionException.class, () -> selector.select(null, grants));
        assertEquals(B, selector.select(B.value().toString(), grants));
    }

    @Test void fabricatedOrMalformedTenantNeverGrantsAccess() {
        var onlyA = Map.of(A, Set.of("DOCUMENT_PROCESSOR"));
        assertThrows(IdentityAdmissionException.class, () -> selector.select(B.value().toString(), onlyA));
        assertThrows(IdentityAdmissionException.class, () -> selector.select("not-a-uuid", onlyA));
        assertThrows(IdentityAdmissionException.class, () -> selector.select(null, Map.of()));
    }
}
