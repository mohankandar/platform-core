package com.documentplatform.fields.delivery;

import com.documentplatform.fields.FieldKey;
import com.documentplatform.fields.FieldSet;
import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.FieldType;
import com.documentplatform.fields.FieldValue;
import com.documentplatform.fields.Normalisation;
import com.documentplatform.fields.ValidValue;
import com.documentplatform.fields.ValidatedFields;
import com.documentplatform.fields.ValueError;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.documentplatform.fields.FieldSpec.builder;
import static com.documentplatform.fields.FieldValue.text;
import static com.documentplatform.fields.delivery.DeliveryProblem.Code.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sender writes key + type + value; the receiver checks every entry against its own field list. */
class FieldDeliveryTest {

    static final String SECRET_NAME = "Wilhelmina Featherstonehaugh";

    static final FieldSet SENDER = FieldSet.of(List.of(
            builder("loan_number", "Loan number", FieldType.TEXT).required(true).primary(true)
                    .normalisation(Normalisation.LetterCase.UPPER, Normalisation.Strip.SEPARATORS).build(),
            builder("loan_amount", "Loan amount", FieldType.MONEY).currency("USD").build(),
            builder("borrower_name", "Borrower name", FieldType.TEXT).multiValue(true).build(),
            builder("property_address", "Property address", FieldType.ADDRESS).build(),
            builder("loan_type", "Loan type", FieldType.DROPDOWN).options("Fixed", "Variable").build()));

    /** The receiver's list: the same keys and types, its own labels and rules, one field of its own. */
    static final FieldSet RECEIVER = FieldSet.of(List.of(
            builder("loan_number", "Loan no.", FieldType.TEXT).required(true).build(),
            builder("loan_amount", "Amount", FieldType.MONEY).currency("USD").build(),
            builder("borrower_name", "Borrowers", FieldType.TEXT).multiValue(true).build(),
            builder("property_address", "Address", FieldType.ADDRESS).build(),
            builder("loan_type", "Type", FieldType.DROPDOWN).options("Fixed", "Variable").build(),
            builder("box_number", "Box number", FieldType.NUMBER).build()));

    static ValidatedFields caseValues() {
        Map<String, List<FieldValue>> in = new LinkedHashMap<>();
        in.put("loan_number", List.of(text("ln-2026 0042")));
        in.put("loan_amount", List.of(text("$250,000")));
        in.put("borrower_name", List.of(text(SECRET_NAME), text("Bo Lee")));
        in.put("property_address", List.of(FieldValue.address("1 Main St", "Springfield", "il", "62701")));
        in.put("loan_type", List.of(text("Fixed")));
        ValidatedFields v = SENDER.validate(in);
        assertTrue(v.accepted(), v.problems().toString());
        return v;
    }

    @Test
    void roundTrip() {
        String doc = FieldDelivery.write(SENDER, caseValues());
        // In the sender's display order; all are 0 here, so by key.
        assertEquals("{\"fields\":["
                + "{\"key\":\"borrower_name\",\"type\":\"TEXT\",\"values\":[\"" + SECRET_NAME + "\",\"Bo Lee\"]},"
                + "{\"currency\":\"USD\",\"key\":\"loan_amount\",\"type\":\"MONEY\",\"value\":\"$250,000\"},"
                + "{\"key\":\"loan_number\",\"type\":\"TEXT\",\"value\":\"ln-2026 0042\"},"
                + "{\"key\":\"loan_type\",\"type\":\"DROPDOWN\",\"value\":\"Fixed\"},"
                + "{\"key\":\"property_address\",\"type\":\"ADDRESS\",\"value\":{\"city\":\"Springfield\","
                + "\"country\":\"US\",\"state\":\"IL\",\"street\":\"1 Main St\",\"zip\":\"62701\"}}],"
                + "\"format\":\"platform-field-values/1\"}", doc);
        DeliveryResult r = FieldDelivery.verify(RECEIVER, doc);
        assertTrue(r.accepted(), r.problems().toString());
        assertEquals(List.of("borrower_name", "loan_amount", "loan_number", "loan_type", "property_address"),
                r.values().keySet().stream().map(FieldKey::value).toList());
        assertEquals("250000.00", r.values().get(FieldKey.of("loan_amount")).get(0).normalisedText());
        assertEquals("ln-2026 0042", r.values().get(FieldKey.of("loan_number")).get(0).normalisedText(),
                "the receiver applies its own normalisation");
        List<ValidValue> borrowers = r.values().get(FieldKey.of("borrower_name"));
        assertEquals(2, borrowers.size());
        assertEquals(FieldValue.address("1 Main St", "Springfield", "IL", "62701").street(),
                ((FieldValue.Address) r.values().get(FieldKey.of("property_address")).get(0).display()).street());
        assertThrows(IllegalArgumentException.class,
                () -> FieldDelivery.write(SENDER, SENDER.validate(Map.of())), "only accepted values are sent");
    }

