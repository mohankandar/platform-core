package com.documentplatform.fields.jdbc;

import com.documentplatform.fields.FieldDefinition;
import com.documentplatform.fields.FieldKey;
import com.documentplatform.fields.FieldScope;
import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.FieldStore;
import com.documentplatform.fields.values.EraseTicket;
import com.documentplatform.fields.values.RecordRef;
import com.documentplatform.fields.values.SearchMatch;
import com.documentplatform.fields.values.StoredRecord;
import com.documentplatform.fields.values.ValueStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * P059, design note 1.5 test 8: the stores' existing tests run a second time with every call on a store bound to an
 * app's transaction ({@code onConnection}), which this helper opens (auto-commit off, READ COMMITTED) and commits
 * after the call, or rolls back when the call throws. Erasing stays outside the app's transaction (note 1.3 rule 6),
 * so {@code delete} goes to the store's own transaction.
 */
final class BoundEachCall {

    private BoundEachCall() {
    }

    static ValueStore values(DataSource ds) {
        return new Values(ds);
    }

    static FieldStore definitions(DataSource ds) {
        return new Definitions(ds);
    }

    static <S, T> T call(DataSource ds, Function<Connection, S> bind, Function<S, T> work) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                T result = work.apply(bind.apply(c));
                c.commit();
                return result;
            } catch (RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQL state " + e.getSQLState(), e);
        }
    }

    private record Values(DataSource ds) implements ValueStore {

        private <T> T call(Function<ValueStore, T> work) {
            return BoundEachCall.call(ds, PostgresValueStore::onConnection, work);
        }

        @Override
        public Optional<StoredRecord> find(RecordRef ref) {
            return call(s -> s.find(ref));
        }

        @Override
        public Map<RecordRef, StoredRecord> findAll(Collection<RecordRef> refs) {
            return call(s -> s.findAll(refs));
        }

        @Override
        public Optional<StoredRecord> findByIdentity(FieldScope scope, String recordType,
                                                     Collection<String> identities) {
            return call(s -> s.findByIdentity(scope, recordType, identities));
        }

        @Override
        public Inserted insert(StoredRecord record) {
            return call(s -> s.insert(record));
        }

        @Override
        public StoredRecord replace(StoredRecord next) {
            return call(s -> s.replace(next));
        }

        @Override
        public void delete(StoredRecord record, EraseTicket ticket) {
            new PostgresValueStore(ds).delete(record, ticket);
        }

        @Override
        public List<RecordRef> search(UUID customer, FieldScope scope, String recordType, FieldKey key,
                                      SearchMatch match, int limit) {
            return call(s -> s.search(customer, scope, recordType, key, match, limit));
        }

        @Override
        public boolean partialSearchAvailable() {
            return call(ValueStore::partialSearchAvailable);
        }

        @Override
        public boolean valuesExist(FieldScope scope, FieldKey key) {
            return call(s -> s.valuesExist(scope, key));
        }

        @Override
        public ValueStore joined(Connection c) {
            return PostgresValueStore.onConnection(c);
        }
    }

    private record Definitions(DataSource ds) implements FieldStore {

        private <T> T call(Function<FieldStore, T> work) {
            return BoundEachCall.call(ds, PostgresFieldStore::onConnection, work);
        }

        @Override
        public List<FieldDefinition> list(FieldScope scope) {
            return call(s -> s.list(scope));
        }

        @Override
        public Optional<FieldDefinition> find(FieldScope scope, FieldKey key) {
            return call(s -> s.find(scope, key));
        }

        @Override
        public List<FieldDefinition> create(FieldScope scope, List<FieldSpec> specs, Change change, String actor,
                                            Instant at) {
            return call(s -> s.create(scope, specs, change, actor, at));
        }

        @Override
        public FieldDefinition update(FieldDefinition current, FieldSpec next, Change change, String actor,
                                      Instant at) {
            return call(s -> s.update(current, next, change, actor, at));
        }

        @Override
        public List<FieldDefinition> saveAll(FieldScope scope, List<Save> saves, String actor, Instant at) {
            return call(s -> s.saveAll(scope, saves, actor, at));
        }

        @Override
        public List<HistoryEntry> history(FieldScope scope, FieldKey key) {
            return call(s -> s.history(scope, key));
        }

        @Override
        public Map<String, String> extensions(FieldScope scope, FieldKey key) {
            return call(s -> s.extensions(scope, key));
        }

        @Override
        public void putExtension(FieldScope scope, FieldKey key, String name, String value, Instant at) {
            call(s -> {
                s.putExtension(scope, key, name, value, at);
                return null;
            });
        }

        @Override
        public FieldStore joined(Connection c) {
            return PostgresFieldStore.onConnection(c);
        }
    }
}
