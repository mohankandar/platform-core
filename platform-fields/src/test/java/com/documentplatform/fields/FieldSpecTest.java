package com.documentplatform.fields;

import com.documentplatform.fields.FieldDefinitionException.Code;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.documentplatform.fields.FieldSpec.builder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The model's own rules: every definition that exists is valid. */
class FieldSpecTest {

    @Test
    void keysStartWithALetterAndHaveAtMost100Characters() {
        for (String ok : List.of("contract_number", "LoanNumber", "a", "member.no", "x-1", "a" + "b".repeat(99))) {
            assertEquals(ok, FieldKey.of(ok).value());
        }
        for (String bad : List.of("", "1abc", "_a", "has space", "naïve", "a/b", "a" + "b".repeat(100))) {
            refused(Code.FIELD_KEY_INVALID, () -> FieldKey.of(bad));
        }
        refused(Code.FIELD_KEY_INVALID, () -> FieldKey.of(null));
        assertTrue(FieldKey.of("Loan_No").sameIgnoringCase(FieldKey.of("loan_no")));
        assertFalse(FieldKey.of("Loan_No").equals(FieldKey.of("loan_no")), "kept exactly as given");
    }

    @Test
    void labelsAndProducts() {
        assertEquals("Contract number", builder("k", "  Contract number ", FieldType.TEXT).build().label());
        refused(Code.FIELD_LABEL_INVALID, () -> builder("k", " ", FieldType.TEXT).build());
        refused(Code.FIELD_LABEL_INVALID, () -> builder("k", "a".repeat(201), FieldType.TEXT).build());
        refused(Code.FIELD_LABEL_INVALID, () -> builder("k", "two\nlines", FieldType.TEXT).build());
        assertEquals("a".repeat(200), builder("k", "a".repeat(200), FieldType.TEXT).build().label());
        refused(Code.FIELD_PRODUCT_INVALID, () -> FieldScope.of(UUID.randomUUID(), "product-a"));
        refused(Code.FIELD_PRODUCT_INVALID, () -> FieldScope.of(UUID.randomUUID(), "P"));
        assertEquals("PRODUCT_A", FieldScope.of(UUID.randomUUID(), "PRODUCT_A").product());
    }

    @Test
    void defaults() {
        FieldSpec s = builder("loan_number", "Loan number", FieldType.TEXT).build();
        assertFalse(s.required());
        assertFalse(s.searchable());
        assertEquals(SearchMode.EXACT, s.searchMode());
        assertTrue(s.displayable());
        assertTrue(s.active());
        assertFalse(s.sensitive());
        assertFalse(s.multiValue());
        assertFalse(s.primary());
        assertTrue(s.normalisation().isNone());
        assertEquals(List.of(), s.options());
    }

    @Test
    void nameAndAddressFieldsAreSensitiveByDefault() {
        for (String key : List.of("first_name", "last_name", "borrower_name", "FullName", "surname", "name",
                "mailing_address", "street")) {
            assertTrue(builder(key, "Field", FieldType.TEXT).build().sensitive(), key);
        }
        assertTrue(builder("b1", "Borrower name", FieldType.TEXT).build().sensitive(), "by label too");
        assertTrue(builder("home", "Home", FieldType.ADDRESS).build().sensitive(), "every ADDRESS field");
        for (String key : List.of("loan_number", "contract_no", "filename", "renamed_at", "amount")) {
            assertFalse(builder(key, "Number", FieldType.TEXT).build().sensitive(), key);
        }
        assertFalse(builder("first_name", "First name", FieldType.TEXT).sensitive(false).build().sensitive(),
                "the Business Admin decides");
        assertTrue(builder("loan_number", "Loan number", FieldType.TEXT).sensitive(true).build().sensitive());
    }

    @Test
    void moneyHasOneCurrency() {
        FieldSpec usd = builder("amount", "Amount", FieldType.MONEY).currency("USD").build();
        assertEquals("USD", usd.currency());
        assertEquals(2, usd.moneyDecimals());
        assertEquals(0, builder("yen", "Yen", FieldType.MONEY).currency("JPY").build().moneyDecimals());
        refused(Code.FIELD_CURRENCY_REQUIRED, () -> builder("a", "A", FieldType.MONEY).build());
        refused(Code.FIELD_CURRENCY_INVALID, () -> builder("a", "A", FieldType.MONEY).currency("usd").build());
        refused(Code.FIELD_CURRENCY_INVALID, () -> builder("a", "A", FieldType.MONEY).currency("ZZZ").build());
        refused(Code.FIELD_CURRENCY_INVALID, () -> builder("a", "A", FieldType.MONEY).currency("XAU").build());
        refused(Code.FIELD_RULE_NOT_FOR_TYPE, () -> builder("a", "A", FieldType.TEXT).currency("USD").build());
    }

