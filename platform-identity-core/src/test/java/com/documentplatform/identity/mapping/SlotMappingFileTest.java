package com.documentplatform.identity.mapping;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.identity.admission.EntityId;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.documentplatform.identity.mapping.RoleMappingFileTest.ALPHA;
import static com.documentplatform.identity.mapping.RoleMappingFileTest.FORMAT;
import static com.documentplatform.identity.mapping.RoleMappingFileTest.HEADER;
import static com.documentplatform.identity.mapping.RoleMappingFileTest.ROLES;
import static com.documentplatform.identity.mapping.RoleMappingFileTest.TARGETS;
import static org.junit.jupiter.api.Assertions.*;

/** Mapping files that name entities by licence slot ID, read for one product. */
class SlotMappingFileTest {

    static final String S1 = "ENT-TEST-0001";
    static final String S2 = "ENT-TEST-0002";
    static final String NAMED = "ENT-TEST-0003";
    static final String DROPPED = "ENT-TEST-0009";

    static final Map<String, SlotTargets.Slot> SLOTS = Map.of(
            S1, new SlotTargets.Slot(null, true),
            S2, new SlotTargets.Slot(null, true),
            NAMED, new SlotTargets.Slot("Gamma GmbH", true),
            DROPPED, new SlotTargets.Slot("Old Co", false));

    static final SlotTargets TARGET_SLOTS = slot -> Optional.ofNullable(SLOTS.get(slot));

    private static RoleMappingFile.Result read(String text) {
        return RoleMappingFile.forSlots(FORMAT, "PRODUCT_A", ROLES, TARGET_SLOTS).read(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void refused(String text, int line, MappingError.Code code) {
        RoleMappingFile.Result result = read(text);
        assertFalse(result.valid(), "expected the file to be refused");
        assertTrue(result.errors().stream().anyMatch(e -> e.code() == code && e.line() == line),
                "expected " + code + " on line " + line + " but got " + result.errors());
    }

    @Test
    void slotIdsAreAcceptedBeforeActivationAndTheNameMayBeEmpty() {
        RoleMappingFile.Result result = read("# Format: " + FORMAT + "\n# Product: PRODUCT_A\n" + HEADER
                + S1 + ",,readers,READER\n"
                + S2 + ",,readers,EDITOR\n"
                + NAMED + ",Gamma GmbH,editors,EDITOR\n");
        assertTrue(result.valid(), result.errors().toString());
        MappingRow first = result.rows().get(0);
        assertEquals(S1, first.slotId());
        assertEquals(EntityId.of(EntityUuid.fromSlotId(S1)), first.entity(), "the entity is the derived UUID");
        assertNull(first.entityName());
        assertEquals("PRODUCT_A", first.productCode());
        assertEquals("Gamma GmbH", result.rows().get(2).entityName());
    }

    @Test
    void slotsMustBeInTheLicenceAndANameMustMatch() {
        refused(HEADER + "ENT-UNKNOWN-1,,g,READER\n", 2, MappingError.Code.UNKNOWN_ENTITY);
        refused(HEADER + DROPPED + ",,g,READER\n", 2, MappingError.Code.SLOT_NOT_IN_LICENCE);
        refused(HEADER + NAMED + ",gamma gmbh,g,READER\n", 2, MappingError.Code.ENTITY_NAME_MISMATCH);
        refused(HEADER + S1 + ",Alpha Ltd,g,READER\n", 2, MappingError.Code.ENTITY_NAME_MISMATCH);
    }

    @Test
    void theSlotOnlyReaderRefusesUuidsAndTheRulesForRolesAndGroupsStillApply() {
        refused(HEADER + ALPHA + ",Alpha Ltd,g,READER\n", 2, MappingError.Code.SLOT_ID_REQUIRED);
        refused(HEADER + "not-an-id,Some Name,g,READER\n", 2, MappingError.Code.NOT_AN_ENTITY_ID);
        refused(HEADER + S1 + ",,g,PLATFORM_ADMIN\n", 2, MappingError.Code.PLATFORM_ROLE_ON_ENTITY);
        refused(HEADER + S1 + ",,g,reader\n", 2, MappingError.Code.ROLE_NOT_WRITTEN_CANONICALLY);
        refused(HEADER + S1 + ",,g ,READER\n", 2, MappingError.Code.INVISIBLE_CHARACTER);
        refused(HEADER + S1 + ",,,READER\n", 2, MappingError.Code.BLANK_VALUE);
        refused(HEADER + S1 + ",,g,READER\n" + S1 + ",,g,READER\n", 3, MappingError.Code.DUPLICATE_ROW);
    }

    @Test
    void aFileForAnotherProductIsRefused() {
        refused("# Product: PRODUCT_B\n" + HEADER + S1 + ",,g,READER\n", 1, MappingError.Code.WRONG_PRODUCT);
        assertTrue(read(HEADER + S1 + ",,g,READER\n").valid(), "no product comment is accepted");
    }

    @Test
    void bothFormsCanBeReadTogetherAndTheOldReaderIsUnchanged() {
        RoleMappingFile both = new RoleMappingFile(FORMAT, "PRODUCT_A", ROLES, TARGETS, TARGET_SLOTS);
        RoleMappingFile.Result result = both.read((HEADER + ALPHA + ",Alpha Ltd,g,READER\n" + S1 + ",,g,READER\n")
                .getBytes(StandardCharsets.UTF_8));
        assertTrue(result.valid(), result.errors().toString());
        assertNull(result.rows().get(0).slotId());
        assertEquals(S1, result.rows().get(1).slotId());

        RoleMappingFile old = new RoleMappingFile(FORMAT, ROLES, TARGETS);
        RoleMappingFile.Result r = old.read((HEADER + S1 + ",Some Name,g,READER\n").getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of(MappingError.Code.NOT_AN_ENTITY_ID), r.errors().stream().map(MappingError::code).toList(),
                "without slot targets a slot ID is not an entity id, as before");
        assertNull(old.read((HEADER + ALPHA + ",Alpha Ltd,g,READER\n").getBytes(StandardCharsets.UTF_8))
                .rows().get(0).productCode());
    }

    @Test
    void eachSlotIsLookedUpOnce() {
        List<String> asked = new ArrayList<>();
        SlotTargets counting = slot -> {
            asked.add(slot);
            return TARGET_SLOTS.describe(slot);
        };
        RoleMappingFile.forSlots(FORMAT, "PRODUCT_A", ROLES, counting).read((HEADER
                + S1 + ",,a,READER\n" + S1 + ",,b,READER\n" + S2 + ",,c,READER\n").getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of(S1, S2), asked);
    }

    @Test
    void aUuidLookingLikeTheDerivedOneIsStillAUuidRow() {
        UUID derived = EntityUuid.fromSlotId(S1);
        RoleMappingFile.Result r = read(HEADER + derived + ",,g,READER\n");
        assertEquals(MappingError.Code.BLANK_VALUE, r.errors().get(0).code(),
                "a UUID row still needs its name; only slot rows may leave it empty");
    }
}
