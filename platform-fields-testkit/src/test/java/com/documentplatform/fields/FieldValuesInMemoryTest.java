package com.documentplatform.fields;

import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.testkit.InMemoryKeyService;
import com.documentplatform.fields.inmemory.InMemoryFieldStore;
import com.documentplatform.fields.inmemory.InMemoryValueStore;
import com.documentplatform.fields.values.FieldValues;
import com.documentplatform.fields.values.FieldValuesError;
import com.documentplatform.fields.values.FieldValuesException;
import com.documentplatform.fields.values.RecordRef;
import com.documentplatform.fields.values.SearchResult;
import com.documentplatform.fields.values.StoredRecord;
import com.documentplatform.fields.values.StoredValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** The in-memory value store keeps the same rules as the Postgres one. */
class FieldValuesInMemoryTest {

    static final FieldScope A = FieldScope.of(UUID.fromString("00000000-0000-0000-0000-0000000000a1"), "PRODUCT_A");
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-22T10:00:00Z"), ZoneOffset.UTC);
    static final CustomerId CUSTOMER = CustomerId.fromTenantId("TEN-TEST-0001");
    static final String CASE = "business_record";

    private final InMemoryFieldStore fieldStore = new InMemoryFieldStore();
    private final InMemoryValueStore store = new InMemoryValueStore();
    private final InMemoryKeyService keys = new InMemoryKeyService();
    private final FieldValues values;

    FieldValuesInMemoryTest() {
        keys.createKeys(CUSTOMER);
        FieldDefinitions definitions = new FieldDefinitions(fieldStore, store, CLOCK);
        definitions.create(A, FieldSpec.builder("loan_number", "Loan number", FieldType.TEXT).required(true)
                .primary(true).sensitive(false).searchable(SearchMode.PARTIAL).build(), "u");
        definitions.create(A, FieldSpec.builder("borrower_name", "Borrower name", FieldType.TEXT).searchable(true)
                .build(), "u");
        values = new FieldValues(CUSTOMER, fieldStore, store, keys, CLOCK);
    }

    static RecordRef ref(String id) {
        return RecordRef.of(A, CASE, id);
    }

    static Map<String, List<FieldValue>> vals(String loan, String name) {
        return name == null ? Map.of("loan_number", List.of(FieldValue.text(loan)))
                : Map.of("loan_number", List.of(FieldValue.text(loan)), "borrower_name", List.of(FieldValue.text(name)));
    }

    private static void refused(FieldValuesError code, Executable call) {
        assertEquals(code, assertThrows(FieldValuesException.class, call).code());
    }

    @Test
    void storesSealsAndReadsBack() {
        values.claim(ref("c1"), vals("LN-100", "Alice Smith"));
        StoredRecord raw = store.all().get(0);
        StoredValue name = raw.values().stream().filter(v -> v.key().value().equals("borrower_name")).findFirst()
                .orElseThrow();
        assertTrue(name.sensitive());
        assertNull(name.display());
        assertFalse(name.sealed().toLowerCase().contains("alice"));
        assertTrue(raw.envelope().startsWith("pck1."));
        assertTrue(values.read(ref("c1")).field("borrower_name").orElseThrow().hidden());
        int before = keys.calls();
        assertEquals("Alice Smith", values.readSensitive(ref("c1")).values("borrower_name").get(0).normalisedText());
        assertEquals(1, keys.calls() - before);
    }

    @Test
    void searchRulesAndThePrefixFallback() {
        values.claim(ref("c1"), vals("LN-100", "Alice Smith"));
        values.claim(ref("c2"), vals("XLN-200", null));
        assertEquals(List.of(ref("c1"), ref("c2")), values.search(A, CASE, "loan_number", SearchMode.PARTIAL,
                FieldValue.text("ln-")).records());
        assertEquals(List.of(ref("c1")), values.search(A, CASE, "borrower_name", SearchMode.EXACT,
                FieldValue.text("alice  SMITH")).records());
        refused(FieldValuesError.VALUES_SENSITIVE_EXACT_ONLY, () -> values.search(A, CASE, "borrower_name",
                SearchMode.PARTIAL, FieldValue.text("ali")));

        store.partialSearch(false);
        SearchResult r = values.search(A, CASE, "loan_number", SearchMode.PARTIAL, FieldValue.text("ln-"));
        assertEquals(SearchResult.Note.PARTIAL_AS_PREFIX, r.note());
        assertEquals(List.of(ref("c1")), r.records());
        assertFalse(values.searchStatus().partialSearch());
    }

    @Test
    void thePrimaryRuleClaimsAndConflicts() {
        assertTrue(values.claim(ref("c1"), vals("LN-100", null)).created());
        assertEquals(new FieldValues.Claim(ref("c1"), false, true), values.claim(ref("c2"),
                vals("ln-100", "Alice Smith")));
        refused(FieldValuesError.VALUES_CONFLICT, () -> values.claim(ref("c3"), vals("LN-100", "Bob Jones")));
        values.claim(ref("c4"), vals("LN-400", null));
        refused(FieldValuesError.VALUES_PRIMARY_TAKEN, () -> values.update(ref("c4"),
                Map.of("loan_number", List.of(FieldValue.text("LN-100")))));
        refused(FieldValuesError.VALUES_WRONG_CUSTOMER, () -> new FieldValues(CustomerId.fromTenantId("TEN-OTHER"),
                fieldStore, store, keys, CLOCK).read(ref("c1")));
        assertEquals(2, store.all().size());
    }

    @Test
    void concurrentClaimsEndUpOnOneRecord() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<FieldValues.Claim>> claims = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                String id = "w" + i;
                claims.add(pool.submit(() -> {
                    start.await();
                    return values.claim(ref(id), vals("LN-777", "Alice Smith"));
                }));
            }
            start.countDown();
            Set<RecordRef> got = new HashSet<>();
            for (Future<FieldValues.Claim> f : claims) {
                got.add(f.get().record());
            }
            assertEquals(1, got.size());
            assertEquals(1, store.all().size());
        } finally {
            pool.shutdownNow();
        }
    }
}
