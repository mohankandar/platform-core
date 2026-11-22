package com.documentplatform.fields;

import com.documentplatform.fields.FieldDefinitionException.Code;
import com.documentplatform.fields.file.FieldFileException;
import com.documentplatform.fields.file.FieldListFile;
import com.documentplatform.fields.file.ImportReport;
import com.documentplatform.fields.inmemory.InMemoryFieldStore;
import com.documentplatform.fields.inmemory.TestValuesCheck;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.documentplatform.fields.FieldSpec.builder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rules for changing definitions, with the in-memory store an app uses in its tests. */
class FieldDefinitionsTest {

    static final FieldScope ENTITY = FieldScope.of(UUID.randomUUID(), "PRODUCT_A");
    static final FieldScope OTHER = FieldScope.of(UUID.randomUUID(), "PRODUCT_A");
    static final FieldKey LOAN = FieldKey.of("loan_amount");

    private final InMemoryFieldStore store = new InMemoryFieldStore();
    private final TestValuesCheck values = new TestValuesCheck();
    private final FieldDefinitions fields = new FieldDefinitions(store, values,
            Clock.fixed(Instant.parse("2026-11-21T10:00:00Z"), ZoneOffset.UTC));

    private FieldDefinition loanAmount() {
        return fields.create(ENTITY, builder("loan_amount", "Loan amount", FieldType.MONEY).currency("USD").build(),
                "u-admin");
    }

    @Test
    void theLabelMayChangeAtAnyTime() {
        loanAmount();
        values.valuesExistFor(ENTITY, LOAN);
        FieldDefinition d = fields.relabel(ENTITY, LOAN, "Amount borrowed", "u-admin");
        assertEquals("Amount borrowed", d.spec().label());
        assertEquals(2, d.version());
        FieldDefinition rules = fields.change(ENTITY, d.spec().toBuilder().required(true).range("0", "1000000")
                .searchable(true).displayOrder(3).build(), 2, "u-admin");
        assertTrue(rules.spec().required(), "rules and flags may change too");
    }

    @Test
    void theTypeIsLockedOnceValuesExist() {
        FieldDefinition d = loanAmount();
        values.valuesExistFor(ENTITY, LOAN);
        FieldDefinitionException e = refused(Code.FIELD_TYPE_LOCKED, () -> fields.change(ENTITY,
                builder("loan_amount", "Loan amount", FieldType.NUMBER).build(), d.version(), "u-admin"));
        assertEquals("Field loan_amount already has values, so its type stays MONEY (USD); add a new field for the "
                + "other type", e.getMessage());
        refused(Code.FIELD_TYPE_LOCKED, () -> fields.change(ENTITY, d.spec().toBuilder().currency("EUR").build(),
                d.version(), "u-admin"));
        assertEquals(1, fields.find(ENTITY, LOAN).orElseThrow().version(), "nothing changed");
    }

    @Test
    void theSensitiveFlagIsLockedOnceValuesExist() {
        FieldKey name = FieldKey.of("borrower_name");
        FieldDefinition plain = fields.create(ENTITY, builder("borrower_name", "Borrower", FieldType.TEXT)
                .sensitive(false).build(), "u-admin");
        FieldDefinition sealed = fields.create(ENTITY, builder("borrower_address", "Address", FieldType.TEXT)
                .sensitive(true).build(), "u-admin");
        values.valuesExistFor(ENTITY, name);
        values.valuesExistFor(ENTITY, FieldKey.of("borrower_address"));
        FieldDefinitionException on = refused(Code.FIELD_SENSITIVE_LOCKED, () -> fields.change(ENTITY,
                plain.spec().toBuilder().sensitive(true).build(), plain.version(), "u-admin"));
        assertEquals("Field borrower_name already has values, so it stays not sensitive; add a new field to store "
                + "such values the other way", on.getMessage());
        refused(Code.FIELD_SENSITIVE_LOCKED, () -> fields.change(ENTITY,
                sealed.spec().toBuilder().sensitive(false).build(), sealed.version(), "u-admin"));
        assertFalse(fields.find(ENTITY, name).orElseThrow().spec().sensitive(), "nothing changed");
        assertEquals(1, fields.find(ENTITY, name).orElseThrow().version());
        assertEquals("Name", fields.relabel(ENTITY, name, "Name", "u-admin").spec().label(),
                "other changes still go through");
    }

