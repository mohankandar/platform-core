package com.documentplatform.identity.mapping;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static com.documentplatform.identity.mapping.RoleAliasMappingTest.ROLES;
import static com.documentplatform.identity.mapping.RoleMappingFileTest.FORMAT;
import static com.documentplatform.identity.mapping.RoleMappingFileTest.HEADER;
import static com.documentplatform.identity.mapping.SlotMappingFileTest.S1;
import static com.documentplatform.identity.mapping.SlotMappingFileTest.TARGET_SLOTS;
import static org.junit.jupiter.api.Assertions.*;

/** P028 B5: an entity_id of {@code *} is platform-wide for the customer and product, for platform roles only. */
class PlatformWideRowsTest {

    static RoleMappingFile.Result read(String text) {
        return RoleMappingFile.forSlots(FORMAT, "PRODUCT_A", ROLES, TARGET_SLOTS)
                .read(text.getBytes(StandardCharsets.UTF_8));
    }

    static void refused(String text, int line, MappingError.Code code) {
        RoleMappingFile.Result r = read(text);
        assertFalse(r.valid());
        assertTrue(r.errors().stream().anyMatch(e -> e.code() == code && e.line() == line), r.errors().toString());
    }

    @Test
    void aStarRowGrantsAPlatformRoleBesideEntityRows() {
        RoleMappingFile.Result r = read(HEADER + "*,,ops-team,PLATFORM_ADMIN\n" + S1 + ",,readers,READER\n");
        assertTrue(r.valid(), r.errors().toString());
        MappingRow star = r.rows().get(0);
        assertTrue(star.platformWide());
        assertNull(star.entity());
        assertNull(star.entityName());
        assertEquals(MappingRow.PLATFORM_WIDE, star.slotId());
        assertEquals(new Grant("ops-team", "PLATFORM_ADMIN"), star.grant());
        assertEquals("PRODUCT_A", star.productCode());
        assertFalse(r.rows().get(1).platformWide());
        // A file of * rows only is a valid file too.
        assertTrue(read(HEADER + "*,,ops-team,PLATFORM_ADMIN\n").valid());
    }

    @Test
    void theWrongRoleKindIsRefusedOnEitherKindOfRow() {
        refused(HEADER + "*,,readers,READER\n", 2, MappingError.Code.ENTITY_ROLE_PLATFORM_WIDE);
        refused(HEADER + "*,,readers,Reviewer\n", 2, MappingError.Code.ENTITY_ROLE_PLATFORM_WIDE); // an alias of READER
        refused(HEADER + S1 + ",,ops-team,PLATFORM_ADMIN\n", 2, MappingError.Code.PLATFORM_ROLE_ON_ENTITY);
        refused(HEADER + "*,,ops-team,NO_SUCH_ROLE\n", 2, MappingError.Code.UNKNOWN_ROLE);
        refused(HEADER + "*,,ops-team,platform_admin\n", 2, MappingError.Code.ROLE_NOT_WRITTEN_CANONICALLY);
    }

    @Test
    void aStarRowNamesNoEntityAndIsNotRepeated() {
        refused(HEADER + "*,Everything,ops-team,PLATFORM_ADMIN\n", 2, MappingError.Code.ENTITY_NAME_MISMATCH);
        refused(HEADER + "*,,ops-team,PLATFORM_ADMIN\n*,,ops-team,PLATFORM_ADMIN\n", 3,
                MappingError.Code.DUPLICATE_ROW);
        refused(HEADER + "*,,ops-team ,PLATFORM_ADMIN\n", 2, MappingError.Code.INVISIBLE_CHARACTER);
    }

    @Test
    void filesReadWithoutAProductOrSlotsDoNotTakeStarRows() {
        RoleMappingFile.Result r = new RoleMappingFile(FORMAT, ROLES, id -> java.util.Optional.empty())
                .read((HEADER + "*,,ops-team,PLATFORM_ADMIN\n").getBytes(StandardCharsets.UTF_8));
        assertFalse(r.valid());
        assertTrue(r.rows().isEmpty(), "refused: a * row is only for setup files (slot IDs, one product)");
    }
}
