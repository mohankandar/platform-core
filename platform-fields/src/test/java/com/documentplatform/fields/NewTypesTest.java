package com.documentplatform.fields;

import com.documentplatform.fields.FieldDefinitionException.Code;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.documentplatform.fields.FieldSpec.builder;
import static com.documentplatform.fields.FieldSpecTest.base;
import static com.documentplatform.fields.FieldSpecTest.refused;
import static com.documentplatform.fields.FieldValue.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Owner answers to P017: DECIMAL and DATE_TIME types (answer 1), a second street line in ADDRESS (answer 5). */
class NewTypesTest {

    // ---- DECIMAL ----

    @Test
    void decimalTakesDigitsWithAnOptionalDecimalPart() {
        FieldSpec rate = builder("rate", "Interest rate", FieldType.DECIMAL).build();
        ok(rate, "4.125", "4.125", "4.125");
        ok(rate, " 4.50 ", "4.50", "4.5");
        ok(rate, "-0.75", "-0.75", "-0.75");
        ok(rate, "100", "100", "100");
        ok(rate, "100.000", "100.000", "100");
        ok(rate, "-0.0", "-0.0", "0");
        ok(rate, "007.10", "007.10", "7.1");
        for (String bad : new String[]{"4,125", "1e3", "+4", ".5", "5.", "abc", "4.1.2", "$4"}) {
            refusedValue(rate, bad, ValueError.VALUE_NOT_A_DECIMAL);
        }
    }

    @Test
    void decimalRangeAndRules() {
        FieldSpec ltv = builder("ltv", "Loan to value", FieldType.DECIMAL).range("0", "97.50").build();
        assertEquals(new BigDecimal("97.5"), ltv.maxValue(), "kept without trailing zeros");
        assertEquals(new BigDecimal("0"), ltv.minValue());
        ok(ltv, "97.5", "97.5", "97.5");
        ok(ltv, "0", "0", "0");
        refusedValue(ltv, "97.51", ValueError.VALUE_ABOVE_MAXIMUM);
        refusedValue(ltv, "-0.01", ValueError.VALUE_BELOW_MINIMUM);
        assertEquals(new BigDecimal("1000"), builder("d", "D", FieldType.DECIMAL).range(null, "1E+3").build()
                .maxValue(), "never in exponent form");
        assertEquals("1000", builder("d", "D", FieldType.DECIMAL).range(null, "1E+3").build().maxValue()
                .toPlainString());
        refused(Code.FIELD_RANGE_INVALID, () -> builder("d", "D", FieldType.DECIMAL).range("2.5", "2.4").build());
        assertEquals(base(FieldType.DECIMAL).range("2.50", "2.5").build().minValue(),
                base(FieldType.DECIMAL).range("2.50", "2.5").build().maxValue(), "equal bounds are fine");
    }

    // ---- DATE_TIME ----

    @Test
    void dateTimeIsIsoWithAnOffsetAndNormalisedToUtc() {
        FieldSpec s = builder("received_at", "Received", FieldType.DATE_TIME).build();
        ok(s, "2026-11-21T10:15:30Z", "2026-11-21T10:15:30Z", "2026-11-21T10:15:30.000Z");
        ok(s, "2026-11-21T10:15:30.5-05:00", "2026-11-21T10:15:30.5-05:00", "2026-11-21T15:15:30.500Z");
        ok(s, "2026-11-21T10:15+01:00", "2026-11-21T10:15+01:00", "2026-11-21T09:15:00.000Z");
        ok(s, "2026-11-21T23:30:00-02:00", "2026-11-21T23:30:00-02:00", "2026-11-22T01:30:00.000Z");
        ok(s, "2026-11-21T10:15:30.123456789Z", "2026-11-21T10:15:30.123456789Z",
                "2026-11-21T10:15:30.123456789Z");
        assertEquals(norm(s, "2026-11-21T10:15:30Z"), norm(s, "2026-11-21T05:15:30-05:00"),
                "the same moment matches, whatever the offset");
        for (String bad : new String[]{"2026-11-21", "2026-11-21T10:15:30", "2026-02-30T10:00Z",
                "2026-11-21T25:00Z", "2026-11-21 10:15:30Z", "11/21/2026 10:15", "2026-11-21T10:15:30.1234567891Z",
                "now"}) {
            refusedValue(s, bad, ValueError.VALUE_NOT_A_DATE_TIME);
        }
    }