    @Test
    void unknownKeysAndTypeMismatchesHaveTheirCodes() {
        assertCodes(entries("{\"key\":\"loan_number\",\"type\":\"TEXT\",\"value\":\"LN-1\"}",
                "{\"key\":\"secret_extra\",\"type\":\"TEXT\",\"value\":\"" + SECRET_NAME + "\"}"),
                DELIVERY_KEY_UNKNOWN);
        assertCodes(entries("{\"key\":\"loan_number\",\"type\":\"NUMBER\",\"value\":\"1\"}"),
                DELIVERY_TYPE_MISMATCH);
        assertCodes(entries("{\"key\":\"loan_number\",\"type\":\"TEXT\",\"value\":\"LN-1\"}",
                        "{\"key\":\"loan_amount\",\"type\":\"MONEY\",\"currency\":\"EUR\",\"value\":\"5\"}"),
                DELIVERY_CURRENCY_MISMATCH);
        assertCodes(entries("{\"key\":\"loan_number\",\"type\":\"TEXT\",\"value\":\"LN-1\"}",
                        "{\"key\":\"loan_amount\",\"type\":\"MONEY\",\"value\":\"5\"}"),
                DELIVERY_CURRENCY_MISMATCH);
        assertCodes(entries("{\"key\":\"loan_number\",\"type\":\"TEXT\",\"value\":\"LN-1\"}",
                        "{\"key\":\"Loan_Amount\",\"type\":\"MONEY\",\"currency\":\"USD\",\"value\":\"5\"}"),
                DELIVERY_KEY_UNKNOWN);
        DeliveryResult mismatch = FieldDelivery.verify(RECEIVER, entries(
                "{\"key\":\"loan_number\",\"type\":\"TEXT\",\"value\":\"LN-1\"}",
                "{\"key\":\"box_number\",\"type\":\"TEXT\",\"value\":\"12\"}"));
        assertEquals("Entry 2: box_number is NUMBER here, and the delivery says TEXT",
                mismatch.problems().get(0).message());
        assertEquals(2, mismatch.problems().get(0).entry());
        assertEquals(List.of(), List.copyOf(mismatch.values().keySet()), "nothing is accepted from a refused delivery");
    }

