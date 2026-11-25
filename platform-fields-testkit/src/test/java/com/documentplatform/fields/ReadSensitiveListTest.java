package com.documentplatform.fields;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.testkit.InMemoryKeyService;
import com.documentplatform.fields.inmemory.InMemoryFieldStore;
import com.documentplatform.fields.inmemory.InMemoryValueStore;
import com.documentplatform.fields.values.FieldValues;
import com.documentplatform.fields.values.FieldValuesError;
import com.documentplatform.fields.values.FieldValuesException;
import com.documentplatform.fields.values.RecordRef;
import com.documentplatform.fields.values.SensitiveRead;
import com.documentplatform.fields.values.StoredRecord;
import com.documentplatform.fields.values.StoredValue;
import com.documentplatform.fields.values.ValueStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Reading a page of records with their sensitive values (P054): the single read, record by record, in one call. */
class ReadSensitiveListTest {

    static final FieldScope A = FieldScope.of(UUID.fromString("00000000-0000-0000-0000-0000000000a1"), "PRODUCT_A");
    static final FieldScope OTHER_ENTITY = FieldScope.of(UUID.fromString("00000000-0000-0000-0000-0000000000b1"),
            "PRODUCT_A");
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-25T10:00:00Z"), ZoneOffset.UTC);
    static final CustomerId CUSTOMER = CustomerId.fromTenantId("TEN-TEST-0001");
    static final CustomerId OTHER = CustomerId.fromTenantId("TEN-TEST-0002");

    private final InMemoryFieldStore fieldStore = new InMemoryFieldStore();
    private final InMemoryValueStore store = new InMemoryValueStore();
    private final InMemoryKeyService keys = new InMemoryKeyService();
    private final FieldValues values;

    ReadSensitiveListTest() {
        keys.createKeys(CUSTOMER);
        keys.createKeys(OTHER);
        FieldDefinitions definitions = new FieldDefinitions(fieldStore, store, CLOCK);
        definitions.create(A, FieldSpec.builder("loan_number", "Loan number", FieldType.TEXT).required(true)
                .primary(true).sensitive(false).build(), "u");
        definitions.create(A, FieldSpec.builder("borrower_name", "Borrower name", FieldType.TEXT).build(), "u");
        values = new FieldValues(CUSTOMER, fieldStore, store, keys, CLOCK);
    }

    static RecordRef ref(String id) {
        return RecordRef.of(A, "business_record", id);
    }

    private static void claim(FieldValues by, String id, String name) {
        by.claim(ref(id), name == null ? Map.of("loan_number", List.of(FieldValue.text("LN-" + id)))
                : Map.of("loan_number", List.of(FieldValue.text("LN-" + id)),
                "borrower_name", List.of(FieldValue.text(name))));
    }

    private StoredRecord stored(String id) {
        return store.find(ref(id)).orElseThrow();
    }

    private void set(String id, String envelope, List<StoredValue> rows) {
        StoredRecord r = stored(id);
        store.replace(r.with(r.identity(), envelope, CLOCK.instant(), rows));
    }

    @Test
    void aPageOf25IsOneCallInsteadOf25() {
        List<RecordRef> page = new ArrayList<>();
        for (int i = 25; i > 0; i--) {
            claim(values, "c" + i, "Person Number " + i);
            page.add(ref("c" + i));
        }
        int before = keys.calls();
        page.forEach(values::readSensitive);
        assertEquals(25, keys.calls() - before);
        before = keys.calls();
        List<SensitiveRead> got = values.readSensitive(page);
        assertEquals(1, keys.calls() - before);
        assertEquals(page, got.stream().map(SensitiveRead::ref).toList(), "order kept");
        for (SensitiveRead r : got) {
            assertEquals(values.readSensitive(r.ref()), r.values());
        }
        assertEquals("Person Number 25", got.get(0).values().values("borrower_name").get(0).normalisedText());
        assertFalse(got.toString().contains("Person"), got.toString());
    }

    /** P056 (LV-25.1): the plain read of a page, the single read's outcome for each, no key service call. */
    @Test
    void aPlainPageHasTheSingleReadsOutcomeAndCallsNoKeyService() {
        claim(values, "c1", "Alice c1");
        claim(values, "plain", null);
        claim(new FieldValues(OTHER, fieldStore, store, keys, CLOCK), "foreign", "Bob Jones");
        int before = keys.calls();
        List<SensitiveRead> got = values.read(List.of(ref("plain"), ref("missing"), ref("foreign"), ref("c1"),
                ref("plain")));
        assertEquals(0, keys.calls() - before);
        assertEquals(values.read(ref("plain")), got.get(0).values());
        assertEquals(FieldValuesError.VALUES_RECORD_UNKNOWN, got.get(1).code().orElseThrow());
        assertEquals(FieldValuesError.VALUES_WRONG_CUSTOMER, got.get(2).code().orElseThrow());
        assertEquals(values.read(ref("c1")), got.get(3).values());
        assertTrue(got.get(3).values().values("borrower_name").isEmpty(), "hidden");
        assertEquals(got.get(0).values(), got.get(4).values());
        assertTrue(values.read(List.<RecordRef>of()).isEmpty());
        List<RecordRef> tooMany = java.util.Collections.nCopies(101, ref("c1"));
        assertThrows(IllegalArgumentException.class, () -> values.read(tooMany));
    }

