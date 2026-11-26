package com.documentplatform.fields;

import com.documentplatform.fields.FieldDefinitionException.Code;
import com.documentplatform.fields.file.FieldListFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P059 part 3 (LV-28): a dropdown's options are a code plus an optional display name, in the order the admin sets.
 * The code stays the stored, matched and searched value; the name is for display only.
 */
class DropdownNamesTest {

    private static FieldSpec.Builder status() {
        return FieldSpec.builder("status", "Status", FieldType.DROPDOWN);
    }

    private static void refused(Code code, Executable call) {
        FieldDefinitionException e = assertThrows(FieldDefinitionException.class, call);
        assertEquals(code, e.code(), e.getMessage());
    }

    @Test
    void optionsKeepTheAdminsOrderWithTheirNames() {
        FieldSpec s = status().namedOptions(new FieldSpec.Option("Z", "Zeta stage"), new FieldSpec.Option("A", null),
                new FieldSpec.Option("M", "Middle")).build();
        assertEquals(List.of("Z", "A", "M"), s.options(), "the codes, in the order given");
        assertEquals(List.of(new FieldSpec.Option("Z", "Zeta stage"), new FieldSpec.Option("A", null),
                new FieldSpec.Option("M", "Middle")), s.namedOptions());
        assertEquals("Zeta stage", s.displayName("Z"));
        assertEquals("A", s.displayName("A"), "an option without a name shows its code");
        assertEquals("A", s.namedOptions().get(1).displayName());
    }

    @Test
    void aCodeOnlyDefinitionReadsAsBefore() {
        FieldSpec old = status().options("Open", "Closed").build();
        assertEquals(List.of("Open", "Closed"), old.options());
        assertEquals(List.of(new FieldSpec.Option("Open", null), new FieldSpec.Option("Closed", null)),
                old.namedOptions());
        assertEquals(old, status().namedOptions(new FieldSpec.Option("Open", null),
                new FieldSpec.Option("Closed", null)).build(), "code-only either way is the same spec");
        assertEquals(List.of(), FieldSpec.builder("notes", "Notes", FieldType.TEXT).build().namedOptions());
    }

    @Test
    void namesAreUniqueWithinAFieldIgnoringCapitalsAndSpaces() {
        refused(Code.FIELD_OPTION_NAME_REPEATED, () -> status().namedOptions(new FieldSpec.Option("A", "Open case"),
                new FieldSpec.Option("B", "open  CASE")).build());
        // the same name in another field is fine
        status().namedOptions(new FieldSpec.Option("A", "Open case")).build();
        FieldSpec.builder("stage", "Stage", FieldType.DROPDOWN).namedOptions(new FieldSpec.Option("A", "Open case"))
                .build();
    }

    @Test
    void namesHaveTheLabelsLimits() {
        String longest = "n".repeat(FieldSpec.MAX_LABEL_LENGTH);
        assertEquals(longest, status().namedOptions(new FieldSpec.Option("A", longest)).build().displayName("A"));
        refused(Code.FIELD_OPTION_NAME_INVALID, () -> status().namedOptions(
                new FieldSpec.Option("A", longest + "n")).build());
        refused(Code.FIELD_OPTION_NAME_INVALID, () -> status().namedOptions(
                new FieldSpec.Option("A", "two\nlines")).build());
        assertEquals("Spaced", status().namedOptions(new FieldSpec.Option("A", "  Spaced ")).build()
                .displayName("A"), "trimmed like a label");
        assertNull(status().namedOptions(new FieldSpec.Option("A", "  ")).build().namedOptions().get(0).name(),
                "a blank name is no name");
        refused(Code.FIELD_OPTION_INVALID, () -> status().namedOptions(new FieldSpec.Option(" ", "Blank code"))
                .build());
    }

    @Test
    void valuesAreMatchedByCodeNeverByName() {
        FieldSpec s = status().namedOptions(new FieldSpec.Option("OPN", "Open"), new FieldSpec.Option("CLS",
                "Closed")).build();
        assertEquals("OPN", FieldValidator.check(s, FieldValue.text("OPN")).valid().normalisedText());
        assertFalse(FieldValidator.check(s, FieldValue.text("Open")).ok(), "the name is not a value");
        assertEquals(ValueError.VALUE_NOT_AN_OPTION, FieldValidator.check(s, FieldValue.text("Open")).problem()
                .code());
    }

    @Test
    void theListFileKeepsNamesAndOrderAndWritesCodeOnlyOptionsAsBefore() {
        FieldSpec named = status().namedOptions(new FieldSpec.Option("Z", "Zeta stage"), new FieldSpec.Option("A",
                null)).build();
        String file = FieldListFile.write(List.of(named), Instant.parse("2026-11-26T10:00:00Z"));
        assertTrue(file.contains("{\"code\":\"Z\",\"name\":\"Zeta stage\"}"), file);
        FieldSpec back = FieldListFile.read(file).fields().get(0);
        assertEquals(named, back);
        assertEquals(List.of("Z", "A"), back.options());

        FieldSpec codeOnly = status().options("Open", "Closed").build();
        assertEquals("[\"Open\",\"Closed\"]", com.documentplatform.fields.json.internal.Json.canonical(
                FieldListFile.toJson(codeOnly).get("options")), "code-only options stay a list of strings");
        assertEquals(codeOnly, FieldListFile.fromJsonText(FieldListFile.toJsonText(codeOnly)));
    }
}
