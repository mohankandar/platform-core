package com.documentplatform.identity.mapping;

import com.documentplatform.identity.admission.EntityId;
import com.documentplatform.identity.admission.EntityRoleCatalog;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleMappingFileTest {

    static final String FORMAT = "example-role-mapping/1";
    static final String HEADER = "entity_id,entity_name,group,role\n";
    static final UUID ALPHA = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID BETA = UUID.fromString("22222222-2222-4222-8222-222222222222");
    static final UUID DORMANT = UUID.fromString("33333333-3333-4333-8333-333333333333");
    static final UUID NO_LOGIN = UUID.fromString("44444444-4444-4444-8444-444444444444");

    /** Mapping tests use their own role names; the library knows none. */
    static final EntityRoleCatalog ROLES = new EntityRoleCatalog() {
        private final Set<String> known = Set.of("READER", "EDITOR", "PLATFORM_ADMIN");

        @Override
        public Optional<String> canonicalEntityRole(String configuredName) {
            if (configuredName == null || configuredName.isBlank()) return Optional.empty();
            String normalized = configuredName.trim().toUpperCase(Locale.ROOT);
            return known.contains(normalized) ? Optional.of(normalized) : Optional.empty();
        }

        @Override
        public boolean isPlatformRole(String canonicalRole) {
            return "PLATFORM_ADMIN".equals(canonicalRole);
        }
    };

    static final Map<UUID, MappingTargets.Target> ENTITIES = Map.of(
            ALPHA, new MappingTargets.Target("Alpha Ltd", true, true),
            BETA, new MappingTargets.Target("Beta, Inc.", true, true),
            DORMANT, new MappingTargets.Target("Dormant Co", false, true),
            NO_LOGIN, new MappingTargets.Target("No Login Co", true, false));

    static final MappingTargets TARGETS = entity -> Optional.ofNullable(ENTITIES.get(entity.value()));

    private static RoleMappingFile.Result read(String text) {
        return new RoleMappingFile(FORMAT, ROLES, TARGETS).read(text.getBytes(StandardCharsets.UTF_8));
    }

    private static List<MappingError.Code> codes(RoleMappingFile.Result result) {
        return result.errors().stream().map(MappingError::code).toList();
    }

    private static void refused(String text, int line, MappingError.Code code) {
        RoleMappingFile.Result result = read(text);
        assertFalse(result.valid(), "expected the file to be refused");
        assertTrue(result.rows().isEmpty(), "a refused file returns no rows");
        assertTrue(result.errors().stream().anyMatch(e -> e.code() == code && e.line() == line),
                "expected " + code + " on line " + line + " but got " + result.errors());
    }

    @Test
    void aValidFileGivesItsRowsInOrder() {
        RoleMappingFile.Result result = read("# Format: " + FORMAT + "\n" + HEADER
                + ALPHA + ",Alpha Ltd,alpha-readers,READER\n"
                + ALPHA + ",Alpha Ltd,alpha-editors,EDITOR\n"
                + BETA + ",\"Beta, Inc.\",\"/beta/group, with comma\",READER\n");
        assertTrue(result.valid(), result.errors().toString());
        assertEquals(3, result.rows().size());
        MappingRow last = result.rows().get(2);
        assertEquals(EntityId.of(BETA), last.entity());
        assertEquals("Beta, Inc.", last.entityName());
        assertEquals("/beta/group, with comma", last.group());
        assertEquals("READER", last.role());
        assertEquals(5, last.line());
    }

    @Test
    void spreadsheetSavedFilesAreRead() {
        String text = "\uFEFF" + "entity_id,entity_name,group,role\r\n"
                + ALPHA + ",\"Alpha Ltd\",\"say \"\"hi\"\"\",READER\r\n"
                + "\r\n# a comment\r\n"
                + BETA + ",\"Beta, Inc.\",g,EDITOR\r\n";
        RoleMappingFile.Result result = read(text);
        assertTrue(result.valid(), result.errors().toString());
        assertEquals("say \"hi\"", result.rows().get(0).group());
        assertEquals(5, result.rows().get(1).line());
    }

    @Test
    void noFormatCommentIsAccepted() {
        assertTrue(read(HEADER + ALPHA + ",Alpha Ltd,g,READER\n").valid());
    }

    @Test
    void anotherFormatIsRefused() {
        refused("# Format: example-role-mapping/2\n" + HEADER + ALPHA + ",Alpha Ltd,g,READER\n", 1, MappingError.Code.UNSUPPORTED_FORMAT);
    }

    @Test
    void theFileMustBeUtf8() {
        byte[] latin1 = (HEADER + ALPHA + ",Caf\u00E9,g,READER\n").getBytes(StandardCharsets.ISO_8859_1);
        RoleMappingFile.Result result = new RoleMappingFile(FORMAT, ROLES, TARGETS).read(latin1);
        assertEquals(List.of(MappingError.Code.NOT_UTF8), codes(result));
    }

    @Test
    void theHeaderMustBeExact() {
        refused("entity_id,entity_name,group\n" + ALPHA + ",Alpha Ltd,g\n", 1, MappingError.Code.BAD_HEADER);
        refused("Entity_ID,entity_name,group,role\n", 1, MappingError.Code.BAD_HEADER);
        refused("", 0, MappingError.Code.BAD_HEADER);
    }

    @Test
    void aFileWithNoRowsIsRefused() {
        refused(HEADER, 0, MappingError.Code.NO_ROWS);
        refused(HEADER + "# only a comment\n", 0, MappingError.Code.NO_ROWS);
    }

    @Test
    void columnCountBlanksAndQuotesAreChecked() {
        refused(HEADER + ALPHA + ",Alpha Ltd,g\n", 2, MappingError.Code.WRONG_COLUMN_COUNT);
        refused(HEADER + ALPHA + ",Alpha Ltd,g,READER,extra\n", 2, MappingError.Code.WRONG_COLUMN_COUNT);
        refused(HEADER + ALPHA + ",Alpha Ltd,,READER\n", 2, MappingError.Code.BLANK_VALUE);
        refused(HEADER + ALPHA + ",Alpha Ltd,\"unclosed,READER\n", 2, MappingError.Code.BAD_QUOTES);
        refused(HEADER + ALPHA + ",Alpha Ltd,\"g\"x,READER\n", 2, MappingError.Code.BAD_QUOTES);
        refused(HEADER + ALPHA + ",Alpha Ltd,g\"x,READER\n", 2, MappingError.Code.BAD_QUOTES);
        refused(HEADER + ALPHA + ",Alpha Ltd,\"two\nlines\",READER\n", 2, MappingError.Code.VALUE_SPANS_LINES);
    }

    @Test
    void invisibleCharactersAreRefusedAndNamed() {
        refused(HEADER + ALPHA + ",Alpha Ltd, g,READER\n", 2, MappingError.Code.INVISIBLE_CHARACTER);
        refused(HEADER + ALPHA + ",Alpha Ltd,g ,READER\n", 2, MappingError.Code.INVISIBLE_CHARACTER);
        refused(HEADER + ALPHA + ",Alpha Ltd,g\t,READER\n", 2, MappingError.Code.INVISIBLE_CHARACTER);
        for (String hidden : List.of("\u00A0", "\u2007", "\u202F", "\u200B", "\uFEFF", "\u0007")) {
            refused(HEADER + ALPHA + ",Alpha Ltd,gro" + hidden + "up,READER\n", 2, MappingError.Code.INVISIBLE_CHARACTER);
        }
        MappingError error = read(HEADER + ALPHA + ",Alpha Ltd,gro\u00A0up,READER\n").errors().get(0);
        assertEquals("line 2: group \"gro\\u00A0up\" contains a non-breaking space", error.display());
    }

    @Test
    void spacesInsideAValueAreKept() {
        RoleMappingFile.Result result = read(HEADER + ALPHA + ",Alpha Ltd,Alpha Readers,READER\n");
        assertTrue(result.valid());
        assertEquals("Alpha Readers", result.rows().get(0).group());
    }

    @Test
    void theEntityMustExistBeActiveMatchItsNameAndHaveALoginSystem() {
        refused(HEADER + "not-a-uuid,Alpha Ltd,g,READER\n", 2, MappingError.Code.NOT_AN_ENTITY_ID);
        refused(HEADER + UUID.randomUUID() + ",Alpha Ltd,g,READER\n", 2, MappingError.Code.UNKNOWN_ENTITY);
        refused(HEADER + DORMANT + ",Dormant Co,g,READER\n", 2, MappingError.Code.ENTITY_NOT_ACTIVE);
        refused(HEADER + ALPHA + ",alpha ltd,g,READER\n", 2, MappingError.Code.ENTITY_NAME_MISMATCH);
        refused(HEADER + NO_LOGIN + ",No Login Co,g,READER\n", 2, MappingError.Code.NO_ACTIVE_LOGIN_SYSTEM);
    }

    @Test
    void oneEntityCannotHaveTwoNamesInAFile() {
        Map<UUID, MappingTargets.Target> renamed = new HashMap<>(ENTITIES);
        MappingTargets anyName = entity -> Optional.ofNullable(renamed.get(entity.value()));
        RoleMappingFile.Result result = new RoleMappingFile(FORMAT, ROLES, anyName).read((HEADER
                + ALPHA + ",Alpha Ltd,g,READER\n"
                + ALPHA + ",Alpha Limited,h,READER\n").getBytes(StandardCharsets.UTF_8));
        assertTrue(codes(result).contains(MappingError.Code.CONFLICTING_ENTITY_NAMES));
        assertTrue(result.errors().stream().anyMatch(e -> e.line() == 3 && e.message().contains("line 2")));
    }

    @Test
    void rolesMustBeKnownCanonicalAndNeverThePlatformRole() {
        refused(HEADER + ALPHA + ",Alpha Ltd,g,OWNER\n", 2, MappingError.Code.UNKNOWN_ROLE);
        refused(HEADER + ALPHA + ",Alpha Ltd,g,reader\n", 2, MappingError.Code.ROLE_NOT_WRITTEN_CANONICALLY);
        refused(HEADER + ALPHA + ",Alpha Ltd,g,PLATFORM_ADMIN\n", 2, MappingError.Code.PLATFORM_ROLE);
    }

    @Test
    void groupsMayBeLongUpToTheLimit() {
        String longest = "g".repeat(RoleMappingFile.MAX_GROUP_LENGTH);
        assertTrue(read(HEADER + ALPHA + ",Alpha Ltd," + longest + ",READER\n").valid());
        refused(HEADER + ALPHA + ",Alpha Ltd," + longest + "g,READER\n", 2, MappingError.Code.GROUP_TOO_LONG);
    }

    @Test
    void duplicateRowsAreRefusedButTheSameGroupMayHaveTwoRoles() {
        refused(HEADER + ALPHA + ",Alpha Ltd,g,READER\n" + ALPHA + ",Alpha Ltd,g,READER\n", 3, MappingError.Code.DUPLICATE_ROW);
        assertTrue(read(HEADER + ALPHA + ",Alpha Ltd,g,READER\n" + ALPHA + ",Alpha Ltd,g,EDITOR\n").valid());
        assertTrue(read(HEADER + ALPHA + ",Alpha Ltd,g,READER\n" + BETA + ",\"Beta, Inc.\",g,READER\n").valid());
    }

    @Test
    void groupNamesAreCaseSensitive() {
        assertTrue(read(HEADER + ALPHA + ",Alpha Ltd,Readers,READER\n" + ALPHA + ",Alpha Ltd,readers,READER\n").valid());
    }

    @Test
    void everyErrorIsCollectedNotJustTheFirst() {
        RoleMappingFile.Result result = read(HEADER
                + ALPHA + ",Alpha Ltd,g,OWNER\n"
                + "bad-id,Alpha Ltd,g,READER\n"
                + DORMANT + ",Dormant Co,g ,READER\n"
                + ALPHA + ",Alpha Ltd,ok,READER\n");
        assertEquals(List.of(MappingError.Code.UNKNOWN_ROLE, MappingError.Code.NOT_AN_ENTITY_ID, MappingError.Code.INVISIBLE_CHARACTER), codes(result));
        assertEquals(List.of(2, 3, 4), result.errors().stream().map(MappingError::line).toList());
        assertTrue(result.rows().isEmpty());
    }

    @Test
    void eachEntityIsLookedUpOnce() {
        List<EntityId> asked = new ArrayList<>();
        MappingTargets counting = entity -> {
            asked.add(entity);
            return TARGETS.describe(entity);
        };
        new RoleMappingFile(FORMAT, ROLES, counting).read((HEADER
                + ALPHA + ",Alpha Ltd,a,READER\n" + ALPHA + ",Alpha Ltd,b,READER\n" + ALPHA + ",Alpha Ltd,c,READER\n")
                .getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of(EntityId.of(ALPHA)), asked);
    }

    @Test
    void mappingStringsUseNoProductWords() throws IOException {
        Pattern string = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
        Pattern productWords = Pattern.compile("(?i)\\btenant|platform operations|" + "lexa" + "vault|\\bs" + "dv\\b");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.list(Path.of("src/main/java/com/documentplatform/identity/mapping"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher literal = string.matcher(lines.get(i));
                    while (literal.find()) {
                        if (productWords.matcher(literal.group(1)).find()) offenders.add(file.getFileName() + ":" + (i + 1));
                    }
                }
            }
        }
        assertEquals(List.of(), offenders);
    }
}