    @Test
    void everyOtherRefusalHasItsCode() {
        assertCodes("{not json", DELIVERY_UNREADABLE);
        assertCodes(null, DELIVERY_TOO_LARGE);
        assertCodes("{\"format\":\"platform-field-values/2\",\"fields\":[]}", DELIVERY_FORMAT_UNKNOWN);
        assertCodes("[]", DELIVERY_SHAPE_INVALID);
        assertCodes(entries("{\"key\":\"loan_number\",\"type\":\"TEXT\"}"), DELIVERY_SHAPE_INVALID,
                DELIVERY_REQUIRED_MISSING);
        assertCodes(entries("{\"key\":\"loan_number\",\"type\":\"TEXT\",\"value\":\"a\",\"values\":[\"b\"]}"),
                DELIVERY_SHAPE_INVALID, DELIVERY_REQUIRED_MISSING);
        assertCodes(entries("{\"key\":\"loan_number\",\"type\":\"TEXT\",\"value\":5}"), DELIVERY_SHAPE_INVALID);
        assertCodes(entries("{\"key\":\"loan_number\",\"type\":\"TEXT\",\"value\":\"a\",\"currency\":\"USD\"}"),
                DELIVERY_SHAPE_INVALID);
        assertCodes(entries("{\"key\":\"9bad\",\"type\":\"TEXT\",\"value\":\"a\"}"), DELIVERY_KEY_INVALID,
                DELIVERY_REQUIRED_MISSING);
        assertCodes(entries("{\"key\":\"loan_number\",\"type\":\"TEXT\",\"value\":\"a\"}",
                "{\"key\":\"loan_number\",\"type\":\"TEXT\",\"value\":\"b\"}"), DELIVERY_KEY_REPEATED);
        assertCodes(entries("{\"key\":\"loan_number\",\"type\":\"TEXT\",\"values\":[\"a\"]}"),
                DELIVERY_VALUE_COUNT_MISMATCH);
        assertCodes(entries("{\"key\":\"loan_number\",\"type\":\"TEXT\",\"value\":\"LN-1\"}",
                "{\"key\":\"borrower_name\",\"type\":\"TEXT\",\"value\":\"Ann\"}"), DELIVERY_VALUE_COUNT_MISMATCH);
        assertCodes(entries("{\"key\":\"loan_number\",\"type\":\"TEXT\",\"value\":\"LN-1\"}",
                "{\"key\":\"property_address\",\"type\":\"ADDRESS\",\"value\":\"1 Main St\"}"), DELIVERY_SHAPE_INVALID);
        assertCodes(entries("{\"key\":\"box_number\",\"type\":\"NUMBER\",\"value\":\"7\"}"),
                DELIVERY_REQUIRED_MISSING);

        DeliveryResult invalid = FieldDelivery.verify(RECEIVER, entries(
                "{\"key\":\"loan_number\",\"type\":\"TEXT\",\"value\":\"LN-1\"}",
                "{\"key\":\"loan_type\",\"type\":\"DROPDOWN\",\"value\":\"Balloon\"}",
                "{\"key\":\"property_address\",\"type\":\"ADDRESS\",\"value\":{\"street\":\"1 Main St\","
                        + "\"city\":\"Springfield\",\"state\":\"ZZ\",\"zip\":\"62701\"}}"));
        assertEquals(List.of(DELIVERY_VALUE_INVALID, DELIVERY_VALUE_INVALID), invalid.problems().stream()
                .map(DeliveryProblem::code).toList());
        assertEquals(List.of(ValueError.VALUE_NOT_AN_OPTION, ValueError.ADDRESS_STATE_INVALID),
                invalid.problems().stream().map(DeliveryProblem::valueError).toList());
        assertEquals("Entry 2: Type must be one of the listed values", invalid.problems().get(0).message());

        FieldSet withOff = FieldSet.of(List.of(builder("loan_number", "Loan no.", FieldType.TEXT).build(),
                builder("old_ref", "Old reference", FieldType.TEXT).active(false).build()));
        assertEquals(List.of(DELIVERY_FIELD_INACTIVE), FieldDelivery.verify(withOff,
                entries("{\"key\":\"old_ref\",\"type\":\"TEXT\",\"value\":\"R\"}")).codes());
    }

    @Test
    void noMessageRepeatsAValueOrAMemberTheSenderMadeUp() {
        String sneaky = "{\"key\":\"borrower_name\",\"type\":\"TEXT\",\"values\":[\"" + SECRET_NAME
                + "\",\"" + SECRET_NAME + "\"]},"
                + "{\"key\":\"loan_number\",\"type\":\"TEXT\",\"value\":\"LN\",\"" + SECRET_NAME + "\":\"x\"},"
                + "{\"key\":\"loan_type\",\"type\":\"" + SECRET_NAME + "\",\"value\":\"" + SECRET_NAME + "\"},"
                + "{\"key\":\"loan_amount\",\"type\":\"MONEY\",\"currency\":\"" + SECRET_NAME + "\",\"value\":\"1\"},"
                + "{\"key\":\"property_address\",\"type\":\"ADDRESS\",\"value\":{\"street\":\"" + SECRET_NAME
                + "\",\"city\":\"" + SECRET_NAME + "\",\"state\":\"" + SECRET_NAME + "\",\"zip\":\"1\"}}";
        DeliveryResult r = FieldDelivery.verify(RECEIVER, entries(sneaky));
        assertFalse(r.accepted());
        assertEquals(List.of(DELIVERY_VALUE_INVALID, DELIVERY_SHAPE_INVALID, DELIVERY_TYPE_MISMATCH,
                DELIVERY_CURRENCY_MISMATCH, DELIVERY_VALUE_INVALID, DELIVERY_REQUIRED_MISSING),
                r.problems().stream().map(DeliveryProblem::code).toList(), r.problems().toString());
        assertFalse(r.toString().contains("Wilhelmina"), r.toString());
        assertFalse(FieldDelivery.verify(RECEIVER, "{\"format\":\"" + SECRET_NAME + "\"}").toString()
                .contains("Wilhelmina"));
        assertFalse(FieldDelivery.verify(RECEIVER, "{\"x\":\"" + SECRET_NAME).toString().contains("Wilhelmina"));
    }

    private static String entries(String... entries) {
        return "{\"format\":\"platform-field-values/1\",\"fields\":[" + String.join(",", entries) + "]}";
    }

    private static void assertCodes(String doc, DeliveryProblem.Code... codes) {
        DeliveryResult r = FieldDelivery.verify(RECEIVER, doc);
        assertFalse(r.accepted());
        assertEquals(List.of(codes), r.codes(), r.problems().toString());
    }
}
