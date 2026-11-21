package com.documentplatform.fields.jdbc;

import com.documentplatform.fields.FieldDefinitionException;
import com.documentplatform.fields.FieldDefinitions;
import com.documentplatform.fields.FieldKey;
import com.documentplatform.fields.FieldScope;
import com.documentplatform.fields.FieldSet;
import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.FieldType;
import com.documentplatform.fields.FieldValidator;
import com.documentplatform.fields.FieldValue;
import com.documentplatform.fields.ValidatedFields;
import com.documentplatform.fields.delivery.DeliveryResult;
import com.documentplatform.fields.delivery.FieldDelivery;
import com.documentplatform.fields.file.FieldFileException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static com.documentplatform.fields.FieldSpec.builder;
import static com.documentplatform.fields.FieldValue.text;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sensitive values (a borrower's name and address) go through every path: field set checks, refusals, the delivery
 * written and verified (accepted and refused), the field list file, the Postgres store and the rules. None of them
 * may appear in any log record at any level, any problem message, any exception or any {@code toString()}.
 */
class NoValueInLogsTest {

    static final String NAME = "Philippa Oyelaran-Whitcombe";
    static final String STREET = "742 Quillfeather Lane";
    static final String MARKERS[] = {"Philippa", "Oyelaran", "Quillfeather"};

    @Test
    void sensitiveValuesNeverLeave() {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        Handler capture = new Handler() {
            private final SimpleFormatter f = new SimpleFormatter();

            @Override
            public void publish(LogRecord r) {
                seen.add(f.format(r));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        capture.setLevel(Level.ALL);
        Logger root = Logger.getLogger("");
        Level before = root.getLevel();
        root.addHandler(capture);
        root.setLevel(Level.ALL);
        List<String> texts = new ArrayList<>();
        try {
            Pg db = Pg.migrated();
            FieldDefinitions fields = new FieldDefinitions(new PostgresFieldStore(db.appUser(
                    "select, insert, update, delete")), (s, k) -> true);
            FieldScope scope = FieldScope.of(UUID.randomUUID(), "PRODUCT_A");
            FieldSpec borrower = fields.create(scope, builder("borrower_name", "Borrower name", FieldType.TEXT)
                    .multiValue(true).searchable(true).build(), "u-admin").spec();
            FieldSpec address = fields.create(scope, builder("property_address", "Property address",
                    FieldType.ADDRESS).build(), "u-admin").spec();
            fields.create(scope, builder("loan_number", "Loan number", FieldType.TEXT).required(true).maxLength(8)
                    .build(), "u-admin");
            assertTrue(borrower.sensitive() && address.sensitive(), "names and addresses are sensitive by default");
            FieldSet set = fields.fieldSet(scope);

            Map<String, List<FieldValue>> good = new LinkedHashMap<>();
            good.put("loan_number", List.of(text("LN-1")));
            good.put("borrower_name", List.of(text(NAME)));
            good.put("property_address", List.of(FieldValue.address(STREET, "Springfield", "IL", "62701")));
            ValidatedFields ok = set.validate(good);
            assertTrue(ok.accepted());
            texts.add(ok.toString());
            texts.add(ok.values().toString());
            String delivery = FieldDelivery.write(set, ok);
            DeliveryResult received = FieldDelivery.verify(set, delivery);
            assertTrue(received.accepted());
            texts.add(received.toString());
            texts.add(received.values().toString());

            Map<String, List<FieldValue>> bad = new LinkedHashMap<>();
            bad.put("loan_number", List.of(text(NAME)));
            bad.put("borrower_name", List.of(text(NAME), text(NAME + "\n")));
            bad.put("property_address", List.of(FieldValue.address(STREET, NAME, NAME, NAME)));
            bad.put("not_a_field", List.of(text(NAME)));
            ValidatedFields refused = set.validate(bad);
            assertFalse(refused.accepted());
            texts.add(refused.toString());
            texts.add(FieldValidator.check(borrower, FieldValue.address(STREET, NAME, "IL", "1")).toString());
            DeliveryResult no = FieldDelivery.verify(set, delivery.replace("LN-1", NAME).replace("IL", NAME)
                    .replace("\"TEXT\",\"values\"", "\"" + NAME + "\",\"values\""));
            assertFalse(no.accepted());
            texts.add(no.toString());
            texts.add(FieldDelivery.verify(set, delivery.substring(0, delivery.indexOf(NAME) + 5)).toString());

            FieldFileException fileError = assertThrows(FieldFileException.class,
                    () -> fields.previewImport(scope, delivery));
            texts.add(fileError.getMessage());
            FieldDefinitionException locked = assertThrows(FieldDefinitionException.class, () -> fields.change(scope,
                    borrower.toBuilder().type(FieldType.EMAIL).build(), 1, "u-admin"));
            texts.add(locked.getMessage());
            texts.add(fields.exportFile(scope));
            texts.add(fields.history(scope, FieldKey.of("borrower_name")).toString());
        } finally {
            root.removeHandler(capture);
            root.setLevel(before);
        }
        assertFalse(seen.isEmpty(), "the capture works");
        assertTrue(seen.stream().anyMatch(s -> s.contains("borrower_name")), "keys are logged");
        for (String marker : MARKERS) {
            for (String line : seen) {
                assertFalse(line.contains(marker), "a log record holds a value: " + line);
            }
            for (String t : texts) {
                assertFalse(t.contains(marker), "a message holds a value: " + t);
            }
        }
    }
}
