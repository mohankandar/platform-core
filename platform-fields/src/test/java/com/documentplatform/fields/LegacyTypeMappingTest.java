package com.documentplatform.fields;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.documentplatform.fields.FieldSpec.builder;
import static com.documentplatform.fields.FieldValue.text;
import static com.documentplatform.fields.Normalisation.LetterCase.LOWER;
import static com.documentplatform.fields.Normalisation.LetterCase.PRESERVE;
import static com.documentplatform.fields.Normalisation.LetterCase.UPPER;
import static com.documentplatform.fields.Normalisation.Strip.NONE;
import static com.documentplatform.fields.Normalisation.Strip.NON_DIGITS;
import static com.documentplatform.fields.Normalisation.Strip.SEPARATORS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mapping in docs/design/case-fields.md section "Mapping from the apps' current models", shown to keep what
 * each app's own validator accepts and how it normalises. Product names stay out of the code: "identifier model" is
 * the business identifier definitions of one app, "metadata model" the metadata field definitions of the other.
 */
class LegacyTypeMappingTest {

    // ---- identifier model: validation type + case normalisation + strip separators ----

    @Test
    void identifierTextWithItsDefaultUpperCase() {
        FieldSpec s = builder("contract_number", "Contract number", FieldType.TEXT).normalisation(UPPER, NONE).build();
        assertEquals("AB 12", norm(s, " ab   12 "));
    }

    @Test
    void identifierNumericIsNumberWithDigitsOnlyAndLeadingZerosKept() {
        FieldSpec s = builder("member_number", "Member number", FieldType.NUMBER).pattern("[0-9]+")
                .normalisation(UPPER, SEPARATORS).build();
        assertEquals("007123", norm(s, "007-123"));
        assertEquals("7", norm(s, "-7"), "the dash is a separator, stripped as before");
        FieldSpec kept = s.toBuilder().normalisation(UPPER, NONE).build();
        assertEquals(ValueError.VALUE_PATTERN_MISMATCH, code(kept, "-7"), "digits only, as before: no sign");
        assertEquals(ValueError.VALUE_NOT_A_NUMBER, code(s, "12a"));
    }

    @Test
    void identifierAlphanumericIsTextWithAPattern() {
        FieldSpec s = builder("loan_number", "Loan number", FieldType.TEXT).pattern("[A-Za-z0-9]+")
                .normalisation(UPPER, SEPARATORS).build();
        assertEquals("LN20260042", norm(s, "ln-2026 0042"));
        assertEquals(ValueError.VALUE_PATTERN_MISMATCH, code(s, "LN_2026"));
    }

    @Test
    void identifierEmailUuidAllowListAndCustomPattern() {
        FieldSpec email = builder("contact", "Contact", FieldType.EMAIL).normalisation(UPPER, NONE).build();
        assertEquals("ANN@EXAMPLE.COM", norm(email, "ann@example.com"));
        FieldSpec uuid = builder("ref", "Reference", FieldType.UUID).build();
        assertEquals("123e4567-e89b-12d3-a456-426614174000", norm(uuid, "123E4567-E89B-12D3-A456-426614174000"),
                "matching is unchanged; the stored form is lower case");
        FieldSpec allow = builder("region", "Region", FieldType.DROPDOWN).options("North", "South")
                .normalisation(UPPER, NONE).build();
        assertEquals("NORTH", norm(allow, "north"));
        assertEquals(ValueError.VALUE_NOT_AN_OPTION, code(allow, "East"));
        FieldSpec custom = builder("policy", "Policy", FieldType.CUSTOM_PATTERN).pattern("P-[0-9]{4}")
                .normalisation(UPPER, NONE).exactLength(6).build();
        assertEquals("P-1234", norm(custom, "p-1234"));
    }

    @Test
    void identifierLimitsAreKept() {
        FieldSpec s = builder("a", "A", FieldType.TEXT).maxLength(512).build();
        assertTrue(FieldValidator.check(s, text("x".repeat(512))).ok());
        assertEquals(ValueError.VALUE_TOO_LONG, code(s, "x".repeat(513)));
        assertTrue(FieldValidator.check(s, text("tab\there")).ok(), "a tab is allowed, as before");
        assertEquals(ValueError.VALUE_CONTROL_CHARACTERS, code(s, "line\nbreak"));
    }

