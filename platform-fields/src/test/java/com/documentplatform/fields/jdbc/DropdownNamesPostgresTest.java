package com.documentplatform.fields.jdbc;

import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.testkit.InMemoryKeyService;
import com.documentplatform.fields.FieldDefinition;
import com.documentplatform.fields.FieldDefinitionException;
import com.documentplatform.fields.FieldDefinitions;
import com.documentplatform.fields.FieldKey;
import com.documentplatform.fields.FieldScope;
import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.FieldStore;
import com.documentplatform.fields.FieldType;
import com.documentplatform.fields.FieldValue;
import com.documentplatform.fields.SearchMode;
import com.documentplatform.fields.file.FieldListFile;
import com.documentplatform.fields.file.ImportReport;
import com.documentplatform.fields.values.FieldValues;
import com.documentplatform.fields.values.RecordRef;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** P059 part 3 (LV-28): dropdown codes with display names in Postgres, through save, read, change and import. */
class DropdownNamesPostgresTest {

    static final FieldScope A = FieldScope.of(UUID.fromString("00000000-0000-0000-0000-0000000000a1"), "PRODUCT_A");
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-26T10:00:00Z"), ZoneOffset.UTC);
    static final CustomerId CUSTOMER = CustomerId.fromTenantId("TEN-TEST-0001");
    static final FieldKey STATUS = FieldKey.of("status");

    private static FieldSpec.Option o(String code, String name) {
        return new FieldSpec.Option(code, name);
    }

    private static FieldSpec status(FieldSpec.Option... options) {
        return FieldSpec.builder("status", "Status", FieldType.DROPDOWN).searchable(true).namedOptions(options)
                .build();
    }

    private record Setup(Pg db, PostgresFieldStore fields, PostgresValueStore store, FieldDefinitions definitions,
                         FieldValues values) {
        static Setup on(Pg db) {
            DataSource app = db.appUser("select, insert, update, delete");
            PostgresFieldStore fields = new PostgresFieldStore(app);
            PostgresValueStore store = new PostgresValueStore(app);
            InMemoryKeyService keys = new InMemoryKeyService();
            keys.createKeys(CUSTOMER);
            return new Setup(db, fields, store, new FieldDefinitions(fields, store, CLOCK),
                    new FieldValues(CUSTOMER, fields, store, keys, CLOCK));
        }
    }

    @Test
    void theOrderIsKeptThroughSaveReadAndChange() {
        Setup s = Setup.on(Pg.migrated());
        FieldDefinition d = s.definitions.create(A, status(o("Z", "Zeta"), o("A", null), o("M", "Middle")), "u-admin");
        FieldSpec read = s.fields.find(A, STATUS).orElseThrow().spec();
        assertEquals(List.of(o("Z", "Zeta"), o("A", null), o("M", "Middle")), read.namedOptions());
        assertEquals(List.of("Z", "A", "M"), s.fields.list(A).get(0).spec().options());

        s.definitions.change(A, status(o("M", "Middle"), o("Z", "Zeta"), o("A", "Alpha")), d.version(), "u-admin");
        assertEquals(List.of(o("M", "Middle"), o("Z", "Zeta"), o("A", "Alpha")),
                s.definitions.find(A, STATUS).orElseThrow().spec().namedOptions());
        List<FieldStore.HistoryEntry> history = s.definitions.history(A, STATUS);
        assertEquals(List.of("Z", "A", "M"), history.get(0).spec().options(), "the history keeps each order");
        assertEquals(List.of(o("M", "Middle"), o("Z", "Zeta"), o("A", "Alpha")), history.get(1).spec()
                .namedOptions());
    }

    @Test
    void namesChangeWithoutTouchingAnyStoredValue() {
        Setup s = Setup.on(Pg.migrated());
        FieldDefinition d = s.definitions.create(A, status(o("OPN", "Open"), o("CLS", "Closed")), "u-admin");
        RecordRef ref = RecordRef.of(A, "business_record", "c1");
        s.values.claim(ref, Map.of("status", List.of(FieldValue.text("OPN"))));
        List<String> before = s.db.strings("select concat_ws('|', r.version, v.display_value, v.normalised_value, "
                + "v.search_value) from fld_value v join fld_record r using (record_uuid)");

        FieldDefinition renamed = s.definitions.change(A, status(o("OPN", "Open, waiting"), o("CLS", "Done")),
                d.version(), "u-admin");
        assertEquals("Open, waiting", renamed.spec().displayName("OPN"));
        assertEquals(before, s.db.strings("select concat_ws('|', r.version, v.display_value, v.normalised_value, "
                + "v.search_value) from fld_value v join fld_record r using (record_uuid)"));
        assertEquals("OPN", s.values.read(ref).values("status").get(0).normalisedText());
        assertEquals(List.of(ref), s.values.search(A, "business_record", "status", SearchMode.EXACT,
                FieldValue.text("OPN")).records());
    }

