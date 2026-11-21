package com.documentplatform.fields.jdbc;

import com.documentplatform.fields.FieldDefinition;
import com.documentplatform.fields.FieldDefinitionException;
import com.documentplatform.fields.FieldDefinitions;
import com.documentplatform.fields.FieldKey;
import com.documentplatform.fields.FieldScope;
import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.FieldStore;
import com.documentplatform.fields.FieldType;
import com.documentplatform.fields.file.FieldListFileTest;
import com.documentplatform.fields.file.ImportReport;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.documentplatform.fields.FieldSpec.builder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Postgres store through the rules service, as the app's runtime user (DML rights only). */
class PostgresFieldStoreTest {

    static final FieldScope A = FieldScope.of(UUID.fromString("00000000-0000-0000-0000-0000000000a1"), "PRODUCT_A");
    static final FieldScope B = FieldScope.of(UUID.fromString("00000000-0000-0000-0000-0000000000b1"), "PRODUCT_B");
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-21T10:00:00Z"), ZoneOffset.UTC);

    private final Pg db = Pg.migrated();
    private final DataSource app = db.appUser("select, insert, update, delete");
    private final PostgresFieldStore store = new PostgresFieldStore(app);
    private final Set<String> withValues = new HashSet<>();
    private final FieldDefinitions fields = new FieldDefinitions(store,
            (scope, key) -> withValues.contains(scope.product() + "/" + key.value()), CLOCK);

    @Test
    void everyKindOfFieldIsStoredAndReadBackExactly() {
        List<FieldSpec> all = FieldListFileTest.everyKind();
        for (FieldSpec s : all) {
            fields.create(A, s, "u-admin");
        }
        List<FieldDefinition> back = fields.list(A);
        assertEquals(all, back.stream().map(FieldDefinition::spec).toList());
        FieldDefinition first = back.get(0);
        assertEquals(1, first.version());
        assertEquals(CLOCK.instant(), first.createdAt());
        assertEquals("u-admin", first.updatedBy());
        assertEquals(List.of(), fields.list(B), "per entity and product");
        assertEquals(10, fields.active(A).size());
        assertEquals(all.get(2), fields.find(A, FieldKey.of("loan_amount")).orElseThrow().spec());
    }

    @Test
    void keysAreUniqueIgnoringCapitalsPerEntityAndProduct() {
        fields.create(A, builder("Loan_Number", "Loan number", FieldType.TEXT).build(), "u-admin");
        refused(FieldDefinitionException.Code.FIELD_KEY_TAKEN,
                () -> fields.create(A, builder("loan_number", "Other", FieldType.TEXT).build(), "u-admin"));
        refused(FieldDefinitionException.Code.FIELD_KEY_TAKEN, () -> store.create(A,
                List.of(builder("loan_NUMBER", "Other", FieldType.TEXT).build()), FieldStore.Change.CREATED, "u",
                CLOCK.instant()));
        fields.create(B, builder("loan_number", "Loan number", FieldType.TEXT).build(), "u-admin");
        assertEquals(1, fields.list(A).size());
        assertTrue(fields.find(A, FieldKey.of("loan_number")).isEmpty(), "find matches the exact key");
    }

    @Test
    void labelAlwaysChangesTypeOnlyWithoutValues() {
        FieldDefinition d = fields.create(A, builder("ref", "Reference", FieldType.TEXT).build(), "u-admin");
        FieldDefinition relabelled = fields.relabel(A, d.key(), "Our reference", "u-admin");
        assertEquals(2, relabelled.version());
        FieldDefinition retyped = fields.change(A, relabelled.spec().toBuilder().type(FieldType.UUID).build(), 2,
                "u-admin");
        assertEquals(FieldType.UUID, retyped.type(), "no values yet: the type may change");

        withValues.add("PRODUCT_A/ref");
        refused(FieldDefinitionException.Code.FIELD_TYPE_LOCKED, () -> fields.change(A,
                retyped.spec().toBuilder().type(FieldType.TEXT).build(), 3, "u-admin"));
        FieldDefinition again = fields.relabel(A, d.key(), "Ref", "u-admin");
        assertEquals("Ref", again.spec().label(), "the label may change any time");
        assertEquals(FieldType.UUID, again.type());
        refused(FieldDefinitionException.Code.FIELD_CHANGED_MEANWHILE, () -> fields.change(A,
                again.spec().toBuilder().label("Stale").build(), 3, "u-admin"));
        refused(FieldDefinitionException.Code.FIELD_CHANGED_MEANWHILE, () -> store.update(retyped,
                retyped.spec().toBuilder().label("Stale").build(), FieldStore.Change.CHANGED, "u", CLOCK.instant()));

        List<FieldStore.HistoryEntry> h = fields.history(A, d.key());
        assertEquals(List.of(1, 2, 3, 4), h.stream().map(FieldStore.HistoryEntry::version).toList());
        assertEquals(List.of("Reference", "Our reference", "Our reference", "Ref"),
                h.stream().map(e -> e.spec().label()).toList());
        assertEquals(FieldStore.Change.CREATED, h.get(0).change());
        assertEquals(FieldType.TEXT, h.get(1).spec().type());
        assertEquals(FieldType.UUID, h.get(2).spec().type());
    }

