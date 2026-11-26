package com.documentplatform.fields.jdbc;

import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.testkit.InMemoryKeyService;
import com.documentplatform.fields.FieldDefinition;
import com.documentplatform.fields.FieldDefinitionException;
import com.documentplatform.fields.FieldDefinitions;
import com.documentplatform.fields.FieldScope;
import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.FieldType;
import com.documentplatform.fields.FieldValue;
import com.documentplatform.fields.Normalisation;
import com.documentplatform.fields.SearchMode;
import com.documentplatform.fields.values.FieldValues;
import com.documentplatform.fields.values.FieldValuesError;
import com.documentplatform.fields.values.FieldValuesException;
import com.documentplatform.fields.values.RecordRef;
import com.documentplatform.fields.values.StoredRecord;
import com.documentplatform.fields.values.TestErase;
import com.documentplatform.fields.values.ValueStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P059 (LV-25.2, design note section 1): the field stores in the app's own transaction, handed over per call with
 * {@code in(Connection)}. The app writes its own row in the same transaction ({@code app_case}).
 */
class AppTransactionTest {

    static final FieldScope A = FieldScope.of(UUID.fromString("00000000-0000-0000-0000-0000000000a1"), "PRODUCT_A");
    static final FieldScope B = FieldScope.of(UUID.fromString("00000000-0000-0000-0000-0000000000b1"), "PRODUCT_B");
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-26T10:00:00Z"), ZoneOffset.UTC);
    static final CustomerId CUSTOMER = CustomerId.fromTenantId("TEN-TEST-0001");
    static final String CASE = "business_record";

    private final Pg db = Pg.migrated();
    private final DataSource app = db.appUser("select, insert, update, delete");
    private final PostgresFieldStore definitionStore = new PostgresFieldStore(app);
    private final PostgresValueStore store = new PostgresValueStore(app);
    private final FieldDefinitions definitions = new FieldDefinitions(definitionStore, store, CLOCK);
    private final InMemoryKeyService keys = new InMemoryKeyService();
    private final FieldValues values;

    AppTransactionTest() {
        keys.createKeys(CUSTOMER);
        values = new FieldValues(CUSTOMER, definitionStore, store, keys, CLOCK);
        definitions.create(A, FieldSpec.builder("contract_number", "Contract number", FieldType.TEXT).required(true)
                .primary(true).normalisation(Normalisation.LetterCase.UPPER, Normalisation.Strip.SEPARATORS)
                .searchable(SearchMode.EXACT).build(), "u-admin");
        definitions.create(A, FieldSpec.builder("notes", "Notes", FieldType.TEXT).build(), "u-admin");
        definitions.create(B, FieldSpec.builder("member_number", "Member number", FieldType.TEXT).primary(true)
                .sensitive(true).searchable(true).build(), "u-admin");
        db.exec("create table app_case (id text primary key)", "grant select, insert on app_case to " + db.schema
                + "_app");
    }

    static RecordRef ref(FieldScope scope, String id) {
        return RecordRef.of(scope, CASE, id);
    }

