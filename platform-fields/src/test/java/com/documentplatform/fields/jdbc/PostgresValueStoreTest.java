package com.documentplatform.fields.jdbc;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.testkit.InMemoryKeyService;
import com.documentplatform.fields.FieldDefinitionException;
import com.documentplatform.fields.FieldDefinitions;
import com.documentplatform.fields.FieldScope;
import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.FieldType;
import com.documentplatform.fields.FieldValidator;
import com.documentplatform.fields.FieldValue;
import com.documentplatform.fields.Normalisation;
import com.documentplatform.fields.SearchMode;
import com.documentplatform.fields.ValidValue;
import com.documentplatform.fields.ValueError;
import com.documentplatform.fields.values.FieldValues;
import com.documentplatform.fields.values.FieldValuesError;
import com.documentplatform.fields.values.FieldValuesException;
import com.documentplatform.fields.values.RecordRef;
import com.documentplatform.fields.values.TestErase;
import com.documentplatform.fields.values.RecordValues;
import com.documentplatform.fields.values.SearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Value storage on Postgres, with the app connecting as the DML-only user and the in-memory key service. */
class PostgresValueStoreTest {

    static final FieldScope A = FieldScope.of(UUID.fromString("00000000-0000-0000-0000-0000000000a1"), "PRODUCT_A");
    static final FieldScope B = FieldScope.of(UUID.fromString("00000000-0000-0000-0000-0000000000b1"), "PRODUCT_B");
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-22T10:00:00Z"), ZoneOffset.UTC);
    static final CustomerId CUSTOMER = CustomerId.fromTenantId("TEN-TEST-0001");
    static final CustomerId OTHER = CustomerId.fromTenantId("TEN-TEST-0002");
    static final String CASE = "business_record";

    /**
     * The sensitive values used below: none may ever appear readably in the database or a log. Whole values with
     * spaces (Base64 has none) and long pieces, so random ciphertext cannot match by chance (checked in lower case).
     */
    static final List<String> SECRETS = List.of("alice smith", "bob jones", "12 maple street", "springfield",
            "m-778812", "778812");

    private final Pg db = Pg.migrated();
    private final DataSource app = db.appUser("select, insert, update, delete");
    private final PostgresFieldStore definitionStore = new PostgresFieldStore(app);
    private final PostgresValueStore store = new PostgresValueStore(app);
    private final FieldDefinitions definitions = new FieldDefinitions(definitionStore, store, CLOCK);
    private final InMemoryKeyService keys = new InMemoryKeyService();
    private final FieldValues values;

    PostgresValueStoreTest() {
        keys.createKeys(CUSTOMER);
        keys.createKeys(OTHER);
        values = new FieldValues(CUSTOMER, definitionStore, store, keys, CLOCK);
        define(A, FieldSpec.builder("contract_number", "Contract number", FieldType.TEXT).required(true).primary(true)
                .normalisation(Normalisation.LetterCase.UPPER, Normalisation.Strip.SEPARATORS)
                .searchable(SearchMode.PARTIAL).sensitive(false).build());
        define(A, FieldSpec.builder("borrower_name", "Borrower name", FieldType.TEXT).multiValue(true)
                .searchable(true).build());
        define(A, FieldSpec.builder("mailing_address", "Mailing address", FieldType.ADDRESS).searchable(true).build());
        define(A, FieldSpec.builder("loan_amount", "Loan amount", FieldType.MONEY).currency("USD").searchable(true)
                .build());
        define(A, FieldSpec.builder("rate", "Rate", FieldType.DECIMAL).build());
        define(A, FieldSpec.builder("term_months", "Term in months", FieldType.NUMBER).build());
        define(A, FieldSpec.builder("signed_on", "Signed on", FieldType.DATE).searchable(true).build());
        define(A, FieldSpec.builder("signed_at", "Signed at", FieldType.DATE_TIME).build());
        define(A, FieldSpec.builder("contact_email", "Contact e-mail", FieldType.EMAIL).build());
        define(A, FieldSpec.builder("source_uuid", "Source", FieldType.UUID).build());
        define(A, FieldSpec.builder("status", "Status", FieldType.DROPDOWN).options("Open", "Closed").build());
        define(A, FieldSpec.builder("branch_code", "Branch code", FieldType.CUSTOM_PATTERN).pattern("BR-[0-9]{3}")
                .searchable(SearchMode.PREFIX).build());
        define(A, FieldSpec.builder("notes", "Notes", FieldType.TEXT).build());
        // Entity B: a sensitive primary field, and a second primary (records identified by the pair).
        define(B, FieldSpec.builder("member_number", "Member number", FieldType.TEXT).primary(true).sensitive(true)
                .searchable(true).normalisation(Normalisation.LetterCase.UPPER, Normalisation.Strip.NONE).build());
        define(B, FieldSpec.builder("plan_code", "Plan", FieldType.TEXT).primary(true).sensitive(false).build());
    }

