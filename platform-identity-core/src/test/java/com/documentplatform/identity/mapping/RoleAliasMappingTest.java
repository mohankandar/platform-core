package com.documentplatform.identity.mapping;

import com.documentplatform.identity.admission.EntityRoleCatalog;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.documentplatform.identity.mapping.RoleMappingFileTest.FORMAT;
import static com.documentplatform.identity.mapping.RoleMappingFileTest.HEADER;
import static com.documentplatform.identity.mapping.SlotMappingFileTest.S1;
import static com.documentplatform.identity.mapping.SlotMappingFileTest.TARGET_SLOTS;
import static org.junit.jupiter.api.Assertions.*;

/** P031 B3: a mapping file may write a role alias; it is resolved when read and kept as the role. */
class RoleAliasMappingTest {

    static final Map<String, String> ALIASES = Map.of("Reviewer", "READER", "Author", "EDITOR");

    static final EntityRoleCatalog ROLES = new EntityRoleCatalog() {
        @Override
        public Optional<String> canonicalEntityRole(String name) {
            for (String r : Set.of("READER", "EDITOR", "PLATFORM_ADMIN")) {
                if (r.equalsIgnoreCase(name)) return Optional.of(r);
            }
            return Optional.empty();
        }

        @Override
        public boolean isPlatformRole(String role) {
            return role.equals("PLATFORM_ADMIN");
        }

        @Override
        public Optional<String> aliasTarget(String name) {
            return Optional.ofNullable(ALIASES.get(name));
        }
    };

    static RoleMappingFile.Result read(String text) {
        return RoleMappingFile.forSlots(FORMAT, "PRODUCT_A", ROLES, TARGET_SLOTS).read(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void anAliasIsResolvedToItsRoleAndRemembered() {
        RoleMappingFile.Result r = read(HEADER + S1 + ",,reviewers,Reviewer\n" + S1 + ",,editors,EDITOR\n");
        assertTrue(r.valid(), r.errors().toString());
        MappingRow aliased = r.rows().get(0);
        assertEquals("READER", aliased.role());
        assertEquals("Reviewer", aliased.alias());
        assertEquals(new Grant("reviewers", "READER"), aliased.grant());
        assertNull(r.rows().get(1).alias());
    }

    @Test
    void anAliasAndItsRoleForTheSameGroupAreTheSameRow() {
        RoleMappingFile.Result r = read(HEADER + S1 + ",,g,Reviewer\n" + S1 + ",,g,READER\n");
        assertFalse(r.valid());
        assertEquals(MappingError.Code.DUPLICATE_ROW, r.errors().get(0).code());
    }

    @Test
    void unknownNamesAreRefusedAsBeforeAndAliasesMatchExactly() {
        assertEquals(MappingError.Code.UNKNOWN_ROLE, read(HEADER + S1 + ",,g,Nobody\n").errors().get(0).code());
        assertEquals(MappingError.Code.UNKNOWN_ROLE, read(HEADER + S1 + ",,g,reviewer\n").errors().get(0).code());
    }

    @Test
    void aCatalogWithoutAliasesReadsAsBefore() {
        EntityRoleCatalog plain = new EntityRoleCatalog() {
            @Override
            public Optional<String> canonicalEntityRole(String name) {
                return "READER".equals(name) ? Optional.of(name) : Optional.empty();
            }

            @Override
            public boolean isPlatformRole(String role) {
                return false;
            }
        };
        RoleMappingFile.Result r = RoleMappingFile.forSlots(FORMAT, "PRODUCT_A", plain, TARGET_SLOTS)
                .read((HEADER + S1 + ",,g,READER\n").getBytes(StandardCharsets.UTF_8));
        assertTrue(r.valid());
        assertNull(r.rows().get(0).alias());
    }
}
