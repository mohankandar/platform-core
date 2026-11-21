package com.documentplatform.identity;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EntityUuidTest {

    @Test
    void theUuidIsPinnedAndTheSameEveryTime() {
        // Computed outside Java (PowerShell SHA-256 of "platform-entity|<slot>", version 5 and variant bits set).
        // If this test fails, every app entity key in every installation would change: never "fix" the expectation.
        assertEquals(UUID.fromString("067c6a40-cc3e-5b9d-90bc-2f5de37c1056"), EntityUuid.fromSlotId("ENT-TEST-0001"));
        assertEquals(UUID.fromString("354e66f7-3676-509c-80cf-02dbeb124ac6"), EntityUuid.fromSlotId("ENT-TEST-0002"));
        for (int i = 0; i < 3; i++) {
            assertEquals(EntityUuid.fromSlotId("ENT-7K3M-9QX2-H4BD-0RWE"), EntityUuid.fromSlotId("ENT-7K3M-9QX2-H4BD-0RWE"));
        }
    }

    @Test
    void itIsANameBasedUuidAndDiffersPerSlot() {
        UUID u = EntityUuid.fromSlotId("ENT-TEST-0001");
        assertEquals(5, u.version());
        assertEquals(2, u.variant());
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertTrue(seen.add(EntityUuid.fromSlotId("ENT-" + i)));
        }
        // The same hash as StableIdentityId ("StableIdentityId-style", setup.md decision 2). An actor ID would need the
        // issuer "platform-entity", which is not an issuer address, so the two never meet.
        assertEquals(EntityUuid.fromSlotId("ENT-TEST-0001"),
                StableIdentityId.fromIssuerAndSubject("platform-entity", "ENT-TEST-0001"));
    }

    @Test
    void onlySlotIdsAreAccepted() {
        for (String bad : new String[]{null, "", "ENT-", "ent-1", "TEN-1", "ENT-1 ", " ENT-1", "ENT-a", "ENT-" + "A".repeat(61)}) {
            assertThrows(IllegalArgumentException.class, () -> EntityUuid.fromSlotId(bad), String.valueOf(bad));
            assertFalse(EntityUuid.isSlotId(bad));
        }
        assertTrue(EntityUuid.isSlotId("ENT-" + "A".repeat(60)));
        assertTrue(EntityUuid.isTenantId("TEN-TEST-0001"));
        assertFalse(EntityUuid.isTenantId("ENT-TEST-0001"));
    }
}