    @Test
    void thePrimaryFlagIsLockedOnceValuesExist() {
        FieldKey contract = FieldKey.of("contract_number");
        FieldDefinition d = fields.create(ENTITY, builder("contract_number", "Contract", FieldType.TEXT)
                .primary(true).build(), "u-admin");
        FieldDefinition secondary = fields.create(ENTITY, builder("member_number", "Member", FieldType.TEXT)
                .build(), "u-admin");
        values.valuesExistFor(ENTITY, contract);
        values.valuesExistFor(ENTITY, FieldKey.of("member_number"));
        FieldDefinitionException off = refused(Code.FIELD_PRIMARY_LOCKED, () -> fields.change(ENTITY,
                d.spec().toBuilder().primary(false).build(), d.version(), "u-admin"));
        assertEquals("Field contract_number already has values, so it stays a primary identifier; add a new field "
                + "instead", off.getMessage());
        refused(Code.FIELD_PRIMARY_LOCKED, () -> fields.change(ENTITY,
                secondary.spec().toBuilder().primary(true).build(), secondary.version(), "u-admin"));
        assertTrue(fields.find(ENTITY, contract).orElseThrow().spec().primary(), "nothing changed");
        FieldDefinition type = fields.create(ENTITY, builder("loan_number", "Loan", FieldType.TEXT).build(),
                "u-admin");
        values.valuesExistFor(ENTITY, FieldKey.of("loan_number"));
        refused(Code.FIELD_TYPE_LOCKED, () -> fields.change(ENTITY, type.spec().toBuilder().type(FieldType.NUMBER)
                .primary(true).build(), type.version(), "u-admin"));
    }

    @Test
    void beforeValuesExistSensitiveAndPrimaryMayChange() {
        FieldDefinition d = fields.create(ENTITY, builder("contract_number", "Contract", FieldType.TEXT).build(),
                "u-admin");
        FieldDefinition changed = fields.change(ENTITY, d.spec().toBuilder().primary(true).sensitive(true).build(),
                d.version(), "u-admin");
        assertTrue(changed.spec().primary());
        assertTrue(changed.spec().sensitive());
    }

    @Test
    void beforeValuesExistTheTypeMayChange() {
        FieldDefinition d = loanAmount();
        FieldDefinition n = fields.change(ENTITY, builder("loan_amount", "Loan amount", FieldType.NUMBER).build(),
                d.version(), "u-admin");
        assertEquals(FieldType.NUMBER, n.type());
        FieldDefinition other = fields.create(OTHER, builder("loan_amount", "Loan amount", FieldType.TEXT).build(),
                "u-admin");
        values.valuesExistFor(ENTITY, LOAN);
        assertEquals(FieldType.DATE, fields.change(OTHER, builder("loan_amount", "Loan amount", FieldType.DATE)
                .build(), other.version(), "u-admin").type(), "values of another entity do not count");
    }

    @Test
    void whenTheAppCannotSayIfValuesExistTheTypeChangeIsRefused() {
        FieldDefinition d = loanAmount();
        values.unavailable(true);
        assertThrows(IllegalStateException.class, () -> fields.change(ENTITY,
                builder("loan_amount", "Loan amount", FieldType.NUMBER).build(), d.version(), "u-admin"));
        assertEquals(FieldType.MONEY, fields.find(ENTITY, LOAN).orElseThrow().type());
        assertEquals("Amount", fields.relabel(ENTITY, LOAN, "Amount", "u-admin").spec().label(),
                "a label change does not need the check");
    }