    /** P056 (LV-25.5): no key service where no field is sensitive; refused only when a sensitive one is used. */
    @Test
    void withoutAKeyServicePlainFieldsWorkAndSensitiveOnesAreRefused() {
        claim(values, "sealed", "Alice Smith");
        FieldValues plain = FieldValues.withoutKeyService(CUSTOMER, fieldStore, store, CLOCK);
        claim(plain, "p1", null);
        assertEquals("LN-P1", plain.readSensitive(ref("p1")).values("loan_number").get(0).normalisedText().toUpperCase());
        assertEquals(ref("p1"), plain.findByPrimary(A, "business_record", Map.of("loan_number",
                FieldValue.text("LN-p1"))).orElseThrow());
        assertFalse(plain.rewrap(ref("p1")));
        assertTrue(plain.readSensitive(List.of(ref("p1"))).get(0).ok());
        assertEquals(1, plain.read(ref("sealed")).fields().get(com.documentplatform.fields.FieldKey.of("borrower_name"))
                .count(), "hidden as ever");
        for (org.junit.jupiter.api.function.Executable e : List.<org.junit.jupiter.api.function.Executable>of(
                () -> claim(plain, "p2", "Bob Jones"),
                () -> plain.update(ref("p1"), Map.of("borrower_name", List.of(FieldValue.text("Bob Jones")))),
                () -> plain.readSensitive(ref("sealed")),
                () -> plain.readSensitive(List.of(ref("p1"), ref("sealed"))),
                () -> plain.rewrap(ref("sealed")))) {
            FieldValuesException x = assertThrows(FieldValuesException.class, e);
            assertEquals(FieldValuesError.VALUES_KEY_SERVICE_REQUIRED, x.code());
            assertFalse(x.getMessage().contains("Bob"));
        }
        assertTrue(store.find(ref("p2")).isEmpty(), "nothing written");
    }

    /** P056 (LV-25.3). */
    @Test
    void aConflictCarriesItsKeysAsData() {
        claim(values, "c1", "Alice Smith");
        FieldValuesException x = assertThrows(FieldValuesException.class, () -> claim(values, "c1", "Bob Jones"));
        assertEquals(FieldValuesError.VALUES_CONFLICT, x.code());
        assertEquals(List.of(com.documentplatform.fields.FieldKey.of("borrower_name")), x.conflictingKeys());
        FieldValuesException other = assertThrows(FieldValuesException.class, () -> values.read(ref("none")));
        assertTrue(other.conflictingKeys().isEmpty());
    }