    @Test
    void dropdownsHaveUpTo100DistinctValues() {
        FieldSpec d = builder("loan_type", "Loan type", FieldType.DROPDOWN).options(" Fixed ", "Variable").build();
        assertEquals(List.of("Fixed", "Variable"), d.options());
        List<String> hundred = IntStream.rangeClosed(1, 100).mapToObj(i -> "v" + i).toList();
        assertEquals(100, builder("a", "A", FieldType.DROPDOWN).options(hundred).build().options().size());
        List<String> more = IntStream.rangeClosed(1, 101).mapToObj(i -> "v" + i).toList();
        refused(Code.FIELD_OPTIONS_TOO_MANY, () -> builder("a", "A", FieldType.DROPDOWN).options(more).build());
        refused(Code.FIELD_OPTIONS_REQUIRED, () -> builder("a", "A", FieldType.DROPDOWN).build());
        refused(Code.FIELD_OPTIONS_REQUIRED, () -> builder("a", "A", FieldType.DROPDOWN).options(List.of()).build());
        refused(Code.FIELD_OPTION_INVALID, () -> builder("a", "A", FieldType.DROPDOWN).options("x", " ").build());
        refused(Code.FIELD_OPTION_INVALID, () -> builder("a", "A", FieldType.DROPDOWN).options("x\ny").build());
        refused(Code.FIELD_OPTION_INVALID, () -> builder("a", "A", FieldType.DROPDOWN)
                .options(Collections.nCopies(1, "x".repeat(513))).build());
        refused(Code.FIELD_OPTION_REPEATED, () -> builder("a", "A", FieldType.DROPDOWN).options("Yes", "Yes").build());
        refused(Code.FIELD_OPTION_REPEATED, () -> builder("a", "A", FieldType.DROPDOWN)
                .normalisation(Normalisation.LetterCase.UPPER, Normalisation.Strip.NONE).options("yes", "YES").build());
        refused(Code.FIELD_RULE_NOT_FOR_TYPE, () -> builder("a", "A", FieldType.TEXT).options("x").build());
    }

    @Test
    void lengthRules() {
        FieldSpec s = builder("a", "A", FieldType.TEXT).minLength(2).maxLength(10).build();
        assertEquals(2, s.minLength());
        builder("a", "A", FieldType.NUMBER).exactLength(9).build();
        builder("a", "A", FieldType.EMAIL).maxLength(512).build();
        builder("a", "A", FieldType.CUSTOM_PATTERN).pattern("[A-Z]+").minLength(1).build();
        refused(Code.FIELD_LENGTH_INVALID, () -> builder("a", "A", FieldType.TEXT).exactLength(5).maxLength(6).build());
        refused(Code.FIELD_LENGTH_INVALID, () -> builder("a", "A", FieldType.TEXT).minLength(6).maxLength(5).build());
        refused(Code.FIELD_LENGTH_INVALID, () -> builder("a", "A", FieldType.TEXT).maxLength(0).build());
        refused(Code.FIELD_LENGTH_INVALID, () -> builder("a", "A", FieldType.TEXT).maxLength(513).build());
        for (FieldType t : List.of(FieldType.MONEY, FieldType.DATE, FieldType.UUID, FieldType.DROPDOWN,
                FieldType.ADDRESS)) {
            FieldSpec.Builder b = base(t).maxLength(5);
            refused(Code.FIELD_RULE_NOT_FOR_TYPE, b::build);
        }
    }

    @Test
    void patterns() {
        refused(Code.FIELD_PATTERN_REQUIRED, () -> builder("a", "A", FieldType.CUSTOM_PATTERN).build());
        refused(Code.FIELD_PATTERN_INVALID, () -> builder("a", "A", FieldType.CUSTOM_PATTERN).pattern("[a-").build());
        refused(Code.FIELD_PATTERN_INVALID, () -> builder("a", "A", FieldType.TEXT).pattern("a".repeat(513)).build());
        builder("a", "A", FieldType.TEXT).pattern("[A-Za-z0-9]+").build();
        builder("a", "A", FieldType.NUMBER).pattern("[0-9]+").build();
        for (FieldType t : List.of(FieldType.MONEY, FieldType.DATE, FieldType.UUID, FieldType.DROPDOWN,
                FieldType.ADDRESS)) {
            FieldSpec.Builder b = base(t).pattern("x");
            refused(Code.FIELD_RULE_NOT_FOR_TYPE, b::build);
        }
    }