    @Test
    void removingSwitchesOffAndNothingIsDeleted() {
        fields.create(A, builder("loan_number", "Loan number", FieldType.TEXT).primary(true).build(), "u-admin");
        FieldDefinition off = fields.switchOff(A, FieldKey.of("loan_number"), "u-admin");
        assertFalse(off.active());
        assertFalse(off.spec().primary(), "a switched-off field is no longer a primary identifier");
        assertEquals(1, fields.list(A).size(), "still there");
        assertEquals(off, fields.switchOff(A, FieldKey.of("loan_number"), "u-admin"), "no new version");
        FieldDefinition on = fields.switchOn(A, FieldKey.of("loan_number"), "u-admin");
        assertTrue(on.active());
        assertEquals(List.of(FieldStore.Change.CREATED, FieldStore.Change.SWITCHED_OFF, FieldStore.Change.SWITCHED_ON),
                fields.history(A, FieldKey.of("loan_number")).stream().map(FieldStore.HistoryEntry::change).toList());
        assertEquals(1, db.count("select count(*) from fld_field_definition"));
        assertEquals(3, db.count("select count(*) from fld_field_definition_history"));
    }

    @Test
    void importCreatesNewKeysOnlyInOneTransaction() {
        fields.create(B, builder("units", "Units", FieldType.TEXT).build(), "u-admin");
        String file = new FieldDefinitions(store, (s, k) -> false, CLOCK).exportFile(fill(A));
        ImportReport preview = fields.previewImport(B, file);
        assertFalse(preview.applied());
        assertEquals(1, fields.list(B).size(), "a preview changes nothing");
        ImportReport done = fields.importFile(B, file, "u-it");
        assertTrue(done.applied());
        assertEquals(10, done.created().size());
        assertEquals(1, done.conflicts().size());
        assertEquals(FieldType.TEXT, fields.find(B, FieldKey.of("units")).orElseThrow().type(), "never retyped");
        assertEquals(11, fields.list(B).size());
        assertEquals(FieldStore.Change.IMPORTED, fields.history(B, FieldKey.of("loan_amount")).get(0).change());
        assertEquals(10, fields.importFile(B, file, "u-it").unchanged().size(), "importing twice creates nothing");
    }

    @Test
    void appSettingsAttachByKey() {
        fields.create(A, builder("loan_number", "Loan number", FieldType.TEXT).build(), "u-admin");
        FieldKey k = FieldKey.of("loan_number");
        fields.putExtension(A, k, "extractable", "true");
        fields.putExtension(A, k, "record_title.position", "1");
        fields.putExtension(A, k, "extractable", "false");
        assertEquals(Map.of("extractable", "false", "record_title.position", "1"), fields.extensions(A, k));
        fields.putExtension(A, k, "record_title.position", null);
        assertEquals(Map.of("extractable", "false"), fields.extensions(A, k));
        refused(FieldDefinitionException.Code.FIELD_EXTENSION_INVALID, () -> fields.putExtension(A, k, "Bad Name", "x"));
        refused(FieldDefinitionException.Code.FIELD_UNKNOWN, () -> fields.putExtension(A, FieldKey.of("nope"), "a", "x"));
        assertFalse(fields.exportFile(A).contains("extractable"), "app settings are not exported");
    }

    @Test
    void ofManyConcurrentChangesFromOneVersionExactlyOneWins() throws Exception {
        FieldDefinition d = fields.create(A, builder("ref", "Reference", FieldType.TEXT).build(), "u-admin");
        int n = 16;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String label = "Label " + i;
            Callable<String> task = () -> {
                start.await();
                try {
                    fields.change(A, d.spec().toBuilder().label(label).build(), 1, "u-" + label.length());
                    return "won";
                } catch (FieldDefinitionException e) {
                    return e.code().name();
                }
            };
            results.add(pool.submit(task));
        }
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : results) {
            outcomes.add(f.get());
        }
        pool.shutdown();
        assertEquals(1, outcomes.stream().filter("won"::equals).count(), outcomes.toString());
        assertEquals(n - 1, outcomes.stream().filter("FIELD_CHANGED_MEANWHILE"::equals).count());
        assertEquals(2, fields.find(A, d.key()).orElseThrow().version());
    }

    private FieldScope fill(FieldScope scope) {
        for (FieldSpec s : FieldListFileTest.everyKind()) {
            store.create(scope, List.of(s), FieldStore.Change.CREATED, "u-admin", CLOCK.instant());
        }
        return scope;
    }

    private static void refused(FieldDefinitionException.Code code, org.junit.jupiter.api.function.Executable e) {
        FieldDefinitionException ex = assertThrows(FieldDefinitionException.class, e);
        assertEquals(code, ex.code(), ex.getMessage());
    }
}