    static Map<String, List<FieldValue>> vals(String... kv) {
        Map<String, List<FieldValue>> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], List.of(FieldValue.text(kv[i + 1])));
        }
        return m;
    }

    /** The app's transaction: auto-commit off, READ COMMITTED (PostgreSQL's default). */
    private Connection begin() throws SQLException {
        Connection c = app.getConnection();
        c.setAutoCommit(false);
        c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        return c;
    }

    private static void appRow(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("insert into app_case (id) values (?)")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    private long count(String table) {
        return db.count("select count(*) from " + table);
    }

    private static void refused(FieldValuesError code, Executable call) {
        FieldValuesException e = assertThrows(FieldValuesException.class, call);
        assertEquals(code, e.code(), e.getMessage());
    }

    private static void refused(FieldDefinitionException.Code code, Executable call) {
        FieldDefinitionException e = assertThrows(FieldDefinitionException.class, call);
        assertEquals(code, e.code(), e.getMessage());
    }

    // 1.5 test 1

    @Test
    void theAppRollsBackSoNoFieldValueNorDefinitionStays() throws Exception {
        long history = count("fld_field_definition_history");
        try (Connection c = begin()) {
            appRow(c, "case-1");
            assertTrue(values.in(c).claim(ref(A, "case-1"), vals("contract_number", "AB-1", "notes", "n")).created());
            // a sensitive value: key service calls inside the open transaction are allowed
            assertTrue(values.in(c).claim(ref(B, "m-1"), vals("member_number", "M-1")).created());
            definitions.in(c).create(A, FieldSpec.builder("extra", "Extra", FieldType.TEXT).build(), "u-admin");
            c.rollback();
        }
        assertEquals(0, count("app_case"));
        assertEquals(0, count("fld_record"));
        assertEquals(0, count("fld_value"));
        assertEquals(history, count("fld_field_definition_history"));
        assertTrue(definitions.find(A, com.documentplatform.fields.FieldKey.of("extra")).isEmpty());
    }

    // 1.5 test 2

    @Test
    void theAppCommitsSoBothAreThereAndNeitherWasSeenBefore() throws Exception {
        try (Connection c = begin()) {
            appRow(c, "case-1");
            values.in(c).claim(ref(A, "case-1"), vals("contract_number", "AB-1"));
            values.in(c).claim(ref(B, "m-1"), vals("member_number", "M-1"));
            assertEquals(0, count("app_case"), "another connection sees nothing before the commit");
            assertEquals(0, count("fld_record"));
            assertEquals("AB1", values.in(c).read(ref(A, "case-1")).values("contract_number").get(0)
                    .normalisedText(), "the app's own transaction sees its values");
            c.commit();
        }
        assertEquals(1, count("app_case"));
        assertEquals(2, count("fld_record"));
        assertEquals("M-1", values.readSensitive(ref(B, "m-1")).values("member_number").get(0).normalisedText());
    }

    // 1.5 test 3

    @Test
    void aRefusedWriteLeavesTheAppsTransactionUsable() throws Exception {
        values.claim(ref(A, "c1"), vals("contract_number", "AB-1"));
        values.claim(ref(A, "c2"), vals("contract_number", "AB-2"));
        try (Connection c = begin()) {
            appRow(c, "before");
            refused(FieldValuesError.VALUES_PRIMARY_TAKEN,
                    () -> values.in(c).update(ref(A, "c2"), vals("contract_number", "AB-1")));
            refused(FieldValuesError.VALUES_CONFLICT,
                    () -> values.in(c).claim(ref(A, "c9"), vals("contract_number", "AB-1", "notes", "x")));
            values.in(c).update(ref(A, "c1"), vals("notes", "after the refusal"));
            appRow(c, "after");
            c.commit();
        }
        assertEquals(2, count("app_case"));
        assertEquals("AB2", values.read(ref(A, "c2")).values("contract_number").get(0).normalisedText());
        assertEquals("after the refusal", values.read(ref(A, "c1")).values("notes").get(0).normalisedText());
    }

    // 1.5 test 4

    @Test
    void twoTransactionsClaimingOneNewPrimaryValueEndOnOneRecordWhenTheFirstCommits() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection first = begin(); Connection second = begin()) {
            assertTrue(values.in(first).claim(ref(A, "x"), vals("contract_number", "N-1")).created());
            Future<FieldValues.Claim> waiting = pool.submit(
                    () -> values.in(second).claim(ref(A, "y"), vals("contract_number", "N-1", "notes", "second")));
            assertThrows(TimeoutException.class, () -> waiting.get(500, TimeUnit.MILLISECONDS),
                    "the second waits for the first");
            first.commit();
            FieldValues.Claim joined = waiting.get(10, TimeUnit.SECONDS);
            assertEquals(new FieldValues.Claim(ref(A, "x"), false, true), joined);
            second.commit();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, count("fld_record"));
        assertEquals("second", values.read(ref(A, "x")).values("notes").get(0).normalisedText());
    }

    @Test
    void twoTransactionsClaimingOneNewPrimaryValueMakeTheSecondsRecordWhenTheFirstRollsBack() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection first = begin(); Connection second = begin()) {
            values.in(first).claim(ref(A, "x"), vals("contract_number", "N-1"));
            Future<FieldValues.Claim> waiting = pool.submit(
                    () -> values.in(second).claim(ref(A, "y"), vals("contract_number", "N-1")));
            assertThrows(TimeoutException.class, () -> waiting.get(500, TimeUnit.MILLISECONDS));
            first.rollback();
            assertEquals(new FieldValues.Claim(ref(A, "y"), true, true), waiting.get(10, TimeUnit.SECONDS));
            second.commit();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, count("fld_record"));
        assertTrue(values.findByPrimary(A, CASE, Map.of("contract_number", FieldValue.text("N-1"))).orElseThrow().equals(ref(A, "y")));
    }

    // 1.5 test 5

    @Test
    void aVersionConflictInsideIsRetriedAndSucceeds() throws Exception {
        values.claim(ref(A, "c1"), vals("contract_number", "AB-1", "notes", "v1"));
        AtomicBoolean interfered = new AtomicBoolean();
        // Between the bound update's read and its write, another writer commits a change of the same record.
        ValueStore interfering = new Delegating(store) {
            @Override
            public ValueStore joined(Connection c) {
                return new Delegating(store.joined(c)) {
                    @Override
                    public StoredRecord replace(StoredRecord next) {
                        if (interfered.compareAndSet(false, true)) {
                            values.update(ref(A, "c1"), vals("notes", "other writer"));
                        }
                        return super.replace(next);
                    }
                };
            }
        };
        FieldValues withInterference = new FieldValues(CUSTOMER, definitionStore, interfering, keys, CLOCK);
        try (Connection c = begin()) {
            int version = withInterference.in(c).update(ref(A, "c1"), vals("notes", "mine"));
            assertEquals(3, version, "written on top of the other writer's version");
            c.commit();
        }
        assertTrue(interfered.get());
        assertEquals("mine", values.read(ref(A, "c1")).values("notes").get(0).normalisedText());
    }

    // 1.5 test 6

    @Test
    void autoCommitOnAndOtherIsolationLevelsAreRefusedWithNothingWritten() throws Exception {
        try (Connection c = app.getConnection()) {
            assertTrue(c.getAutoCommit());
            refused(FieldValuesError.VALUES_TRANSACTION_UNSUITABLE,
                    () -> values.in(c).claim(ref(A, "c1"), vals("contract_number", "AB-1")));
            refused(FieldDefinitionException.Code.FIELD_TRANSACTION_UNSUITABLE, () -> definitions.in(c)
                    .create(A, FieldSpec.builder("extra", "Extra", FieldType.TEXT).build(), "u-admin"));
        }
        for (int level : new int[]{Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_SERIALIZABLE}) {
            try (Connection c = app.getConnection()) {
                c.setAutoCommit(false);
                c.setTransactionIsolation(level);
                refused(FieldValuesError.VALUES_TRANSACTION_UNSUITABLE,
                        () -> values.in(c).claim(ref(A, "c1"), vals("contract_number", "AB-1")));
                refused(FieldValuesError.VALUES_TRANSACTION_UNSUITABLE, () -> values.in(c).read(ref(A, "c1")));
                refused(FieldDefinitionException.Code.FIELD_TRANSACTION_UNSUITABLE, () -> definitions.in(c)
                        .create(A, FieldSpec.builder("extra", "Extra", FieldType.TEXT).build(), "u-admin"));
                c.commit();
            }
        }
        assertEquals(0, count("fld_record"));
        assertTrue(definitions.find(A, com.documentplatform.fields.FieldKey.of("extra")).isEmpty());
    }

    // 1.5 test 7

    @Test
    void aBoundStoreNeverEndsOrChangesTheAppsTransaction() throws Exception {
        values.claim(ref(A, "c2"), vals("contract_number", "AB-2"));
        try (Connection real = begin()) {
            Connection c = forbidding(real);
            FieldValues bound = values.in(c);
            bound.claim(ref(A, "c1"), vals("contract_number", "AB-1"));
            bound.claim(ref(A, "c1"), vals("contract_number", "AB-1", "notes", "merged"));
            bound.update(ref(A, "c1"), vals("notes", "updated"));
            bound.claim(ref(B, "m-1"), vals("member_number", "M-1"));
            refused(FieldValuesError.VALUES_PRIMARY_TAKEN,
                    () -> bound.update(ref(A, "c2"), vals("contract_number", "AB-1")));
            bound.read(ref(A, "c1"));
            bound.readSensitive(List.of(ref(B, "m-1")));
            bound.search(A, CASE, "contract_number", SearchMode.EXACT, FieldValue.text("AB-1"));
            FieldDefinitions boundDefinitions = definitions.in(c);
            FieldDefinition extra = boundDefinitions.create(A,
                    FieldSpec.builder("extra", "Extra", FieldType.TEXT).build(), "u-admin");
            boundDefinitions.change(A, FieldSpec.builder("extra", "Extra 2", FieldType.TEXT).build(),
                    extra.version(), "u-admin");
            boundDefinitions.switchOff(A, extra.key(), "u-admin");
            real.commit();
        }
        assertEquals("updated", values.read(ref(A, "c1")).values("notes").get(0).normalisedText());
        assertEquals(3, count("fld_record"));
    }

    /** A connection that fails the test when a bound store ends or changes the app's transaction. */
    private static Connection forbidding(Connection real) {
        List<String> forbidden = List.of("commit", "setAutoCommit", "setTransactionIsolation", "close", "abort",
                "setReadOnly");
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (forbidden.contains(name) || name.equals("rollback") && (args == null || args.length == 0)) {
                        fail("a bound store called Connection." + name);
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    // erasing stays out (note 1.3 rule 6)

    @Test
    void erasingInTheAppsTransactionIsRefused() throws Exception {
        values.claim(ref(A, "c1"), vals("contract_number", "AB-1"));
        StoredRecord stored = store.find(ref(A, "c1")).orElseThrow();
        try (Connection c = begin()) {
            refused(FieldValuesError.VALUES_ERASE_OUTSIDE_LOG, () -> TestErase.erase(values.in(c), ref(A, "c1")));
            refused(FieldValuesError.VALUES_ERASE_OUTSIDE_LOG, () -> values.in(c).erase(ref(A, "c1")));
            refused(FieldValuesError.VALUES_ERASE_OUTSIDE_LOG,
                    () -> store.joined(c).delete(stored, TestErase.ticketFor(ref(A, "c1"))));
            c.commit();
        }
        assertEquals(1, count("fld_record"));
    }


    /** Hands every call to another store; overridden where a test steps in. */
    static class Delegating implements ValueStore {
        private final ValueStore to;

        Delegating(ValueStore to) {
            this.to = to;
        }

        @Override
        public Optional<StoredRecord> find(RecordRef ref) {
            return to.find(ref);
        }

        @Override
        public Map<RecordRef, StoredRecord> findAll(java.util.Collection<RecordRef> refs) {
            return to.findAll(refs);
        }

        @Override
        public Optional<StoredRecord> findByIdentity(FieldScope scope, String recordType,
                                                     java.util.Collection<String> identities) {
            return to.findByIdentity(scope, recordType, identities);
        }

        @Override
        public Inserted insert(StoredRecord record) {
            return to.insert(record);
        }

        @Override
        public StoredRecord replace(StoredRecord next) {
            return to.replace(next);
        }

        @Override
        public void delete(StoredRecord record, com.documentplatform.fields.values.EraseTicket ticket) {
            to.delete(record, ticket);
        }

        @Override
        public List<RecordRef> search(UUID customer, FieldScope scope, String recordType,
                                      com.documentplatform.fields.FieldKey key,
                                      com.documentplatform.fields.values.SearchMatch match, int limit) {
            return to.search(customer, scope, recordType, key, match, limit);
        }

        @Override
        public boolean partialSearchAvailable() {
            return to.partialSearchAvailable();
        }

        @Override
        public boolean valuesExist(FieldScope scope, com.documentplatform.fields.FieldKey key) {
            return to.valuesExist(scope, key);
        }
    }

}
