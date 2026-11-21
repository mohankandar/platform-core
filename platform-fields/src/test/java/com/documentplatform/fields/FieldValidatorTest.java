package com.documentplatform.fields;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static com.documentplatform.fields.FieldSpec.builder;
import static com.documentplatform.fields.FieldValue.text;
import static com.documentplatform.fields.Normalisation.LetterCase.LOWER;
import static com.documentplatform.fields.Normalisation.LetterCase.PRESERVE;
import static com.documentplatform.fields.Normalisation.LetterCase.UPPER;
import static com.documentplatform.fields.Normalisation.Strip.NON_DIGITS;
import static com.documentplatform.fields.Normalisation.Strip.SEPARATORS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every type and rule: what passes, how it is normalised, the code of what fails, and no value in any message. */
class FieldValidatorTest {

    // ---- TEXT and the rules every text-like type shares ----

    @Test
    void textIsTrimmedWithSingleSpaces() {
        FieldSpec s = builder("contract_number", "Contract number", FieldType.TEXT).build();
        ok(s, "  AB  12\t34 ", "AB 12 34", "AB 12 34");
        refused(s, "", ValueError.VALUE_EMPTY);
        refused(s, "   ", ValueError.VALUE_EMPTY);
        refused(s, "two\nlines", ValueError.VALUE_CONTROL_CHARACTERS);
        refused(s, "bell\u0007", ValueError.VALUE_CONTROL_CHARACTERS);
        refused(s, "line separator", ValueError.VALUE_CONTROL_CHARACTERS);
        ok(s, "x".repeat(512), "x".repeat(512), "x".repeat(512));
        refused(s, "x".repeat(513), ValueError.VALUE_TOO_LONG);
    }

    @Test
    void normalisation() {
        FieldSpec upperStrip = builder("c", "C", FieldType.TEXT).normalisation(UPPER, SEPARATORS).build();
        ok(upperStrip, " ab-12 34 ", "ab-12 34", "AB1234");
        FieldSpec lower = builder("c", "C", FieldType.TEXT).normalisation(LOWER, Normalisation.Strip.NONE).build();
        ok(lower, "AbC", "AbC", "abc");
        FieldSpec digits = builder("c", "C", FieldType.TEXT).normalisation(PRESERVE, NON_DIGITS).build();
        ok(digits, "(555) 123-4567", "(555) 123-4567", "5551234567");
    }

    @Test
    void lengthsApplyToTheNormalisedValue() {
        FieldSpec exact = builder("ssn_like", "Member number", FieldType.TEXT).normalisation(UPPER, SEPARATORS)
                .exactLength(9).build();
        ok(exact, "123-45-6789", "123-45-6789", "123456789");
        refused(exact, "123-45-678", ValueError.VALUE_LENGTH_NOT_EXACT);
        FieldSpec range = builder("c", "C", FieldType.TEXT).minLength(3).maxLength(5).build();
        refused(range, "ab", ValueError.VALUE_TOO_SHORT);
        refused(range, "abcdef", ValueError.VALUE_ABOVE_MAX_LENGTH);
        ok(range, "abc", "abc", "abc");
        FieldSpec emoji = builder("c", "C", FieldType.TEXT).maxLength(2).build();
        ok(emoji, "😀😀", "😀😀", "😀😀");
    }

    @Test
    void patternOnText() {
        FieldSpec alnum = builder("c", "C", FieldType.TEXT).pattern("[A-Za-z0-9]+").build();
        ok(alnum, "AB12", "AB12", "AB12");
        refused(alnum, "AB-12", ValueError.VALUE_PATTERN_MISMATCH);
        refused(alnum, "AB 12", ValueError.VALUE_PATTERN_MISMATCH);
    }

    // ---- each type ----