    @Test
    void theKeyNeverChanges() {
        FieldDefinition d = loanAmount();
        refused(Code.FIELD_KEY_LOCKED, () -> fields.change(ENTITY, builder("Loan_Amount", "Loan amount",
                FieldType.MONEY).currency("USD").build(), d.version(), "u-admin"));
        refused(Code.FIELD_UNKNOWN, () -> fields.change(ENTITY, builder("loan_sum", "Loan amount",
                FieldType.MONEY).currency("USD").build(), d.version(), "u-admin"));
        refused(Code.FIELD_KEY_TAKEN, () -> fields.create(ENTITY, builder("LOAN_AMOUNT", "Again", FieldType.TEXT)
                .build(), "u-admin"));
        refused(Code.FIELD_KEY_LOCKED, () -> store.update(d, builder("loan_sum", "X", FieldType.TEXT).build(),
                FieldStore.Change.CHANGED, "u", Instant.now()));
    }

    @Test
    void removingAFieldSwitchesItOffAndKeepsIt() {
        loanAmount();
        values.valuesExistFor(ENTITY, LOAN);
        FieldDefinition off = fields.switchOff(ENTITY, LOAN, "u-admin");
        assertFalse(off.active());
        assertEquals(List.of(off), fields.list(ENTITY));
        assertEquals(List.of(), fields.active(ENTITY));
        assertFalse(fields.fieldSet(ENTITY).validate(Map.of("loan_amount", List.of(FieldValue.text("5"))))
                .accepted(), "a switched-off field takes no new values");
        FieldDefinition on = fields.switchOn(ENTITY, LOAN, "u-admin");
        assertTrue(on.active());
        assertEquals(List.of(FieldStore.Change.CREATED, FieldStore.Change.SWITCHED_OFF,
                FieldStore.Change.SWITCHED_ON), fields.history(ENTITY, LOAN).stream()
                .map(FieldStore.HistoryEntry::change).toList());
        FieldDefinition changedWhileOff = fields.change(ENTITY, on.spec().toBuilder().active(false).label("X").build(),
                on.version(), "u-admin");
        assertTrue(changedWhileOff.active(), "change() keeps on/off as it is");
    }

    @Test
    void staleChangesAreRefused() {
        FieldDefinition d = loanAmount();
        fields.relabel(ENTITY, LOAN, "First", "u-a");
        refused(Code.FIELD_CHANGED_MEANWHILE, () -> fields.change(ENTITY, d.spec().toBuilder().label("Second")
                .build(), d.version(), "u-b"));
        FieldDefinition same = fields.find(ENTITY, LOAN).orElseThrow();
        assertEquals(same, fields.change(ENTITY, same.spec(), same.version(), "u-b"), "nothing changed, no version");
    }

    @Test
    void whoChangedItIsRequired() {
        refused(Code.FIELD_ACTOR_INVALID, () -> fields.create(ENTITY, builder("a", "A", FieldType.TEXT).build(), " "));
        refused(Code.FIELD_ACTOR_INVALID, () -> fields.create(ENTITY, builder("a", "A", FieldType.TEXT).build(),
                "u\n1"));
        assertEquals(List.of(), fields.list(ENTITY));
        FieldDefinition d = fields.create(ENTITY, builder("a", "A", FieldType.TEXT).build(), "u-admin");
        assertEquals("u-admin", fields.history(ENTITY, d.key()).get(0).by());
    }