    @Test
    void eachRecordHasTheSingleReadsOutcome() {
        for (String id : List.of("c1", "c2", "c3", "c4", "c5")) {
            claim(values, id, "Alice " + id);
        }
        claim(values, "plain", null);
        claim(new FieldValues(OTHER, fieldStore, store, keys, CLOCK), "foreign", "Bob Jones");
        keys.rotate(CUSTOMER);
        claim(values, "newer", "Alice newer");
        // c1: its data key was changed; c2: another customer's envelope; c3: another record's item; c4: no envelope.
        byte[] raw = Base64.getUrlDecoder().decode(stored("c1").envelope().substring(5));
        raw[raw.length - 1] = (byte) (raw[raw.length - 1] == 'A' ? 'B' : 'A');
        raw[raw.length - 8] = (byte) (raw[raw.length - 8] == 'A' ? 'B' : 'A');
        set("c1", "pck1." + Base64.getUrlEncoder().withoutPadding().encodeToString(raw), stored("c1").values());
        set("c2", stored("foreign").envelope(), stored("c2").values());
        set("c3", stored("c3").envelope(), stored("c5").values());
        set("c4", null, stored("c4").values());
        List<RecordRef> page = List.of(ref("c5"), ref("c1"), ref("c2"), ref("c3"), ref("c4"), ref("missing"),
                ref("foreign"), ref("plain"), RecordRef.of(OTHER_ENTITY, "business_record", "c5"),
                RecordRef.of(A, "other_type", "c5"), ref("newer"), ref("c5"));
        compare(page);
        List<SensitiveRead> got = values.readSensitive(page);
        assertEquals(CryptoError.CRYPTO_CIPHERTEXT_INVALID, got.get(1).code().orElseThrow());
        assertEquals(CryptoError.CRYPTO_WRONG_CUSTOMER, got.get(2).code().orElseThrow());
        assertEquals(CryptoError.CRYPTO_CIPHERTEXT_INVALID, got.get(3).code().orElseThrow());
        assertEquals(CryptoError.CRYPTO_FORMAT_UNKNOWN, got.get(4).code().orElseThrow());
        assertEquals(FieldValuesError.VALUES_RECORD_UNKNOWN, got.get(5).code().orElseThrow());
        assertEquals(FieldValuesError.VALUES_WRONG_CUSTOMER, got.get(6).code().orElseThrow());
        assertEquals(FieldValuesError.VALUES_RECORD_UNKNOWN, got.get(8).code().orElseThrow(), "another entity");
        assertEquals(FieldValuesError.VALUES_RECORD_UNKNOWN, got.get(9).code().orElseThrow(), "another type");
        assertTrue(got.get(0).ok() && got.get(7).ok() && got.get(10).ok() && got.get(11).ok());
        keys.setLowestUsable(CUSTOMER, 2);
        compare(page);
        got = values.readSensitive(page);
        assertEquals(CryptoError.CRYPTO_KEY_DISABLED, got.get(0).code().orElseThrow());
        assertTrue(got.get(10).ok());
        for (SensitiveRead r : got) {
            assertFalse((r + " " + r.failure()).contains("Alice"));
        }
    }

    /** Same values, same errors, one call. */
    private void compare(List<RecordRef> page) {
        int before = keys.calls();
        List<SensitiveRead> got = values.readSensitive(page);
        assertEquals(1, keys.calls() - before);
        for (int i = 0; i < page.size(); i++) {
            RecordRef ref = page.get(i);
            SensitiveRead r = got.get(i);
            assertEquals(ref, r.ref());
            if (r.ok()) {
                assertEquals(values.readSensitive(ref), r.values(), ref.toString());
                assertTrue(r.code().isEmpty());
            } else {
                RuntimeException single = assertThrows(RuntimeException.class, () -> values.readSensitive(ref));
                assertEquals(single.getClass(), r.failure().getClass());
                assertEquals(single.getMessage(), r.failure().getMessage());
                assertSame(r.failure(), assertThrows(RuntimeException.class, r::values));
            }
        }
    }

    @Test
    void aFailureThatIsNotAboutOneRecordFailsThePage() {
        claim(values, "c1", "Alice Smith");
        claim(values, "plain", null);
        List<RecordRef> page = List.of(ref("c1"), ref("missing"));
        keys.down(true);
        assertEquals(CryptoError.CRYPTO_KEY_SERVICE_UNREACHABLE, assertThrows(CryptoException.class,
                () -> values.readSensitive(page)).code());
        assertTrue(values.readSensitive(List.of(ref("plain"), ref("missing"))).get(0).ok(), "nothing to open, no call");
        keys.down(false).denied(true);
        assertEquals(CryptoError.CRYPTO_PERMISSION_DENIED, assertThrows(CryptoException.class,
                () -> values.readSensitive(page)).code());
        keys.denied(false);
        ValueStore broken = (ValueStore) Proxy.newProxyInstance(ValueStore.class.getClassLoader(),
                new Class<?>[]{ValueStore.class}, (p, m, args) -> {
                    if (m.getName().equals("find") && args[0].equals(ref("missing"))
                            || m.getName().equals("findAll")) {
                        throw new FieldValuesException(FieldValuesError.VALUES_STORE_FAILED, "SQL state 08006");
                    }
                    try {
                        return m.invoke(store, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        assertEquals(FieldValuesError.VALUES_STORE_FAILED, assertThrows(FieldValuesException.class,
                () -> new FieldValues(CUSTOMER, fieldStore, broken, keys, CLOCK).readSensitive(page)).code());
    }

    @Test
    void limitEmptyAndNullCallNothing() {
        claim(values, "c1", "Alice Smith");
        int before = keys.calls();
        assertEquals(List.of(), values.readSensitive(List.of()));
        assertThrows(IllegalArgumentException.class, () -> values.readSensitive(Collections.nCopies(101, ref("c1"))));
        assertThrows(NullPointerException.class, () -> values.readSensitive(Arrays.asList(ref("c1"), null)));
        assertEquals(before, keys.calls());
        assertEquals(100, values.readSensitive(Collections.nCopies(100, ref("c1"))).size());
        assertEquals(before + 1, keys.calls());
    }
}