    @Test
    void number() {
        FieldSpec s = builder("units", "Units", FieldType.NUMBER).build();
        ok(s, "42", "42", "42");
        ok(s, "-7", "-7", "-7");
        ok(s, "007", "007", "007");
        ok(s, "123456789012345678901234567890", "123456789012345678901234567890",
                "123456789012345678901234567890");
        for (String bad : List.of("4.2", "1,000", "+5", "12a", "--1", "1e3")) {
            refused(s, bad, ValueError.VALUE_NOT_A_NUMBER);
        }
        FieldSpec ranged = builder("units", "Units", FieldType.NUMBER).range("1", "10").build();
        refused(ranged, "0", ValueError.VALUE_BELOW_MINIMUM);
        refused(ranged, "11", ValueError.VALUE_ABOVE_MAXIMUM);
        ok(ranged, "010", "010", "010");
        FieldSpec stripped = builder("acct", "Account", FieldType.NUMBER).normalisation(PRESERVE, SEPARATORS).build();
        ok(stripped, "12-34 56", "12-34 56", "123456");
    }

    @Test
    void money() {
        FieldSpec usd = builder("loan_amount", "Loan amount", FieldType.MONEY).currency("USD").build();
        ok(usd, "1250", "1250", "1250.00");
        ok(usd, "1,250.5", "1,250.5", "1250.50");
        ok(usd, "$1,250.50", "$1,250.50", "1250.50");
        ok(usd, "USD 99.99", "USD 99.99", "99.99");
        ok(usd, "99.99 USD", "99.99 USD", "99.99");
        ok(usd, "-$5", "-$5", "-5.00");
        ok(usd, "0.00", "0.00", "0.00");
        ok(usd, "-0", "-0", "0.00");
        refused(usd, "1.234", ValueError.VALUE_TOO_MANY_DECIMALS);
        for (String bad : List.of("12,34", "1,2345", "abc", "1.2.3", "EUR 5", "€5", ".5", "1 250")) {
            refused(usd, bad, ValueError.VALUE_NOT_MONEY);
        }
        FieldSpec jpy = builder("fee", "Fee", FieldType.MONEY).currency("JPY").build();
        ok(jpy, "1,000", "1,000", "1000");
        refused(jpy, "1.5", ValueError.VALUE_TOO_MANY_DECIMALS);
        FieldSpec ranged = builder("fee", "Fee", FieldType.MONEY).currency("USD").range("0", "1000").build();
        refused(ranged, "-0.01", ValueError.VALUE_BELOW_MINIMUM);
        refused(ranged, "1000.01", ValueError.VALUE_ABOVE_MAXIMUM);
        ok(ranged, "1000", "1000", "1000.00");
    }

    @Test
    void date() {
        FieldSpec s = builder("closing_date", "Closing date", FieldType.DATE).build();
        ok(s, "2026-11-21", "2026-11-21", "2026-11-21");
        ok(s, "11/21/2026", "11/21/2026", "2026-11-21");
        ok(s, "1/2/2026", "1/2/2026", "2026-01-02");
        ok(s, "2028-02-29", "2028-02-29", "2028-02-29");
        for (String bad : List.of("2026-02-30", "2027-02-29", "21/11/2026", "2026-11-21T10:00:00Z", "20261121",
                "Nov 21, 2026", "2026-1-2")) {
            refused(s, bad, ValueError.VALUE_NOT_A_DATE);
        }
    }

    @Test
    void email() {
        FieldSpec s = builder("contact", "Contact", FieldType.EMAIL).build();
        ok(s, "Ann.Lee@Example.com", "Ann.Lee@Example.com", "Ann.Lee@Example.com");
        FieldSpec lower = builder("contact", "Contact", FieldType.EMAIL).normalisation(LOWER, Normalisation.Strip.NONE)
                .build();
        ok(lower, "Ann.Lee@Example.com", "Ann.Lee@Example.com", "ann.lee@example.com");
        for (String bad : List.of("ann", "ann@", "@x.com", "ann@example", "a b@x.com", "a@@x.com")) {
            refused(s, bad, ValueError.VALUE_NOT_AN_EMAIL);
        }
    }

    @Test
    void uuid() {
        FieldSpec s = builder("ref", "Reference", FieldType.UUID).build();
        ok(s, "123E4567-E89B-12D3-A456-426614174000", "123E4567-E89B-12D3-A456-426614174000",
                "123e4567-e89b-12d3-a456-426614174000");
        for (String bad : List.of("123e4567e89b12d3a456426614174000", "ffffffff-e89b-12d3-a456-42661417400",
                "1-2-3-4-5", "g23e4567-e89b-12d3-a456-426614174000")) {
            refused(s, bad, ValueError.VALUE_NOT_A_UUID);
        }
    }