    @Test
    void newTypesTakeOnlyTheirRules() {
        refused(Code.FIELD_RULE_NOT_FOR_TYPE, () -> base(FieldType.DATE_TIME).range("1", null).build());
        for (FieldType t : List.of(FieldType.DECIMAL, FieldType.DATE_TIME)) {
            refused(Code.FIELD_RULE_NOT_FOR_TYPE, () -> base(t).maxLength(5).build());
            refused(Code.FIELD_RULE_NOT_FOR_TYPE, () -> base(t).pattern("x").build());
            refused(Code.FIELD_RULE_NOT_FOR_TYPE, () -> base(t).currency("USD").build());
            refused(Code.FIELD_RULE_NOT_FOR_TYPE, () -> base(t).normalisation(
                    Normalisation.of(Normalisation.LetterCase.UPPER, Normalisation.Strip.NONE)).build());
            assertTrue(base(t).searchable(SearchMode.PREFIX).build().searchable());
        }
        assertTrue(FieldType.DECIMAL.takesRange());
        assertFalse(FieldType.DATE_TIME.takesRange());
        assertFalse(FieldSpec.suggestsSensitive("rate", "Interest rate", FieldType.DECIMAL));
    }

    // ---- ADDRESS street2 ----

    @Test
    void addressHasAnOptionalSecondStreetLine() {
        FieldSpec s = builder("property_address", "Property address", FieldType.ADDRESS).build();
        FieldValidator.Checked c = FieldValidator.check(s,
                FieldValue.address("1 Main St", "  Apt   4B ", "Springfield", "il", "62701"));
        assertTrue(c.ok(), String.valueOf(c.problem()));
        FieldValue.Address a = (FieldValue.Address) c.valid().display();
        assertEquals("Apt 4B", a.street2());
        assertEquals(new FieldValue.Address("1 Main St", "Apt 4B", "Springfield", "IL", "62701", "US"), a);

        FieldValue.Address blank = (FieldValue.Address) FieldValidator.check(s,
                FieldValue.address("1 Main St", "  ", "Springfield", "IL", "62701")).valid().display();
        assertNull(blank.street2(), "a blank second line is no second line");
        assertEquals(new FieldValue.Address("1 Main St", "Springfield", "IL", "62701", "US"), blank,
                "the same as an address without one");

        assertEquals(ValueError.VALUE_CONTROL_CHARACTERS, FieldValidator.check(s,
                FieldValue.address("1 Main St", "Apt\n4B", "Springfield", "IL", "62701")).problem().code());
        assertEquals(ValueError.VALUE_TOO_LONG, FieldValidator.check(s,
                FieldValue.address("1 Main St", "x".repeat(490), "Springfield", "IL", "62701")).problem().code(),
                "the second line counts towards the 512 characters");
        assertEquals(ValueError.ADDRESS_STREET_MISSING, FieldValidator.check(s,
                FieldValue.address(" ", "Apt 4B", "Springfield", "IL", "62701")).problem().code(),
                "the first street line stays required");
        assertFalse(FieldValue.address("1 Main St", "Apt 4B", "Springfield", "IL", "62701").toString()
                .contains("4B"));
    }

    @Test
    void anAddressWithOnlyASecondLineIsNotBlankInACase() {
        FieldSet set = FieldSet.of(List.of(builder("property_address", "Property address", FieldType.ADDRESS)
                .build()));
        ValidatedFields v = set.validate(java.util.Map.of("property_address",
                List.<FieldValue>of(FieldValue.address(null, "Apt 4B", null, null, null))));
        assertFalse(v.accepted());
        assertEquals(ValueError.ADDRESS_STREET_MISSING, v.problems().get(0).code());
    }

    // ---- helpers ----

    private static String norm(FieldSpec s, String input) {
        return FieldValidator.check(s, text(input)).valid().normalisedText();
    }

    private static void ok(FieldSpec s, String input, String display, String normalised) {
        FieldValidator.Checked c = FieldValidator.check(s, text(input));
        assertTrue(c.ok(), () -> input + " should pass, got " + c.problem());
        assertEquals(text(display), c.valid().display());
        assertEquals(normalised, c.valid().normalisedText());
    }

    private static void refusedValue(FieldSpec s, String input, ValueError code) {
        FieldValidator.Checked c = FieldValidator.check(s, text(input));
        assertFalse(c.ok(), () -> input + " should be refused");
        assertEquals(code, c.problem().code(), c.problem().message());
        assertTrue(c.problem().message().startsWith(s.label()), c.problem().message());
    }
}
