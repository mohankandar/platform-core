package com.documentplatform.fields.jdbc;

import com.documentplatform.fields.FieldKey;
import com.documentplatform.fields.FieldScope;
import com.documentplatform.fields.values.FieldValuesError;
import com.documentplatform.fields.values.FieldValuesException;
import com.documentplatform.fields.values.RecordRef;
import com.documentplatform.fields.values.SearchMatch;
import com.documentplatform.fields.values.StoredRecord;
import com.documentplatform.fields.values.StoredValue;
import com.documentplatform.fields.values.ValueStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.documentplatform.fields.jdbc.Sql.instant;
import static com.documentplatform.fields.jdbc.Sql.ts;

/**
 * Field values in Postgres (tables {@code fld_record} and {@code fld_value}). Plain JDBC on the app's runtime
 * {@link DataSource}; the app's database user needs SELECT, INSERT, UPDATE on {@code fld_record} and SELECT, INSERT,
 * DELETE on {@code fld_value}.
 *
 * <p>The database enforces one record per record type and ID, and one per identity (unique indexes), so concurrent
 * writers cannot make two records for the same primary value(s): {@link #insert} uses {@code on conflict do nothing},
 * which waits for a concurrent writer of the same key and then returns that writer's record. A change is written only
 * if the record is still at the version read. Failures name the operation and the SQL state only.
 */
public final class PostgresValueStore implements ValueStore {

    private static final String RECORD = "r.record_uuid, r.customer_id, r.entity_uuid, r.product_code, r.record_type, "
            + "r.record_id, r.identity, r.envelope, r.version, r.created_at, r.updated_at";
    private static final int ATTEMPTS = 5;

    private final DataSource ds;