    @Test
    void dropdown() {
        FieldSpec s = builder("loan_type", "Loan type", FieldType.DROPDOWN).options("Fixed rate", "Variable")
                .normalisation(UPPER, Normalisation.Strip.NONE).build();
        ok(s, " fixed   RATE ", "Fixed rate", "FIXED RATE");
        refused(s, "Balloon", ValueError.VALUE_NOT_AN_OPTION);
        FieldSpec exact = builder("loan_type", "Loan type", FieldType.DROPDOWN).options("Fixed").build();
        refused(exact, "fixed", ValueError.VALUE_NOT_AN_OPTION);
    }

    @Test
    void customPattern() {
        FieldSpec s = builder("policy", "Policy number", FieldType.CUSTOM_PATTERN).pattern("POL-[0-9]{6}")
                .normalisation(UPPER, Normalisation.Strip.NONE).build();
        ok(s, "pol-123456", "pol-123456", "POL-123456");
        refused(s, "POL-12345", ValueError.VALUE_PATTERN_MISMATCH);
        refused(s, "xPOL-123456", ValueError.VALUE_PATTERN_MISMATCH);
    }

    @Test
    void address() {
        FieldSpec s = builder("property_address", "Property address", FieldType.ADDRESS).build();
        FieldValidator.Checked c = FieldValidator.check(s, new FieldValue.Address(" 1  Main St ", "Springfield",
                "il", "627011234", null));
        assertTrue(c.ok(), String.valueOf(c.problem()));
        assertEquals(new FieldValue.Address("1 Main St", "Springfield", "IL", "62701-1234", "US"), c.valid().display());
        assertEquals(c.valid().display(), c.valid().normalised());
        assertTrue(FieldValidator.check(s, new FieldValue.Address("1 Main St", "Springfield", "IL", "62701", "us"))
                .ok());
        assertTrue(FieldValidator.check(s, FieldValue.address("PO Box 9", "San Juan", "PR", "00901")).ok());

        refusedAddress(s, new FieldValue.Address(" ", "Springfield", "IL", "62701", null),
                ValueError.ADDRESS_STREET_MISSING);
        refusedAddress(s, new FieldValue.Address("1 Main St", null, "IL", "62701", null),
                ValueError.ADDRESS_CITY_MISSING);
        refusedAddress(s, FieldValue.address("1 Main St", "Springfield", "Illinois", "62701"),
                ValueError.ADDRESS_STATE_INVALID);
        refusedAddress(s, FieldValue.address("1 Main St", "Springfield", "XX", "62701"),
                ValueError.ADDRESS_STATE_INVALID);
        refusedAddress(s, FieldValue.address("1 Main St", "Springfield", "IL", "6270"),
                ValueError.ADDRESS_ZIP_INVALID);
        refusedAddress(s, FieldValue.address("1 Main St", "Springfield", "IL", "62701-12"),
                ValueError.ADDRESS_ZIP_INVALID);
        refusedAddress(s, new FieldValue.Address("1 Main St", "Toronto", "ON", "M5V", "CA"),
                ValueError.ADDRESS_COUNTRY_NOT_SUPPORTED);
        refusedAddress(s, FieldValue.address("1 Main St\n2nd floor", "Springfield", "IL", "62701"),
                ValueError.VALUE_CONTROL_CHARACTERS);
        refusedAddress(s, FieldValue.address("x".repeat(500), "Springfield", "IL", "62701"),
                ValueError.VALUE_TOO_LONG);
        refusedAddress(s, text("1 Main St, Springfield IL"), ValueError.VALUE_WRONG_SHAPE);
        assertEquals(ValueError.VALUE_WRONG_SHAPE, FieldValidator.check(builder("t", "T", FieldType.TEXT).build(),
                FieldValue.address("1 Main St", "Springfield", "IL", "62701")).problem().code());
    }

    // ---- several values ----

