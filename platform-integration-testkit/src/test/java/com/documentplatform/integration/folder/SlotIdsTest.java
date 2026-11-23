package com.documentplatform.integration.folder;

import com.documentplatform.identity.EntityUuid;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** P028 B4: integration's copy of the slot rule gives the same entity UUIDs as identity's EntityUuid. */
class SlotIdsTest {

    @Test
    void theSameUuidsAndTheSameSlotRule() {
        for (String slot : List.of("ENT-0001", "ENT-A", "ENT-ACME-NORTH-42", "ENT-" + "9".repeat(60))) {
            assertTrue(SlotIds.isSlotId(slot));
            assertEquals(EntityUuid.fromSlotId(slot), SlotIds.entityUuid(slot), slot);
        }
        for (String bad : List.of("ent-0001", "ENT-", "TEN-0001", "ENT-" + "9".repeat(61), "ENT-a")) {
            assertEquals(EntityUuid.isSlotId(bad), SlotIds.isSlotId(bad), bad);
            assertFalse(SlotIds.isSlotId(bad));
            assertThrows(IllegalArgumentException.class, () -> SlotIds.entityUuid(bad));
        }
    }
}
