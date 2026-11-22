package com.documentplatform.fields.file;

import com.documentplatform.fields.FieldDefinition;
import com.documentplatform.fields.FieldDefinitionException;
import com.documentplatform.fields.FieldKey;
import com.documentplatform.fields.FieldScope;
import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.FieldType;
import com.documentplatform.fields.Normalisation;
import com.documentplatform.fields.SearchMode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.documentplatform.fields.FieldSpec.builder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The platform-fields/1 file: what one product exports, the other reads back exactly; import matches by key. */
public class FieldListFileTest {

    static final Instant AT = Instant.parse("2026-11-21T10:00:00Z");

    /** One field of every type, with every rule and flag used somewhere. */
    public static List<FieldSpec> everyKind() {
        return List.of(
                builder("loan_number", "Loan number", FieldType.TEXT).required(true).maxLength(20).minLength(3)
                        .pattern("[A-Z0-9-]+").normalisation(Normalisation.LetterCase.UPPER,
                                Normalisation.Strip.SEPARATORS).primary(true).searchable(SearchMode.PARTIAL)
                        .displayOrder(0).build(),
                builder("units", "Units", FieldType.NUMBER).range("0", "999").exactLength(3).displayOrder(1).build(),
                builder("loan_amount", "Loan amount", FieldType.MONEY).currency("USD").range("0", "5000000.5")
                        .searchable(true).displayOrder(2).build(),
                builder("closing_date", "Closing date", FieldType.DATE).displayable(false).displayOrder(3).build(),
                builder("contact", "Contact e-mail", FieldType.EMAIL).normalisation(Normalisation.LetterCase.LOWER,
                        Normalisation.Strip.NONE).displayOrder(4).build(),
                builder("ref", "Reference", FieldType.UUID).displayOrder(5).build(),
                builder("loan_type", "Loan type", FieldType.DROPDOWN).options("Fixed", "Variable", "Balloon")
                        .displayOrder(6).build(),
                builder("property_address", "Property address", FieldType.ADDRESS).searchable(true).displayOrder(7)
                        .build(),
                builder("policy", "Policy number", FieldType.CUSTOM_PATTERN).pattern("POL-[0-9]{6}")
                        .displayOrder(8).build(),
                builder("borrower_name", "Borrower name", FieldType.TEXT).multiValue(true).searchable(true)
                        .displayOrder(9).build(),
                builder("old_ref", "Old reference", FieldType.TEXT).active(false).displayOrder(10).build());
    }

    @Test
    void exportAndImportRoundTrip() {
        String file = FieldListFile.write(everyKind(), AT);
        FieldListFile.Content back = FieldListFile.read(file);
        assertEquals(AT, back.exportedAt());
        assertEquals(everyKind(), back.fields(), "every member comes back as it was");
        assertEquals(file, FieldListFile.write(back.fields(), AT), "the same file again");
        assertTrue(file.contains("\"format\": \"platform-fields/1\""), file);
        assertTrue(file.contains("\"maxValue\": \"5000000.50\""), "decimals as strings");
        assertTrue(FieldListFile.read("﻿" + file).fields().size() == 11, "a byte order mark is fine");
    }

    @Test
    void onlyKeyLabelAndTypeAreRequired() {
        FieldListFile.Content c = FieldListFile.read("""
                {"format": "platform-fields/1", "exportedAt": "2026-11-21T10:00:00Z", "fields": [
                  {"key": "first_name", "label": "First name", "type": "TEXT"},
                  {"key": "loan_number", "label": "Loan number", "type": "TEXT"}
                ]}""");
        assertEquals(builder("first_name", "First name", FieldType.TEXT).build(), c.fields().get(0));
        assertTrue(c.fields().get(0).sensitive(), "the default for names applies when the file says nothing");
        assertFalse(c.fields().get(1).sensitive());
    }

    @Test
    void readingIsStrictAndSaysWhere() {
        refused(FieldFileException.Code.FILE_UNREADABLE, "{not json");
        refused(FieldFileException.Code.FILE_UNREADABLE, null);
        refused(FieldFileException.Code.FILE_FORMAT_UNKNOWN, "{\"format\": \"platform-fields/2\", \"fields\": []}");
        refused(FieldFileException.Code.FILE_FORMAT_UNKNOWN, "{\"fields\": []}");
        refused(FieldFileException.Code.FILE_SHAPE_INVALID, wrap("{\"key\": \"a\", \"label\": \"A\"}"));
        refused(FieldFileException.Code.FILE_SHAPE_INVALID,
                wrap("{\"key\": \"a\", \"label\": \"A\", \"type\": \"TEXT\", \"colour\": \"red\"}"));
        refused(FieldFileException.Code.FILE_SHAPE_INVALID, wrap("{\"key\": \"a\", \"label\": \"A\", \"type\": \"BLOB\"}"));
        refused(FieldFileException.Code.FILE_SHAPE_INVALID,
                wrap("{\"key\": \"a\", \"label\": \"A\", \"type\": \"NUMBER\", \"minValue\": 5}"));
        refused(FieldFileException.Code.FILE_SHAPE_INVALID,
                "{\"format\": \"platform-fields/1\", \"exportedAt\": \"yesterday\", \"fields\": []}");
        refused(FieldFileException.Code.FILE_SHAPE_INVALID,
                "{\"format\": \"platform-fields/1\", \"exportedAt\": \"2026-11-21T10:00:00Z\", \"fields\": [], \"x\": 1}");

        FieldFileException bad = refused(FieldFileException.Code.FILE_FIELD_INVALID, wrap(
                "{\"key\": \"a\", \"label\": \"A\", \"type\": \"TEXT\"}",
                "{\"key\": \"m\", \"label\": \"M\", \"type\": \"MONEY\"}"));
        assertEquals(2, bad.fieldNumber());
        assertEquals(FieldDefinitionException.Code.FIELD_CURRENCY_REQUIRED, bad.fieldCode());

        FieldFileException repeated = refused(FieldFileException.Code.FILE_KEY_REPEATED, wrap(
                "{\"key\": \"Loan\", \"label\": \"A\", \"type\": \"TEXT\"}",
                "{\"key\": \"loan\", \"label\": \"B\", \"type\": \"TEXT\"}"));
        assertEquals(2, repeated.fieldNumber());

        StringBuilder many = new StringBuilder();
        for (int i = 0; i <= FieldListFile.MAX_FIELDS; i++) {
            many.append(i == 0 ? "" : ",").append("{\"key\": \"f").append(i).append("\", \"label\": \"F\", \"type\": \"TEXT\"}");
        }
        refused(FieldFileException.Code.FILE_TOO_LARGE, wrap(many.toString()));
    }