    @Test
    void multiValue() {
        FieldSpec borrowers = builder("borrower_name", "Borrower name", FieldType.TEXT).multiValue(true)
                .normalisation(UPPER, Normalisation.Strip.NONE).build();
        FieldValidator.Result two = FieldValidator.checkAll(borrowers, List.of(text("Ann Lee"), text("Bo Lee")));
        assertTrue(two.ok());
        assertEquals(List.of("ANN LEE", "BO LEE"), two.values().stream().map(ValidValue::normalisedText).toList());

        FieldValidator.Result repeated = FieldValidator.checkAll(borrowers, List.of(text("Ann Lee"), text("ann  lee")));
        assertEquals(ValueError.VALUE_REPEATED, repeated.problems().get(0).code());
        assertEquals(1, repeated.problems().get(0).index());
        assertEquals(List.of(), repeated.values(), "nothing passes when one fails");

        FieldValidator.Result oneBad = FieldValidator.checkAll(borrowers, List.of(text("Ann"), text("x\ny")));
        assertEquals("Borrower name (value 2) must not contain line breaks or other control characters",
                oneBad.problems().get(0).message());

        List<FieldValue> fiftyOne = IntStream.rangeClosed(1, 51).mapToObj(i -> (FieldValue) text("n" + i)).toList();
        assertEquals(ValueError.VALUE_TOO_MANY, FieldValidator.checkAll(borrowers, fiftyOne).problems().get(0).code());
        assertTrue(FieldValidator.checkAll(borrowers, fiftyOne.subList(0, 50)).ok());

        FieldSpec single = builder("loan_number", "Loan number", FieldType.TEXT).build();
        assertEquals(ValueError.VALUE_NOT_MULTI,
                FieldValidator.checkAll(single, List.of(text("A"), text("B"))).problems().get(0).code());
        FieldSpec required = builder("loan_number", "Loan number", FieldType.TEXT).required(true).build();
        assertEquals(ValueError.VALUE_REQUIRED, FieldValidator.checkAll(required, List.of()).problems().get(0).code());
        assertTrue(FieldValidator.checkAll(single, List.of()).ok(), "an optional field may have no value");
    }

    @Test
    void messagesNameTheLabelAndTheRuleNeverTheValue() {
        String secret = "Zebediah-Quartermaine-4471";
        FieldSpec name = builder("borrower_name", "Borrower name", FieldType.TEXT).maxLength(5).build();
        ValueProblem p = FieldValidator.check(name, text(secret)).problem();
        assertEquals("Borrower name must have at most 5 characters", p.message());
        assertEquals(FieldKey.of("borrower_name"), p.key());
        for (FieldType t : FieldType.values()) {
            FieldSpec s = FieldSpecTest.base(t).build();
            FieldValidator.Checked c = t == FieldType.ADDRESS
                    ? FieldValidator.check(s, FieldValue.address(secret, secret, secret, secret))
                    : FieldValidator.check(s, text(secret + "\u0001"));
            assertFalse(c.ok(), t.name());
            assertFalse(c.problem().message().contains("Zebediah"), c.problem().message());
            assertFalse(c.problem().toString().contains("Zebediah"), c.problem().toString());
        }
        assertFalse(text(secret).toString().contains("Zebediah"));
        assertFalse(FieldValue.address(secret, "c", "IL", "1").toString().contains("Zebediah"));
        ValidValue v = FieldValidator.check(builder("t", "T", FieldType.TEXT).build(), text(secret)).valid();
        assertFalse(v.toString().contains("Zebediah"));
    }

    // ---- helpers ----

    private static void ok(FieldSpec s, String input, String display, String normalised) {
        FieldValidator.Checked c = FieldValidator.check(s, text(input));
        assertTrue(c.ok(), () -> s.type() + " should accept, got " + c.problem());
        assertEquals(text(display), c.valid().display());
        assertEquals(normalised, c.valid().normalisedText());
    }

    /** @param code null for "any refusal" */
    private static void refused(FieldSpec s, String input, ValueError code) {
        FieldValidator.Checked c = FieldValidator.check(s, text(input));
        assertFalse(c.ok(), () -> s.type() + " should refuse");
        if (code != null) {
            assertEquals(code, c.problem().code(), c.problem().message());
        }
        assertTrue(c.problem().message().startsWith(s.label()), c.problem().message());
        if (input.strip().length() >= 3) {
            assertFalse(c.problem().message().contains(input.strip()), "the message repeats the value");
        }
    }

    private static void refusedAddress(FieldSpec s, FieldValue v, ValueError code) {
        FieldValidator.Checked c = FieldValidator.check(s, v);
        assertFalse(c.ok());
        assertEquals(code, c.problem().code(), c.problem().message());
    }
}