    @Test
    void normalisationOnlyForTextLikeTypes() {
        Normalisation upper = Normalisation.of(Normalisation.LetterCase.UPPER, Normalisation.Strip.SEPARATORS);
        for (FieldType t : List.of(FieldType.TEXT, FieldType.NUMBER, FieldType.EMAIL, FieldType.DROPDOWN,
                FieldType.CUSTOM_PATTERN)) {
            assertEquals(upper, base(t).normalisation(upper).build().normalisation());
        }
        for (FieldType t : List.of(FieldType.MONEY, FieldType.DATE, FieldType.UUID, FieldType.ADDRESS)) {
            FieldSpec.Builder b = base(t).normalisation(upper);
            refused(Code.FIELD_RULE_NOT_FOR_TYPE, b::build);
        }
    }

    @Test
    void ranges() {
        FieldSpec n = builder("n", "N", FieldType.NUMBER).range("0", "100").build();
        assertEquals(new BigDecimal("100"), n.maxValue());
        FieldSpec m = builder("m", "M", FieldType.MONEY).currency("USD").range("0", "10.5").build();
        assertEquals(new BigDecimal("10.50"), m.maxValue(), "kept at the currency's decimals");
        refused(Code.FIELD_RANGE_INVALID, () -> builder("n", "N", FieldType.NUMBER).range("0.5", null).build());
        refused(Code.FIELD_RANGE_INVALID, () -> builder("n", "N", FieldType.NUMBER).range("10", "1").build());
        refused(Code.FIELD_RANGE_INVALID, () -> builder("m", "M", FieldType.MONEY).currency("USD")
                .range("0.001", null).build());
        refused(Code.FIELD_RULE_NOT_FOR_TYPE, () -> builder("t", "T", FieldType.TEXT).range("1", null).build());
        refused(Code.FIELD_RULE_NOT_FOR_TYPE, () -> builder("d", "D", FieldType.DATE).range("1", null).build());
    }

    @Test
    void flagRules() {
        refused(Code.FIELD_SENSITIVE_EXACT_ONLY, () -> builder("first_name", "First name", FieldType.TEXT)
                .searchable(SearchMode.PARTIAL).build());
        refused(Code.FIELD_SENSITIVE_EXACT_ONLY, () -> builder("loan_number", "Loan number", FieldType.TEXT)
                .sensitive(true).searchable(SearchMode.PREFIX).build());
        assertEquals(SearchMode.PARTIAL, builder("loan_number", "Loan number", FieldType.TEXT)
                .searchable(SearchMode.PARTIAL).build().searchMode(), "partial search for fields that are not sensitive");
        assertEquals(SearchMode.EXACT, builder("first_name", "First name", FieldType.TEXT)
                .searchable(SearchMode.EXACT).build().searchMode());
        refused(Code.FIELD_SEARCH_MODE_INVALID, () -> new FieldSpec(FieldKey.of("a"), "A", FieldType.TEXT, null, null,
                false, null, null, null, null, null, null, null, false, false, SearchMode.PARTIAL, false, false, true, 0,
                true));
        refused(Code.FIELD_PRIMARY_MULTI_VALUE, () -> builder("a", "A", FieldType.TEXT).primary(true).multiValue(true)
                .build());
        refused(Code.FIELD_PRIMARY_INACTIVE, () -> builder("a", "A", FieldType.TEXT).primary(true).active(false)
                .build());
        refused(Code.FIELD_DISPLAY_ORDER_INVALID, () -> builder("a", "A", FieldType.TEXT).displayOrder(-1).build());
        refused(Code.FIELD_DISPLAY_ORDER_INVALID, () -> builder("a", "A", FieldType.TEXT).displayOrder(10_001).build());
        assertTrue(builder("borrowers", "Borrowers", FieldType.TEXT).multiValue(true).build().multiValue());
    }

    @Test
    void sameValueKindMeansTypeAndCurrency() {
        FieldSpec usd = builder("a", "A", FieldType.MONEY).currency("USD").build();
        assertTrue(usd.sameValueKind(usd.toBuilder().label("B").build()));
        assertFalse(usd.sameValueKind(usd.toBuilder().currency("EUR").build()));
        assertFalse(usd.sameValueKind(builder("a", "A", FieldType.NUMBER).build()));
        assertEquals(usd, usd.toBuilder().build(), "toBuilder keeps everything");
    }

    static FieldSpec.Builder base(FieldType t) {
        FieldSpec.Builder b = builder("f", "F", t);
        return switch (t) {
            case MONEY -> b.currency("USD");
            case DROPDOWN -> b.options("A", "B");
            case CUSTOM_PATTERN -> b.pattern("[A-Z]+");
            default -> b;
        };
    }

    static void refused(Code code, Executable e) {
        FieldDefinitionException ex = assertThrows(FieldDefinitionException.class, e);
        assertEquals(code, ex.code(), ex.getMessage());
    }
}
