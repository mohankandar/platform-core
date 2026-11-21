package com.documentplatform.fields;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.documentplatform.fields.FieldSpec.builder;
import static com.documentplatform.fields.FieldValue.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A case's values checked together against the entity's field list. */
class FieldSetTest {

    static final FieldSet FIELDS = FieldSet.of(List.of(
            builder("loan_number", "Loan number", FieldType.TEXT).required(true).primary(true).displayOrder(1).build(),
            builder("borrower_name", "Borrower name", FieldType.TEXT).multiValue(true).displayOrder(2).build(),
            builder("loan_amount", "Loan amount", FieldType.MONEY).currency("USD").displayOrder(3).build(),
            builder("old_ref", "Old reference", FieldType.TEXT).active(false).displayOrder(0).build()));

    @Test
    void acceptedValuesComeBackInDisplayOrder() {
        Map<String, List<FieldValue>> in = new LinkedHashMap<>();
        in.put("loan_amount", List.of(text("1,000")));
        in.put("borrower_name", List.of(text("Ann Lee"), text("Bo Lee")));
        in.put("loan_number", List.of(text(" LN-1 ")));
        ValidatedFields v = FIELDS.validate(in);
        assertTrue(v.accepted(), v.problems().toString());
        assertEquals(List.of("loan_number", "borrower_name", "loan_amount"),
                v.values().keySet().stream().map(FieldKey::value).toList());
        assertEquals("1000.00", v.values().get(FieldKey.of("loan_amount")).get(0).normalisedText());
        assertEquals(List.of("old_ref", "loan_number", "borrower_name", "loan_amount"),
                FIELDS.all().stream().map(s -> s.key().value()).toList());
        assertEquals(3, FIELDS.active().size());
    }

    @Test
    void unknownSwitchedOffAndMissingRequiredAreRefused() {
        Map<String, List<FieldValue>> in = new LinkedHashMap<>();
        in.put("nope", List.of(text("x")));
        in.put("not a key", List.of(text("x")));
        in.put("old_ref", List.of(text("R-1")));
        in.put("Loan_Number", List.of(text("LN-1")));
        ValidatedFields v = FIELDS.validate(in);
        assertFalse(v.accepted());
        assertEquals(List.of(ValueError.FIELD_UNKNOWN, ValueError.FIELD_UNKNOWN, ValueError.FIELD_INACTIVE,
                ValueError.FIELD_UNKNOWN, ValueError.VALUE_REQUIRED),
                v.problems().stream().map(ValueProblem::code).toList(), "keys match exactly, capitals included");
        assertEquals("A value was given under a key that is not a valid field key", v.problems().get(1).message());
        assertEquals("Old reference is switched off and takes no new values", v.problems().get(2).message());
    }

    @Test
    void blankCountsAsNoValue() {
        Map<String, List<FieldValue>> in = Map.of("loan_number", List.of(text("LN-1")),
                "borrower_name", List.of(text(" "), text("")), "old_ref", List.of(text("  ")));
        ValidatedFields v = FIELDS.validate(in);
        assertTrue(v.accepted(), v.problems().toString());
        assertEquals(List.of(FieldKey.of("loan_number")), List.copyOf(v.values().keySet()));
        assertEquals(ValueError.VALUE_REQUIRED, FIELDS.validate(Map.of("loan_number", List.of(text(" "))))
                .problems().get(0).code());
    }

    @Test
    void twoKeysMayNotDifferOnlyInCapitals() {
        FieldDefinitionException e = assertThrows(FieldDefinitionException.class, () -> FieldSet.of(List.of(
                builder("Loan", "A", FieldType.TEXT).build(), builder("loan", "B", FieldType.TEXT).build())));
        assertEquals(FieldDefinitionException.Code.FIELD_KEY_TAKEN, e.code());
    }
}