    @Test
    void importFromTheOtherProductsExport() {
        FieldScope exporting = FieldScope.of(UUID.randomUUID(), "PRODUCT_A");
        FieldScope importing = FieldScope.of(UUID.randomUUID(), "PRODUCT_B");
        fields.create(exporting, builder("loan_number", "Loan number", FieldType.TEXT).primary(true).build(), "u-a");
        fields.create(exporting, builder("borrower_name", "Borrower name", FieldType.TEXT).multiValue(true).build(),
                "u-a");
        fields.create(exporting, builder("loan_amount", "Loan amount", FieldType.MONEY).currency("USD").build(), "u-a");
        fields.putExtension(exporting, FieldKey.of("loan_number"), "extractable", "true");
        String file = fields.exportFile(exporting);
        assertFalse(file.contains("extractable"), "app-only settings stay in the app");

        fields.create(importing, builder("loan_amount", "Amount", FieldType.NUMBER).build(), "u-b");
        values.valuesExistFor(importing, LOAN);
        ImportReport r = fields.importFile(importing, file, "u-b");
        assertEquals(2, r.created().size());
        assertEquals(1, r.conflicts().size());
        assertEquals(ImportReport.Conflict.TYPE_DIFFERS, r.conflicts().get(0).conflict());
        assertEquals(FieldType.NUMBER, fields.find(importing, LOAN).orElseThrow().type(), "never retyped");
        assertEquals(3, fields.list(importing).size());
        assertEquals(Map.of(), fields.extensions(importing, FieldKey.of("loan_number")));
        assertTrue(fields.find(importing, FieldKey.of("borrower_name")).orElseThrow().spec().sensitive());

        FieldFileException bad = assertThrows(FieldFileException.class,
                () -> fields.importFile(importing, file.replace("platform-fields/1", "x"), "u-b"));
        assertEquals(FieldFileException.Code.FILE_FORMAT_UNKNOWN, bad.code());
        assertEquals(3, fields.list(importing).size());
        assertEquals(FieldListFile.FORMAT, "platform-fields/1");
    }

    @Test
    void anImportIsAllOrNothing() {
        FieldScope s = FieldScope.of(UUID.randomUUID(), "PRODUCT_B");
        String file = FieldListFile.write(List.of(builder("a", "A", FieldType.TEXT).build(),
                builder("b", "B", FieldType.TEXT).build()), Instant.EPOCH);
        // Another admin creates "b" between the plan and the save: the store refuses, nothing is created.
        InMemoryFieldStore racing = new InMemoryFieldStore();
        FieldDefinitions raced = new FieldDefinitions(new RacingStore(racing, s), values);
        refused(Code.FIELD_KEY_TAKEN, () -> raced.importFile(s, file, "u-b"));
        assertEquals(List.of("b"), racing.list(s).stream().map(d -> d.key().value()).toList());
    }

    /** Creates "b" just before the import saves. */
    private record RacingStore(InMemoryFieldStore inner, FieldScope scope) implements FieldStore {
        @Override
        public List<FieldDefinition> list(FieldScope s) {
            return inner.list(s);
        }

        @Override
        public java.util.Optional<FieldDefinition> find(FieldScope s, FieldKey key) {
            return inner.find(s, key);
        }

        @Override
        public List<FieldDefinition> create(FieldScope s, List<FieldSpec> specs, Change change, String actor,
                                            Instant at) {
            if (specs.size() > 1) {
                inner.create(s, List.of(builder("b", "B", FieldType.TEXT).build()), Change.CREATED, "u-c", at);
            }
            return inner.create(s, specs, change, actor, at);
        }

        @Override
        public FieldDefinition update(FieldDefinition current, FieldSpec next, Change change, String actor,
                                      Instant at) {
            return inner.update(current, next, change, actor, at);
        }

        @Override
        public List<HistoryEntry> history(FieldScope s, FieldKey key) {
            return inner.history(s, key);
        }

        @Override
        public Map<String, String> extensions(FieldScope s, FieldKey key) {
            return inner.extensions(s, key);
        }

        @Override
        public void putExtension(FieldScope s, FieldKey key, String name, String value, Instant at) {
            inner.putExtension(s, key, name, value, at);
        }
    }

    private static FieldDefinitionException refused(Code code, Executable e) {
        FieldDefinitionException ex = assertThrows(FieldDefinitionException.class, e);
        assertEquals(code, ex.code(), ex.getMessage());
        return ex;
    }
}