    public PostgresValueStore(DataSource dataSource) {
        this.ds = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public Optional<StoredRecord> find(RecordRef ref) {
        return run("read a record's values", c -> find(c, ref));
    }

    /** One query for all of them, values included. */
    @Override
    public Map<RecordRef, StoredRecord> findAll(Collection<RecordRef> refs) {
        List<RecordRef> asked = List.copyOf(new LinkedHashSet<>(refs));
        if (asked.isEmpty()) {
            return Map.of();
        }
        return run("read the values of a page of records", c -> {
            try (PreparedStatement ps = c.prepareStatement("select q.n, " + RECORD + ", v.field_key, v.position, "
                    + "v.sensitive, v.display_value, v.normalised_value, v.search_value, v.sealed_value, v.fingerprint "
                    + "from unnest(?::uuid[], ?::text[], ?::text[], ?::text[]) with ordinality "
                    + "as q(entity_uuid, product_code, record_type, record_id, n) "
                    + "join fld_record r on r.entity_uuid = q.entity_uuid and r.product_code = q.product_code "
                    + "and r.record_type = q.record_type and r.record_id = q.record_id "
                    + "left join fld_value v on v.record_uuid = r.record_uuid "
                    + "order by q.n, lower(v.field_key), v.position")) {
                ps.setArray(1, c.createArrayOf("uuid", asked.stream().map(r -> r.scope().entity()).toArray()));
                ps.setArray(2, c.createArrayOf("text", asked.stream().map(r -> r.scope().product()).toArray()));
                ps.setArray(3, c.createArrayOf("text", asked.stream().map(RecordRef::recordType).toArray()));
                ps.setArray(4, c.createArrayOf("text", asked.stream().map(RecordRef::recordId).toArray()));
                Map<RecordRef, StoredRecord> out = new LinkedHashMap<>();
                try (ResultSet rs = ps.executeQuery()) {
                    Row row = null;
                    List<StoredValue> values = new ArrayList<>();
                    long at = 0;
                    while (rs.next()) {
                        long n = rs.getLong("n");
                        if (n != at) {
                            if (row != null) {
                                out.put(row.ref, row.with(values));
                            }
                            row = row(rs);
                            values = new ArrayList<>();
                            at = n;
                        }
                        if (rs.getString("field_key") != null) {
                            values.add(value(rs));
                        }
                    }
                    if (row != null) {
                        out.put(row.ref, row.with(values));
                    }
                }
                return out;
            }
        });
    }

    @Override
    public Optional<StoredRecord> findByIdentity(FieldScope scope, String recordType, Collection<String> identities) {
        if (identities.isEmpty()) {
            return Optional.empty();
        }
        return run("look up a record by primary value", c -> byIdentity(c, scope, recordType, identities));
    }

    @Override
    public Inserted insert(StoredRecord record) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            Inserted done = inTransaction("store a new record", c -> {
                try (PreparedStatement ps = c.prepareStatement("""
                        insert into fld_record (record_uuid, customer_id, entity_uuid, product_code, record_type,
                            record_id, identity, envelope, version, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?) on conflict do nothing""")) {
                    RecordRef ref = record.ref();
                    ps.setObject(1, record.id());
                    ps.setObject(2, record.customer());
                    ps.setObject(3, ref.scope().entity());
                    ps.setString(4, ref.scope().product());
                    ps.setString(5, ref.recordType());
                    ps.setString(6, ref.recordId());
                    ps.setString(7, record.identity());
                    ps.setString(8, record.envelope());
                    ps.setObject(9, ts(record.createdAt()));
                    ps.setObject(10, ts(record.updatedAt()));
                    if (ps.executeUpdate() == 1) {
                        insertValues(c, record);
                        return new Inserted(new StoredRecord(record.id(), record.customer(), ref, record.identity(),
                                record.envelope(), 1, record.createdAt(), record.updatedAt(), record.values()), true);
                    }
                }
                Optional<StoredRecord> other = find(c, record.ref());
                if (other.isEmpty() && record.identity() != null) {
                    other = byIdentity(c, record.ref().scope(), record.ref().recordType(),
                            List.of(record.identity()));
                }
                return other.map(r -> new Inserted(r, false)).orElse(null);
            });
            if (done != null) {
                return done;
            }
            // The conflicting writer rolled back between our insert and our read: try again.
        }
        throw new FieldValuesException(FieldValuesError.VALUES_CHANGED_MEANWHILE, "Record " + record.ref()
                + " kept changing while it was written; try again");
    }

    @Override
    public StoredRecord replace(StoredRecord next) {
        if (next.values().isEmpty()) {
            // P044, SEC-5: taking every value of a record away (and with it the envelope) is an erasure in all but
            // name; it is done only through the erase log (delete with an EraseTicket), never by a replace.
            throw new FieldValuesException(FieldValuesError.VALUES_ERASE_OUTSIDE_LOG, "Record " + next.ref() + " was "
                    + "not changed: removing every value of a record is an erasure, done only through the erase log");
        }
        return inTransaction("change a record's values", c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    update fld_record set identity = ?, envelope = ?, version = version + 1, updated_at = ?
                    where record_uuid = ? and version = ?""")) {
                ps.setString(1, next.identity());
                ps.setString(2, next.envelope());
                ps.setObject(3, ts(next.updatedAt()));
                ps.setObject(4, next.id());
                ps.setInt(5, next.version());
                int n;
                try {
                    n = ps.executeUpdate();
                } catch (SQLException e) {
                    if (Sql.UNIQUE_VIOLATION.equals(e.getSQLState())) {
                        throw new FieldValuesException(FieldValuesError.VALUES_PRIMARY_TAKEN, "Another record "
                                + "already has the primary value(s) given for record " + next.ref());
                    }
                    throw e;
                }
                if (n == 0) {
                    boolean exists = find(c, next.ref()).isPresent();
                    throw exists
                            ? new FieldValuesException(FieldValuesError.VALUES_CHANGED_MEANWHILE, "Record "
                            + next.ref() + " was changed meanwhile")
                            : new FieldValuesException(FieldValuesError.VALUES_RECORD_UNKNOWN, "No record "
                            + next.ref());
                }
            }
            try (PreparedStatement ps = c.prepareStatement("delete from fld_value where record_uuid = ?")) {
                ps.setObject(1, next.id());
                ps.executeUpdate();
            }
            insertValues(c, next);
            return next.next();
        });
    }

    @Override
    public void delete(StoredRecord record, com.documentplatform.fields.values.EraseTicket ticket) {
        com.documentplatform.fields.values.EraseTicket.check(ticket, record);
        inTransaction("erase a record", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "select 1 from fld_record where record_uuid = ? and version = ? for update")) {
                ps.setObject(1, record.id());
                ps.setInt(2, record.version());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        boolean exists = find(c, record.ref()).isPresent();
                        throw exists
                                ? new FieldValuesException(FieldValuesError.VALUES_CHANGED_MEANWHILE, "Record "
                                + record.ref() + " was changed meanwhile")
                                : new FieldValuesException(FieldValuesError.VALUES_RECORD_UNKNOWN, "No record "
                                + record.ref());
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("delete from fld_value where record_uuid = ?")) {
                ps.setObject(1, record.id());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("delete from fld_record where record_uuid = ?")) {
                ps.setObject(1, record.id());
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<RecordRef> search(UUID customer, FieldScope scope, String recordType, FieldKey key, SearchMatch match,
                                  int limit) {
        String condition;
        Object term;
        switch (match) {
            case SearchMatch.Exact e -> {
                condition = "v.search_value = any (?)";
                term = e.values();
            }
            case SearchMatch.Prefix p -> {
                condition = "v.search_value like ? escape '\\'";
                term = like(p.start()) + "%";
            }
            case SearchMatch.Partial p -> {
                condition = "v.search_value like ? escape '\\'";
                term = "%" + like(p.part()) + "%";
            }
            case SearchMatch.Fingerprints f -> {
                condition = "v.fingerprint = any (?)";
                term = f.fingerprints();
            }
        }
        return run("search records", c -> {
            try (PreparedStatement ps = c.prepareStatement("select distinct r.record_id from fld_value v "
                    + "join fld_record r using (record_uuid) where v.customer_id = ? and v.entity_uuid = ? "
                    + "and v.product_code = ? and v.record_type = ? and v.field_key = ? and " + condition
                    + " order by r.record_id limit ?")) {
                ps.setObject(1, customer);
                ps.setObject(2, scope.entity());
                ps.setString(3, scope.product());
                ps.setString(4, recordType);
                ps.setString(5, key.value());
                if (term instanceof List<?> list) {
                    ps.setArray(6, c.createArrayOf("text", list.toArray()));
                } else {
                    ps.setString(6, (String) term);
                }
                ps.setInt(7, limit);
                List<RecordRef> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(RecordRef.of(scope, recordType, rs.getString(1)));
                    }
                }
                return out;
            }
        });
    }

    @Override
    public boolean partialSearchAvailable() {
        return run("check partial search", c -> {
            try (PreparedStatement ps = c.prepareStatement("select exists (select 1 from pg_indexes "
                    + "where schemaname = current_schema() and indexname = ?)")) {
                ps.setString(1, FieldSchema.PARTIAL_SEARCH_INDEX);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getBoolean(1);
                }
            }
        });
    }

    @Override
    public boolean valuesExist(FieldScope scope, FieldKey key) {
        return run("check whether a field has values", c -> {
            try (PreparedStatement ps = c.prepareStatement("select exists (select 1 from fld_value "
                    + "where entity_uuid = ? and product_code = ? and lower(field_key) = lower(?))")) {
                ps.setObject(1, scope.entity());
                ps.setString(2, scope.product());
                ps.setString(3, key.value());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getBoolean(1);
                }
            }
        });
    }

    // ---- inside ----

    private static String like(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Optional<StoredRecord> find(Connection c, RecordRef ref) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select " + RECORD + " from fld_record r where "
                + "r.entity_uuid = ? and r.product_code = ? and r.record_type = ? and r.record_id = ?")) {
            ps.setObject(1, ref.scope().entity());
            ps.setString(2, ref.scope().product());
            ps.setString(3, ref.recordType());
            ps.setString(4, ref.recordId());
            return one(c, ps);
        }
    }

    private static Optional<StoredRecord> byIdentity(Connection c, FieldScope scope, String recordType,
                                                     Collection<String> identities) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select " + RECORD + " from fld_record r where "
                + "r.entity_uuid = ? and r.product_code = ? and r.record_type = ? and r.identity = any (?) "
                + "order by r.created_at limit 1")) {
            ps.setObject(1, scope.entity());
            ps.setString(2, scope.product());
            ps.setString(3, recordType);
            ps.setArray(4, c.createArrayOf("text", identities.toArray()));
            return one(c, ps);
        }
    }

    private static Optional<StoredRecord> one(Connection c, PreparedStatement ps) throws SQLException {
        Row row;
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return Optional.empty();
            }
            row = row(rs);
        }
        List<StoredValue> values = new ArrayList<>();
        try (PreparedStatement vs = c.prepareStatement("select field_key, position, sensitive, display_value, "
                + "normalised_value, search_value, sealed_value, fingerprint from fld_value where record_uuid = ? "
                + "order by lower(field_key), position")) {
            vs.setObject(1, row.id);
            try (ResultSet rs = vs.executeQuery()) {
                while (rs.next()) {
                    values.add(value(rs));
                }
            }
        }
        return Optional.of(row.with(values));
    }

    /** A record's own columns, before its values. */
    private record Row(UUID id, UUID customer, RecordRef ref, String identity, String envelope, int version,
                       Instant created, Instant updated) {
        StoredRecord with(List<StoredValue> values) {
            return new StoredRecord(id, customer, ref, identity, envelope, version, created, updated, values);
        }
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(rs.getObject("record_uuid", UUID.class), rs.getObject("customer_id", UUID.class),
                RecordRef.of(FieldScope.of(rs.getObject("entity_uuid", UUID.class), rs.getString("product_code")),
                        rs.getString("record_type"), rs.getString("record_id")),
                rs.getString("identity"), rs.getString("envelope"), rs.getInt("version"), instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static StoredValue value(ResultSet rs) throws SQLException {
        return new StoredValue(FieldKey.of(rs.getString("field_key")), rs.getInt("position"),
                rs.getBoolean("sensitive"), rs.getString("display_value"), rs.getString("normalised_value"),
                rs.getString("search_value"), rs.getString("sealed_value"), rs.getString("fingerprint"));
    }

    private static void insertValues(Connection c, StoredRecord r) throws SQLException {
        if (r.values().isEmpty()) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement("""
                insert into fld_value (record_uuid, customer_id, entity_uuid, product_code, record_type, field_key,
                    position, sensitive, display_value, normalised_value, search_value, sealed_value, fingerprint)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            for (StoredValue v : r.values()) {
                ps.setObject(1, r.id());
                ps.setObject(2, r.customer());
                ps.setObject(3, r.ref().scope().entity());
                ps.setString(4, r.ref().scope().product());
                ps.setString(5, r.ref().recordType());
                ps.setString(6, v.key().value());
                ps.setShort(7, (short) v.position());
                ps.setBoolean(8, v.sensitive());
                ps.setString(9, v.display());
                ps.setString(10, v.normalised());
                ps.setString(11, v.search());
                ps.setString(12, v.sealed());
                ps.setString(13, v.fingerprint());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private <T> T run(String operation, Sql.Work<T> work) {
        try (Connection c = ds.getConnection()) {
            return work.run(c);
        } catch (SQLException e) {
            throw failed(operation, e);
        }
    }

    private <T> T inTransaction(String operation, Sql.Work<T> work) {
        try (Connection c = ds.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw failed(operation, e);
        }
    }

    /** SQL state only: the driver's text can quote the values written. */
    private static FieldValuesException failed(String operation, SQLException e) {
        String state = e.getSQLState();
        if (e instanceof java.sql.BatchUpdateException b && b.getNextException() != null) {
            state = b.getNextException().getSQLState();
        }
        return new FieldValuesException(FieldValuesError.VALUES_STORE_FAILED, "Field values: could not " + operation
                + " (SQL state " + state + ")");
    }
}