    private void define(FieldScope scope, FieldSpec spec) {
        definitions.create(scope, spec, "u-admin");
    }

    static RecordRef ref(FieldScope scope, String id) {
        return RecordRef.of(scope, CASE, id);
    }

    /** key, value, key, value...; a value is a String (text), a FieldValue, or a List of them. */
    static Map<String, List<FieldValue>> vals(Object... kv) {
        Map<String, List<FieldValue>> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1];
            List<FieldValue> list = new ArrayList<>();
            if (v instanceof List<?> l) {
                l.forEach(x -> list.add(x instanceof String s ? FieldValue.text(s) : (FieldValue) x));
            } else {
                list.add(v instanceof String s ? FieldValue.text(s) : (FieldValue) v);
            }
            m.put((String) kv[i], list);
        }
        return m;
    }

    static final FieldValue.Address ADDRESS = FieldValue.address("12 Maple Street", "Apt 4", "Springfield", "il",
            "62701");

    private Map<String, List<FieldValue>> fullCase(String contract) {
        return vals("contract_number", contract, "borrower_name", List.of("Alice Smith", "Bob Jones"),
                "mailing_address", ADDRESS, "loan_amount", "$1,250.50", "rate", "4.50", "term_months", "360",
                "signed_on", "11/21/2026", "signed_at", "2026-11-21T10:15:30-05:00",
                "contact_email", "Info@Example.com", "source_uuid", "123E4567-E89B-12D3-A456-426614174000",
                "status", "Open", "branch_code", "BR-042", "notes", "Note one");
    }

    private ValidValue expected(FieldScope scope, String key, FieldValue v) {
        FieldSpec spec = definitions.fieldSet(scope).find(key).orElseThrow();
        return FieldValidator.check(spec, v).valid();
    }

    private static void refused(FieldValuesError code, Executable call) {
        FieldValuesException e = assertThrows(FieldValuesException.class, call);
        assertEquals(code, e.code(), e.getMessage());
    }

    // ---- storing and reading back ----

    @Test
    void everyTypeIsStoredAndReadBack() {
        FieldValues.Claim claim = values.claim(ref(A, "case-1"), fullCase("ab-1001"));
        assertTrue(claim.created());

        RecordValues plain = values.read(ref(A, "case-1"));
        assertFalse(plain.opened());
        assertTrue(plain.field("borrower_name").orElseThrow().hidden());
        assertEquals(2, plain.field("borrower_name").orElseThrow().count());
        assertTrue(plain.field("mailing_address").orElseThrow().hidden());
        Map<String, List<FieldValue>> given = fullCase("ab-1001");
        for (String key : List.of("contract_number", "loan_amount", "rate", "term_months", "signed_on", "signed_at",
                "contact_email", "source_uuid", "status", "branch_code", "notes")) {
            assertEquals(List.of(expected(A, key, given.get(key).get(0))), plain.values(key), key);
        }
        assertEquals("AB1001", plain.values("contract_number").get(0).normalisedText());
        assertEquals("1250.50", plain.values("loan_amount").get(0).normalisedText());
        assertEquals("2026-11-21T15:15:30.000Z", plain.values("signed_at").get(0).normalisedText());

        RecordValues open = values.readSensitive(ref(A, "case-1"));
        assertTrue(open.opened());
        assertEquals(List.of(expected(A, "borrower_name", FieldValue.text("Alice Smith")),
                expected(A, "borrower_name", FieldValue.text("Bob Jones"))), open.values("borrower_name"));
        ValidValue address = open.values("mailing_address").get(0);
        assertEquals(expected(A, "mailing_address", ADDRESS), address);
        assertEquals("IL", ((FieldValue.Address) address.display()).state());
        assertEquals("Apt 4", ((FieldValue.Address) address.display()).street2());
        assertEquals(plain.values("notes"), open.values("notes"));
    }

    @Test
    void invalidValuesAreRefusedWithTheirCodesAndNoValueInTheMessage() {
        FieldValuesException e = assertThrows(FieldValuesException.class, () -> values.claim(ref(A, "case-x"),
                vals("contract_number", "C-1", "mailing_address", FieldValue.address("1 Maple Street", "Springfield",
                        "XX", "62701"), "status", "Pending")));
        assertEquals(FieldValuesError.VALUES_INVALID, e.code());
        assertEquals(Set.of(ValueError.ADDRESS_STATE_INVALID, ValueError.VALUE_NOT_AN_OPTION),
                Set.copyOf(e.problems().stream().map(p -> p.code()).toList()));
        String all = (e.getMessage() + e.problems()).toLowerCase(Locale.ROOT);
        assertFalse(all.contains("maple") || all.contains("pending"), all);
        assertEquals(0, db.count("select count(*) from fld_record"));
        refused(FieldValuesError.VALUES_INVALID, () -> values.claim(ref(A, "case-x"), vals("notes", "no contract")));
    }

    // ---- search ----

    @Test
    void nonSensitiveFieldsAreSearchedExactlyByPrefixAndByPart() {
        values.claim(ref(A, "c1"), vals("contract_number", "AB-1001"));
        values.claim(ref(A, "c2"), vals("contract_number", "AB-2002", "branch_code", "BR-042"));
        values.claim(ref(A, "c3"), vals("contract_number", "XY-1003", "branch_code", "BR-077"));

        assertEquals(List.of(ref(A, "c1")), values.search(A, CASE, "contract_number", SearchMode.EXACT,
                FieldValue.text(" ab 1001 ")).records(), "normalised like a value: capitals, dashes, spaces");
        assertEquals(List.of(ref(A, "c1"), ref(A, "c2")), values.search(A, CASE, "contract_number",
                SearchMode.PREFIX, FieldValue.text("ab")).records());
        SearchResult part = values.search(A, CASE, "contract_number", SearchMode.PARTIAL, FieldValue.text("100"));
        assertEquals(List.of(ref(A, "c1"), ref(A, "c3")), part.records());
        assertEquals(SearchResult.Note.NONE, part.note());
        assertTrue(values.searchStatus().partialSearch());
        assertEquals(List.of(ref(A, "c2"), ref(A, "c3")), values.search(A, CASE, "branch_code", SearchMode.PREFIX,
                FieldValue.text("br-0")).records());
        assertEquals(List.of(), values.search(A, CASE, "contract_number", SearchMode.PARTIAL,
                FieldValue.text("%")).records(), "LIKE wildcards are matched as themselves");
        assertEquals(List.of(), values.search(A, CASE, "branch_code", SearchMode.EXACT,
                FieldValue.text("not a branch")).records(), "a term the field could never hold matches nothing");

        refused(FieldValuesError.VALUES_SEARCH_MODE_NOT_ALLOWED, () -> values.search(A, CASE, "branch_code",
                SearchMode.PARTIAL, FieldValue.text("04")));
        refused(FieldValuesError.VALUES_NOT_SEARCHABLE, () -> values.search(A, CASE, "notes", SearchMode.EXACT,
                FieldValue.text("x")));
        refused(FieldValuesError.VALUES_FIELD_UNKNOWN, () -> values.search(A, CASE, "nothing", SearchMode.EXACT,
                FieldValue.text("x")));
    }

    @Test
    void withoutTheTrigramIndexPartialSearchRunsAsPrefixAndSaysSo() {
        values.claim(ref(A, "c1"), vals("contract_number", "AB-1001"));
        values.claim(ref(A, "c3"), vals("contract_number", "XY-1003"));
        db.exec("drop index " + FieldSchema.PARTIAL_SEARCH_INDEX);

        SearchResult r = values.search(A, CASE, "contract_number", SearchMode.PARTIAL, FieldValue.text("xy"));
        assertEquals(SearchResult.Note.PARTIAL_AS_PREFIX, r.note());
        assertEquals(List.of(ref(A, "c3")), r.records());
        assertEquals(List.of(), values.search(A, CASE, "contract_number", SearchMode.PARTIAL,
                FieldValue.text("100")).records(), "only the start of the value, as the note says");
        FieldValues.SearchStatus status = values.searchStatus();
        assertFalse(status.partialSearch());
        assertEquals("FLD_PARTIAL_SEARCH_UNAVAILABLE", status.code());
        assertTrue(status.note().contains(FieldSchema.PARTIAL_SEARCH_SCRIPT), status.note());

        // The DBA's optional script brings it back.
        db.exec(new String(readResource(FieldSchema.PARTIAL_SEARCH_SCRIPT)));
        assertTrue(values.searchStatus().partialSearch());
        assertEquals(List.of(ref(A, "c1"), ref(A, "c3")), values.search(A, CASE, "contract_number",
                SearchMode.PARTIAL, FieldValue.text("100")).records());
    }

    static byte[] readResource(String name) {
        try (var in = PostgresValueStoreTest.class.getClassLoader().getResourceAsStream(name)) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void sensitiveFieldsAreSearchedByExactMatchOnlyIgnoringCapitalsAndSpaces() {
        values.claim(ref(A, "c1"), vals("contract_number", "AB-1001", "borrower_name", List.of("Alice Smith",
                "Bob Jones"), "mailing_address", ADDRESS));
        values.claim(ref(A, "c2"), vals("contract_number", "AB-2002", "borrower_name", "Carol King"));

        assertEquals(List.of(ref(A, "c1")), values.search(A, CASE, "borrower_name", SearchMode.EXACT,
                FieldValue.text("  ALICE    smith ")).records());
        assertEquals(List.of(ref(A, "c1")), values.search(A, CASE, "borrower_name", SearchMode.EXACT,
                FieldValue.text("bob jones")).records());
        assertEquals(List.of(), values.search(A, CASE, "borrower_name", SearchMode.EXACT,
                FieldValue.text("Alice")).records(), "no partial match through the fingerprint");
        assertEquals(List.of(ref(A, "c1")), values.search(A, CASE, "mailing_address", SearchMode.EXACT,
                FieldValue.address("12  MAPLE street", "apt 4", "springfield", "IL", "62701")).records());

        for (SearchMode mode : List.of(SearchMode.PREFIX, SearchMode.PARTIAL)) {
            refused(FieldValuesError.VALUES_SENSITIVE_EXACT_ONLY, () -> values.search(A, CASE, "borrower_name", mode,
                    FieldValue.text("ali")));
        }
    }

    // ---- sensitive values never readable ----

    @Test
    void noSensitiveValueIsReadableInAnyTableColumn() {
        values.claim(ref(A, "c1"), fullCase("AB-1001"));
        values.claim(ref(B, "m1"), vals("member_number", "m-778812", "plan_code", "GOLD"));

        List<String> rows = new ArrayList<>();
        for (String table : FieldSchema.TABLES) {
            rows.addAll(db.strings("select t::text from " + table + " t"));
        }
        assertTrue(rows.size() > 10);
        String dump = String.join("\n", rows).toLowerCase(Locale.ROOT);
        for (String secret : SECRETS) {
            assertFalse(dump.contains(secret), "readable in a table: " + secret);
        }
        assertTrue(dump.contains("ab1001"), "non-sensitive values are stored readably");
        assertEquals(4, db.count("select count(*) from fld_value where sensitive and sealed_value like 'pci1.%' "
                + "and display_value is null and normalised_value is null and search_value is null"));
        assertEquals(4, db.count("select count(*) from fld_value where sensitive and fingerprint is not null"));
        assertEquals(2, db.count("select count(*) from fld_record where envelope like 'pck1.%'"));

        // The database itself refuses a readable sensitive value.
        assertThrows(IllegalStateException.class, () -> db.exec("update fld_value set display_value = 'x' "
                + "where sensitive"));
    }

    @Test
    void noSensitiveValueInLogsMessagesOrToString() {
        Logger root = Logger.getLogger("");
        Level before = root.getLevel();
        List<String> logged = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord r) {
                logged.add(r.getLoggerName() + " " + r.getMessage() + " " + java.util.Arrays.toString(r.getParameters())
                        + " " + r.getThrown());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        root.setLevel(Level.ALL);
        Logger.getLogger("org.postgresql").setLevel(Level.ALL);
        root.addHandler(capture);
        List<String> texts = new ArrayList<>();
        try {
            values.claim(ref(A, "c1"), fullCase("AB-1001"));
            values.claim(ref(B, "m1"), vals("member_number", "M-778812", "plan_code", "GOLD"));
            RecordValues open = values.readSensitive(ref(A, "c1"));
            texts.add(open.toString());
            open.fields().values().forEach(f -> texts.add(f.toString() + f.values()));
            texts.add(store.find(ref(A, "c1")).orElseThrow().toString());
            texts.add(store.find(ref(A, "c1")).orElseThrow().values().toString());
            values.search(A, CASE, "borrower_name", SearchMode.EXACT, FieldValue.text("Alice Smith"));
            for (Executable bad : List.<Executable>of(
                    () -> values.claim(ref(A, "c1"), vals("contract_number", "AB-1001", "borrower_name",
                            List.of("Alice Smith", "Bob Jonesy"))),
                    () -> values.claim(ref(A, "c9"), vals("contract_number", "AB-9", "mailing_address",
                            FieldValue.address("9 Maple Street", "Springfield", "ZZ", "62701"))),
                    () -> values.search(A, CASE, "borrower_name", SearchMode.PREFIX, FieldValue.text("Alice")),
                    () -> new FieldValues(OTHER, definitionStore, store, keys, CLOCK).readSensitive(ref(A, "c1")),
                    () -> values.update(ref(B, "nope"), vals("member_number", "M-778812")))) {
                Throwable t = assertThrows(RuntimeException.class, bad);
                texts.add(t.toString());
                if (t instanceof FieldValuesException f) {
                    texts.add(f.problems().toString());
                }
            }
        } finally {
            root.removeHandler(capture);
            root.setLevel(before);
            Logger.getLogger("org.postgresql").setLevel(null);
        }
        String all = (String.join("\n", logged) + "\n" + String.join("\n", texts)).toLowerCase(Locale.ROOT);
        for (String secret : SECRETS) {
            assertFalse(all.contains(secret), "shown: " + secret);
        }
    }

    // ---- key service ----

    @Test
    void openingARecordIsOneKeyServiceCallHoweverManySensitiveValues() {
        values.claim(ref(A, "c1"), fullCase("AB-1001")); // three sensitive values
        int before = keys.calls();
        values.readSensitive(ref(A, "c1"));
        assertEquals(1, keys.calls() - before);

        before = keys.calls();
        values.read(ref(A, "c1"));
        assertEquals(0, keys.calls() - before, "reading without opening calls nothing");

        // Writing a new record: the data key, the fingerprint key's versions, one batch of fingerprints.
        before = keys.calls();
        values.claim(ref(A, "c2"), vals("contract_number", "AB-2", "borrower_name", "Ann Lee"));
        int one = keys.calls() - before;
        before = keys.calls();
        values.claim(ref(A, "c3"), vals("contract_number", "AB-3", "borrower_name",
                List.of("Ann Lee", "Ben Ray", "Cy Day", "Di Fox"), "mailing_address", ADDRESS));
        assertEquals(3, one);
        assertEquals(one, keys.calls() - before, "the same however many sensitive values");
    }

    @Test
    void keyRotationKeepsReadingAndSearchWorking() {
        values.claim(ref(A, "old"), vals("contract_number", "AB-1", "borrower_name", "Alice Smith"));
        keys.rotate(CUSTOMER);
        values.claim(ref(A, "new"), vals("contract_number", "AB-2", "borrower_name", "Alice Smith"));
        assertEquals("Alice Smith", values.readSensitive(ref(A, "old")).values("borrower_name").get(0)
                .normalisedText());
        assertEquals("Alice Smith", values.readSensitive(ref(A, "new")).values("borrower_name").get(0)
                .normalisedText());

        // Retire version 1: rewrap first, then the old record still opens.
        assertTrue(values.rewrap(ref(A, "old")));
        assertFalse(values.rewrap(ref(A, "old")), "already on the latest version");
        keys.setLowestUsable(CUSTOMER, 2);
        assertEquals(1, values.readSensitive(ref(A, "old")).values("borrower_name").size());

        // A rotated fingerprint key: values fingerprinted before and after are both found.
        keys.rotateFingerprintKey(CUSTOMER);
        values.claim(ref(A, "newer"), vals("contract_number", "AB-3", "borrower_name", "alice smith"));
        assertEquals(List.of(ref(A, "new"), ref(A, "newer"), ref(A, "old")), values.search(A, CASE,
                "borrower_name", SearchMode.EXACT, FieldValue.text("ALICE SMITH")).records());
    }

    @Test
    void aSensitivePrimaryValueStillFindsItsRecordAfterTheFingerprintKeyRotates() {
        assertTrue(values.claim(ref(B, "m1"), vals("member_number", "m-778812", "plan_code", "GOLD")).created());
        keys.rotateFingerprintKey(CUSTOMER);
        FieldValues.Claim again = values.claim(ref(B, "m2"), vals("member_number", "M-778812 ", "plan_code",
                "GOLD"));
        assertEquals(ref(B, "m1"), again.record());
        assertFalse(again.created());
        assertEquals(ref(B, "m1"), values.findByPrimary(B, CASE, Map.of("member_number", FieldValue.text("m-778812"),
                "plan_code", FieldValue.text("GOLD"))).orElseThrow());
        assertEquals(1, db.count("select count(*) from fld_record"));
    }

    @Test
    void sensitiveAndPrimaryLockOnceARecordHoldsAValueAndFreeAgainWhenAllAreErased() {
        values.claim(ref(A, "c1"), vals("contract_number", "AB-1001", "notes", "n"));
        var notes = definitions.find(A, com.documentplatform.fields.FieldKey.of("notes")).orElseThrow();
        var contract = definitions.find(A, com.documentplatform.fields.FieldKey.of("contract_number")).orElseThrow();
        assertEquals(FieldDefinitionException.Code.FIELD_SENSITIVE_LOCKED, assertThrows(
                FieldDefinitionException.class, () -> definitions.change(A, notes.spec().toBuilder().sensitive(true)
                        .build(), notes.version(), "u-admin")).code());
        assertEquals(FieldDefinitionException.Code.FIELD_PRIMARY_LOCKED, assertThrows(
                FieldDefinitionException.class, () -> definitions.change(A, contract.spec().toBuilder().primary(false)
                        .build(), contract.version(), "u-admin")).code());
        TestErase.erase(values, ref(A, "c1"));
        assertTrue(definitions.change(A, notes.spec().toBuilder().sensitive(true).build(), notes.version(),
                "u-admin").spec().sensitive(), "no value left, so the flag may change again");
    }

    // ---- erasing a record (P020) ----

    /** xc-public-api-2 (P037): the Postgres store deletes only with a ticket for that record. */
    @Test
    void theStoreDeletesNothingWithoutATicketForThatRecord() {
        values.claim(ref(A, "c1"), vals("contract_number", "AB-1001", "notes", "n"));
        values.claim(ref(A, "c2"), vals("contract_number", "AB-1002", "notes", "n"));
        var c1 = store.find(ref(A, "c1")).orElseThrow();
        refused(FieldValuesError.VALUES_ERASE_OUTSIDE_LOG, () -> store.delete(c1, null));
        refused(FieldValuesError.VALUES_ERASE_OUTSIDE_LOG, () -> store.delete(c1, TestErase.ticketFor(ref(A, "c2"))));
        assertTrue(store.find(ref(A, "c1")).isPresent(), "nothing deleted");
        store.delete(c1, TestErase.ticketFor(ref(A, "c1")));
        assertTrue(store.find(ref(A, "c1")).isEmpty());
        assertTrue(store.find(ref(A, "c2")).isPresent());
    }

    @Test
    void erasingARecordRemovesItsValuesAndEnvelopeAndSearchNoLongerFindsIt() {
        values.claim(ref(A, "c1"), fullCase("AB-1001"));
        values.claim(ref(A, "c2"), vals("contract_number", "AB-1002", "borrower_name", "Alice Smith"));
        values.claim(ref(B, "m1"), vals("member_number", "M-778812", "plan_code", "P1"));
        long otherRows = db.count("select count(*) from fld_value where record_uuid <> (select record_uuid from "
                + "fld_record where record_id = 'c1')");
        int calls = keys.calls();

        FieldValues.Erased erased = TestErase.erase(values, ref(A, "c1"));

        assertEquals(keys.calls(), calls, "erase calls no key service");
        assertEquals(ref(A, "c1"), erased.record());
        assertEquals(14, erased.values());
        assertTrue(erased.hadSensitiveValues());
        assertEquals(CLOCK.instant(), erased.at());
        assertFalse(erased.toString().toLowerCase(Locale.ROOT).contains("alice"));
        assertEquals(0, db.count("select count(*) from fld_record where record_id = 'c1'"), "record and envelope gone");
        assertEquals(otherRows, db.count("select count(*) from fld_value"), "only c1's value rows were deleted");
        refused(FieldValuesError.VALUES_RECORD_UNKNOWN, () -> values.read(ref(A, "c1")));
        refused(FieldValuesError.VALUES_RECORD_UNKNOWN, () -> values.readSensitive(ref(A, "c1")));
        assertEquals(List.of(ref(A, "c2")), values.search(A, CASE, "contract_number", SearchMode.PREFIX,
                FieldValue.text("AB")).records());
        assertEquals(List.of(ref(A, "c2")), values.search(A, CASE, "borrower_name", SearchMode.EXACT,
                FieldValue.text("alice smith")).records(), "sensitive exact search no longer finds it");
        assertTrue(values.findByPrimary(A, CASE, Map.of("contract_number", FieldValue.text("AB-1001"))).isEmpty());
        refused(FieldValuesError.VALUES_RECORD_UNKNOWN, () -> TestErase.erase(values, ref(A, "c1")));

        FieldValues.Claim again = values.claim(ref(A, "c9"), vals("contract_number", "AB-1001"));
        assertTrue(again.created(), "the same primary value later makes a new record");
        assertEquals(ref(A, "c9"), again.record());

        FieldValues.Erased member = TestErase.erase(values, ref(B, "m1"));
        assertTrue(member.hadSensitiveValues());
        assertTrue(values.findByPrimary(B, CASE, Map.of("member_number", FieldValue.text("m-778812"),
                "plan_code", FieldValue.text("P1"))).isEmpty(), "a sensitive primary value no longer finds it");
    }

    @Test
    void aRecordUnderLegalHoldIsNotErased() {
        Set<String> held = new HashSet<>(Set.of("c1"));
        FieldValues guarded = new FieldValues(CUSTOMER, definitionStore, store, keys, CLOCK,
                r -> held.contains(r.recordId()));
        guarded.claim(ref(A, "c1"), vals("contract_number", "AB-1001", "borrower_name", "Alice Smith"));
        guarded.claim(ref(A, "c2"), vals("contract_number", "AB-1002"));

        FieldValuesException e = assertThrows(FieldValuesException.class, () -> TestErase.erase(guarded, ref(A, "c1")));
        assertEquals(FieldValuesError.VALUES_LEGAL_HOLD, e.code());
        assertFalse(e.getMessage().toLowerCase(Locale.ROOT).contains("alice"));
        assertEquals("Alice Smith", ((FieldValue.Text) guarded.readSensitive(ref(A, "c1")).values("borrower_name")
                .get(0).display()).value(), "nothing was erased");
        TestErase.erase(guarded, ref(A, "c2"));

        FieldValues unsure = new FieldValues(CUSTOMER, definitionStore, store, keys, CLOCK, r -> {
            throw new IllegalStateException("hold register down");
        });
        refused(FieldValuesError.VALUES_LEGAL_HOLD_UNKNOWN, () -> TestErase.erase(unsure, ref(A, "c1")));
        assertEquals(1, db.count("select count(*) from fld_record where record_id = 'c1'"));

        held.clear();
        TestErase.erase(guarded, ref(A, "c1"));
        assertEquals(0, db.count("select count(*) from fld_record"));
    }

    @Test
    void anotherCustomersRecordIsNotErased() {
        values.claim(ref(A, "c1"), vals("contract_number", "AB-1001"));
        refused(FieldValuesError.VALUES_WRONG_CUSTOMER,
                () -> TestErase.erase(new FieldValues(OTHER, definitionStore, store, keys, CLOCK), ref(A, "c1")));
        assertEquals(1, db.count("select count(*) from fld_record"));
    }

    /**
     * What erase does NOT do today, pinned so that it is not mistaken for crypto-shredding: a backup taken before the
     * erase still holds the record's envelope, and the customer's wrapping key still opens it. Making backups
     * unreadable needs a per-record key kept outside the database (P020 report, question 1). When that is built, this
     * test must be turned round.
     */
    @Test
    void aRestoredBackupOfAnErasedRecordStillOpensWhileTheCustomersKeyLives() {
        values.claim(ref(A, "c1"), vals("contract_number", "AB-1001", "borrower_name", "Alice Smith"));
        db.exec("create table backup_record as select * from fld_record",
                "create table backup_value as select * from fld_value");
        TestErase.erase(values, ref(A, "c1"));
        assertEquals(0, db.count("select count(*) from fld_value"));

        db.exec("insert into fld_record select * from backup_record", "insert into fld_value select * from backup_value");
        assertEquals("Alice Smith", ((FieldValue.Text) values.readSensitive(ref(A, "c1")).values("borrower_name")
                .get(0).display()).value(), "the backup copy opens: erase is not crypto-shredding");
    }

    @Test
    void anotherCustomersRecordIsRefused() {
        values.claim(ref(A, "c1"), vals("contract_number", "AB-1001", "borrower_name", "Alice Smith"));
        FieldValues other = new FieldValues(OTHER, definitionStore, store, keys, CLOCK);

        refused(FieldValuesError.VALUES_WRONG_CUSTOMER, () -> other.read(ref(A, "c1")));
        refused(FieldValuesError.VALUES_WRONG_CUSTOMER, () -> other.readSensitive(ref(A, "c1")));
        refused(FieldValuesError.VALUES_WRONG_CUSTOMER, () -> other.claim(ref(A, "c2"),
                vals("contract_number", "ab1001")));
        refused(FieldValuesError.VALUES_WRONG_CUSTOMER, () -> other.update(ref(A, "c1"), vals("notes", "x")));
        assertEquals(List.of(), other.search(A, CASE, "contract_number", SearchMode.EXACT,
                FieldValue.text("AB-1001")).records());

        // Even with the sealed rows copied under another customer's record, the key service refuses them.
        values.claim(ref(A, "c3"), vals("contract_number", "AB-3", "borrower_name", "Bob Jones"));
        db.exec("update fld_record set customer_id = '" + OTHER.value() + "' where record_id = 'c3'");
        CryptoException e = assertThrows(CryptoException.class, () -> other.readSensitive(ref(A, "c3")));
        assertEquals(CryptoError.CRYPTO_WRONG_CUSTOMER, e.code());
        // A sealed value moved to another record does not open.
        db.exec("update fld_record set customer_id = '" + CUSTOMER.value() + "' where record_id = 'c3'",
                "update fld_record set envelope = (select envelope from fld_record where record_id = 'c1') "
                        + "where record_id = 'c3'");
        e = assertThrows(CryptoException.class, () -> values.readSensitive(ref(A, "c3")));
        assertEquals(CryptoError.CRYPTO_CIPHERTEXT_INVALID, e.code());
    }

    // ---- the primary rule ----

    @Test
    void theSamePrimaryValueAlwaysLeadsToTheSameRecord() {
        assertTrue(values.claim(ref(A, "c1"), vals("contract_number", "AB-1001", "borrower_name", "Alice Smith"))
                .created());

        // The same contract again, under another candidate ID, with one more field: added to the same record.
        FieldValues.Claim again = values.claim(ref(A, "c2"), vals("contract_number", "ab 1001", "borrower_name",
                "alice  smith", "loan_amount", "100"));
        assertEquals(new FieldValues.Claim(ref(A, "c1"), false, true), again);
        assertEquals(List.of(expected(A, "loan_amount", FieldValue.text("100"))), values.read(ref(A, "c1"))
                .values("loan_amount"));
        assertEquals(new FieldValues.Claim(ref(A, "c1"), false, false), values.claim(ref(A, "c2"),
                vals("contract_number", "AB1001")), "nothing new: nothing written");

        // A different value for a field the record already has: refused, nothing written.
        refused(FieldValuesError.VALUES_CONFLICT, () -> values.claim(ref(A, "c2"), vals("contract_number", "AB1001",
                "borrower_name", "Bob Jones")));
        refused(FieldValuesError.VALUES_CONFLICT, () -> values.claim(ref(A, "c2"), vals("contract_number", "AB1001",
                "loan_amount", "101")));
        assertEquals(1, db.count("select count(*) from fld_record"));
        assertEquals(2, values.read(ref(A, "c1")).version());

        assertEquals(ref(A, "c1"), values.findByPrimary(A, CASE, Map.of("contract_number",
                FieldValue.text("AB 1001"))).orElseThrow());
        assertTrue(values.findByPrimary(A, CASE, Map.of("contract_number", FieldValue.text("AB-9"))).isEmpty());
        refused(FieldValuesError.VALUES_NOT_PRIMARY, () -> values.findByPrimary(A, CASE, Map.of("notes",
                FieldValue.text("x"))));

        // Changing a record's primary value to another record's is refused by the database.
        values.claim(ref(A, "c3"), vals("contract_number", "AB-3"));
        refused(FieldValuesError.VALUES_PRIMARY_TAKEN, () -> values.update(ref(A, "c3"),
                vals("contract_number", "AB-1001")));
        assertEquals(2, values.update(ref(A, "c3"), vals("contract_number", "AB-4", "notes", "renumbered")));
        assertEquals(ref(A, "c3"), values.findByPrimary(A, CASE, Map.of("contract_number",
                FieldValue.text("AB-4"))).orElseThrow());
        refused(FieldValuesError.VALUES_INVALID, () -> values.update(ref(A, "c3"), vals("contract_number",
                List.of())));
        refused(FieldValuesError.VALUES_RECORD_UNKNOWN, () -> values.update(ref(A, "nope"), vals("notes", "x")));
    }

    @Test
    void severalPrimaryFieldsIdentifyARecordTogether() {
        values.claim(ref(B, "r1"), vals("member_number", "M-1", "plan_code", "GOLD"));
        assertEquals(ref(B, "r1"), values.claim(ref(B, "r2"), vals("member_number", "m-1", "plan_code", "GOLD"))
                .record());
        assertTrue(values.claim(ref(B, "r3"), vals("member_number", "M-1", "plan_code", "SILVER")).created(),
                "another pair: another record");
        assertTrue(values.claim(ref(B, "r4"), vals("member_number", "M-1")).created(),
                "a part of the pair: another record");
        assertEquals(3, db.count("select count(*) from fld_record"));
    }

    @Test
    void concurrentWritersOfTheSamePrimaryValueEndUpOnOneRecord() throws Exception {
        int writers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<FieldValues.Claim>> claims = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                String id = "w" + i;
                claims.add(pool.submit(() -> {
                    start.await();
                    return values.claim(ref(A, id), vals("contract_number", "CC-777", "borrower_name",
                            "Alice Smith"));
                }));
            }
            start.countDown();
            Set<RecordRef> records = new HashSet<>();
            int created = 0;
            for (Future<FieldValues.Claim> f : claims) {
                FieldValues.Claim c = f.get();
                records.add(c.record());
                created += c.created() ? 1 : 0;
            }
            assertEquals(1, records.size(), "every writer got the same record");
            assertEquals(1, created);
            assertEquals(1, db.count("select count(*) from fld_record"));
            assertEquals(1, values.readSensitive(records.iterator().next()).values("borrower_name").size());
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- definitions and values together ----

    @Test
    void theStoreTellsDefinitionsThatValuesExist() {
        assertFalse(store.valuesExist(A, com.documentplatform.fields.FieldKey.of("notes")));
        values.claim(ref(A, "c1"), vals("contract_number", "AB-1", "notes", "a note"));
        assertTrue(store.valuesExist(A, com.documentplatform.fields.FieldKey.of("NOTES")));
        FieldSpec notes = definitions.fieldSet(A).find("notes").orElseThrow();
        FieldDefinitionException e = assertThrows(FieldDefinitionException.class, () -> definitions.change(A,
                notes.toBuilder().type(FieldType.NUMBER).build(), 1, "u-admin"));
        assertEquals(FieldDefinitionException.Code.FIELD_TYPE_LOCKED, e.code());
    }

    @Test
    void theAppUserNeedsOnlyDataRights() {
        FieldSchema.verify(app);
        DataSource readOnly = Pg.migrated().appUser("select");
        FieldSchemaException e = assertThrows(FieldSchemaException.class, () -> FieldSchema.verify(readOnly));
        assertEquals(FieldSchemaException.Problem.FLD_PRIVILEGES_MISSING, e.problem());
    }
}