    @Test
    void importMatchesByKeyAndNeverChangesAnExistingField() {
        FieldScope scope = FieldScope.of(UUID.randomUUID(), "PRODUCT_B");
        List<FieldDefinition> here = List.of(
                stored(scope, builder("loan_number", "Loan no.", FieldType.TEXT).build()),
                stored(scope, builder("units", "Units", FieldType.TEXT).build()),
                stored(scope, builder("loan_amount", "Loan amount", FieldType.MONEY).currency("EUR").build()),
                stored(scope, builder("Closing_Date", "Closing date", FieldType.DATE).build()),
                stored(scope, builder("ref", "Reference", FieldType.UUID).displayOrder(5).build()));
        ImportReport r = FieldImport.plan(here, everyKind(), false);
        assertFalse(r.applied());
        assertEquals(List.of(
                        ImportReport.Outcome.UNCHANGED, ImportReport.Outcome.CONFLICT, ImportReport.Outcome.CONFLICT,
                        ImportReport.Outcome.CONFLICT, ImportReport.Outcome.CREATE, ImportReport.Outcome.UNCHANGED,
                        ImportReport.Outcome.CREATE, ImportReport.Outcome.CREATE, ImportReport.Outcome.CREATE,
                        ImportReport.Outcome.CREATE, ImportReport.Outcome.CREATE),
                r.lines().stream().map(ImportReport.Line::outcome).toList());
        ImportReport.Line loanNumber = r.lines().get(0);
        assertEquals(List.of("label", "letterCase", "maxLength", "minLength", "pattern", "primary", "required",
                "searchMode", "searchable", "strip"), loanNumber.differences(), "listed, not applied");
        assertTrue(loanNumber.message().startsWith("Field loan_number exists and is left as it is"));
        assertEquals(ImportReport.Conflict.TYPE_DIFFERS, r.lines().get(1).conflict());
        assertEquals("Field units is TEXT here and NUMBER in the file; the existing field keeps its type",
                r.lines().get(1).message());
        assertEquals(ImportReport.Conflict.CURRENCY_DIFFERS, r.lines().get(2).conflict());
        assertEquals(ImportReport.Conflict.KEY_CASE_DIFFERS, r.lines().get(3).conflict());
        assertEquals(List.of(), r.lines().get(5).differences());
        assertEquals("Field ref is already the same", r.lines().get(5).message());
        assertEquals(6, r.toCreate().size());
        assertNull(r.lines().get(4).conflict());
    }

    private static FieldDefinition stored(FieldScope scope, FieldSpec spec) {
        return new FieldDefinition(UUID.randomUUID(), scope, spec, 1, AT, AT, "u-admin");
    }

    private static String wrap(String... fields) {
        return "{\"format\": \"platform-fields/1\", \"exportedAt\": \"2026-11-21T10:00:00Z\", \"fields\": ["
                + String.join(",", fields) + "]}";
    }

    private static FieldFileException refused(FieldFileException.Code code, String text) {
        FieldFileException e = assertThrows(FieldFileException.class, () -> FieldListFile.read(text));
        assertEquals(code, e.code(), e.getMessage());
        return e;
    }

    /** The types and parts added with the owner answers to P017. */
    public static List<FieldSpec> newKinds() {
        return List.of(
                builder("rate", "Interest rate", FieldType.DECIMAL).range("-1.5", "25.125").searchable(true)
                        .displayOrder(20).build(),
                builder("received_at", "Received", FieldType.DATE_TIME).required(true).displayOrder(21).build());
    }

    @Test
    void newKindsRoundTrip() {
        String file = FieldListFile.write(newKinds(), AT);
        assertEquals(newKinds(), FieldListFile.read(file).fields());
        assertTrue(file.contains("\"type\": \"DECIMAL\""), file);
        assertTrue(file.contains("\"type\": \"DATE_TIME\""), file);
        assertTrue(file.contains("\"maxValue\": \"25.125\""), file);
        for (FieldSpec s : newKinds()) {
            assertEquals(s, FieldListFile.fromJsonText(FieldListFile.toJsonText(s)), s.key().value());
        }
    }

    @Test
    void historySnapshotsRoundTrip() {
        for (FieldSpec s : everyKind()) {
            assertEquals(s, FieldListFile.fromJsonText(FieldListFile.toJsonText(s)), s.key().value());
        }
        assertEquals(FieldKey.of("units"), everyKind().get(1).key());
    }
}