    // ---- metadata model: field type + normalisation + validation rules + search mode ----

    @Test
    void metadataIntegerAndRanges() {
        FieldSpec s = builder("box_count", "Boxes", FieldType.NUMBER).range("1", "99").build();
        assertEquals("12", norm(s, "12"));
        assertEquals(ValueError.VALUE_ABOVE_MAXIMUM, code(s, "100"));
    }

    @Test
    void metadataDecimalIsDecimalWithItsRangeOrMoneyWhenItIsAnAmount() {
        FieldSpec amount = builder("premium", "Premium", FieldType.MONEY).currency("USD").range("0", null).build();
        assertEquals("12.50", norm(amount, "12.5"));
        FieldSpec rate = builder("rate", "Rate", FieldType.DECIMAL).range("0", "25.5").build();
        assertEquals("4.125", norm(rate, "4.125"));
        assertEquals("-0.5", norm(builder("d", "D", FieldType.DECIMAL).build(), "-0.50"));
        assertEquals(ValueError.VALUE_NOT_A_DECIMAL, code(rate, "4,125"));
        assertEquals(ValueError.VALUE_ABOVE_MAXIMUM, code(rate, "25.51"), "min/max kept, as the old rules had them");
        assertEquals(ValueError.VALUE_BELOW_MINIMUM, code(rate, "-1"));
    }

    @Test
    void metadataDateTimeIsDateTimeWithItsOffset() {
        FieldSpec s = builder("received_at", "Received", FieldType.DATE_TIME).build();
        assertEquals("2026-11-21T10:15:30.000Z", norm(s, "2026-11-21T10:15:30Z"));
        assertEquals("2026-11-21T15:15:30.500Z", norm(s, "2026-11-21T10:15:30.5-05:00"));
        assertEquals(ValueError.VALUE_NOT_A_DATE_TIME, code(s, "2026-11-21"), "an offset is required, as before");
        assertEquals(ValueError.VALUE_NOT_A_DATE_TIME, code(s, "2026-02-30T10:00:00Z"), "the calendar is checked");
    }

    @Test
    void metadataBooleanIsADropdown() {
        FieldSpec s = builder("insured", "Insured", FieldType.DROPDOWN).options("true", "false")
                .normalisation(LOWER, NONE).build();
        assertEquals("true", norm(s, "TRUE"));
        assertEquals("false", norm(s, " false "));
        assertEquals(ValueError.VALUE_NOT_AN_OPTION, code(s, "yes"));
    }

    @Test
    void metadataNormalisationsAndSearchModes() {
        assertEquals("5551234", norm(builder("phone", "Phone", FieldType.TEXT).normalisation(PRESERVE, NON_DIGITS)
                .build(), "555-1234"), "DIGITS_ONLY");
        assertEquals("ABC", norm(builder("c", "C", FieldType.TEXT).normalisation(UPPER, NONE).build(), "abc"));
        assertEquals("abc", norm(builder("c", "C", FieldType.TEXT).normalisation(LOWER, NONE).build(), "ABC"));
        assertEquals("a b", norm(builder("c", "C", FieldType.TEXT).build(), "  a   b "), "TRIM; NONE also trims");
        for (SearchMode m : SearchMode.values()) {
            assertEquals(m, builder("case_ref", "Case ref", FieldType.TEXT).searchable(m).build().searchMode());
        }
        FieldSpec identifier = builder("policy_no", "Policy", FieldType.TEXT).primary(true).required(true).build();
        FieldSpec second = builder("plan_no", "Plan", FieldType.TEXT).primary(true).build();
        assertEquals(2, FieldSet.of(List.of(identifier, second)).active().stream().filter(FieldSpec::primary).count(),
                "several fields may identify a case together");
        FieldSpec email = builder("contact", "Contact", FieldType.EMAIL).build();
        assertFalse(FieldValidator.check(email, text("a@b")).ok(), "an e-mail needs a dot after the @, as before");
    }

    private static String norm(FieldSpec s, String value) {
        FieldValidator.Checked c = FieldValidator.check(s, text(value));
        assertTrue(c.ok(), () -> String.valueOf(c.problem()));
        return c.valid().normalisedText();
    }

    private static ValueError code(FieldSpec s, String value) {
        FieldValidator.Checked c = FieldValidator.check(s, text(value));
        assertFalse(c.ok());
        return c.problem().code();
    }
}