    @Test
    void aDuplicateNameIsRefusedAndNothingIsSaved() {
        Setup s = Setup.on(Pg.migrated());
        FieldDefinitionException e = assertThrows(FieldDefinitionException.class, () -> s.definitions.create(A,
                status(o("A", "Same"), o("B", "same")), "u-admin"));
        assertEquals(FieldDefinitionException.Code.FIELD_OPTION_NAME_REPEATED, e.code());
        assertTrue(s.fields.list(A).isEmpty());
    }

    @Test
    void aCodeOnlyDefinitionFromBeforeReadsUnchanged() {
        Pg db = Pg.empty();
        db.exec(FieldSchema.script("V1__fields.sql"));
        db.exec(FieldSchema.script("V2__fields_search_path.sql"));
        UUID id = UUID.randomUUID();
        // as 1.2.0 wrote it: options only, and the history entry in the platform-fields/1 form of that time
        db.exec("insert into fld_field_definition (definition_id, entity_uuid, product_code, field_key, label, "
                        + "field_type, options, required, letter_case, strip, primary_flag, searchable, search_mode, "
                        + "sensitive, multi_value, displayable, display_order, active, version, created_at, updated_at, "
                        + "updated_by) values ('" + id + "', '" + A.entity() + "', 'PRODUCT_A', 'status', 'Status', "
                        + "'DROPDOWN', array['Open', 'Waiting', 'Closed'], false, 'PRESERVE', 'NONE', false, true, "
                        + "'EXACT', false, false, true, 0, true, 1, now(), now(), 'u-admin')",
                "insert into fld_field_definition_history (definition_id, version, change, changed_at, changed_by, "
                        + "definition) values ('" + id + "', 1, 'CREATED', now(), 'u-admin', '{\"key\":\"status\","
                        + "\"label\":\"Status\",\"type\":\"DROPDOWN\",\"options\":[\"Open\",\"Waiting\",\"Closed\"],"
                        + "\"searchable\":true}')");
        for (String script : FieldSchema.scripts()) {
            if (!script.startsWith("V1__") && !script.startsWith("V2__")) {
                db.exec(FieldSchema.script(script));
            }
        }
        Setup s = Setup.on(db);
        FieldSpec read = s.fields.find(A, STATUS).orElseThrow().spec();
        assertEquals(List.of("Open", "Waiting", "Closed"), read.options());
        assertEquals(List.of(o("Open", null), o("Waiting", null), o("Closed", null)), read.namedOptions());
        assertEquals(List.of("Open", "Waiting", "Closed"), s.definitions.history(A, STATUS).get(0).spec().options());
        s.values.claim(RecordRef.of(A, "business_record", "c1"), Map.of("status", List.of(FieldValue.text("Waiting"))));
        assertEquals("Status", s.definitions.relabel(A, STATUS, "Status", "u-admin").spec().label());
    }

    @Test
    void importAndSearchStillMatchByCode() {
        Setup s = Setup.on(Pg.migrated());
        String file = FieldListFile.write(List.of(status(o("OPN", "Open"), o("CLS", "Closed"))), CLOCK.instant());
        ImportReport report = s.definitions.importFile(A, file, "u-admin");
        assertEquals(ImportReport.Outcome.CREATE, report.lines().get(0).outcome());
        assertEquals(ImportReport.Outcome.UNCHANGED, s.definitions.previewImport(A, file).lines().get(0).outcome());

        RecordRef ref = RecordRef.of(A, "business_record", "c1");
        s.values.claim(ref, Map.of("status", List.of(FieldValue.text("CLS"))));
        assertEquals(List.of(ref), s.values.search(A, "business_record", "status", SearchMode.EXACT,
                FieldValue.text("CLS")).records());
        assertTrue(s.values.search(A, "business_record", "status", SearchMode.EXACT, FieldValue.text("Closed"))
                .records().isEmpty(), "a name is never searched");
    }
}
