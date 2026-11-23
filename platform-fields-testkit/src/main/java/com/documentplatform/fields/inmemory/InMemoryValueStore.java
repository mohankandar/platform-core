package com.documentplatform.fields.inmemory;

import com.documentplatform.fields.FieldKey;
import com.documentplatform.fields.FieldScope;
import com.documentplatform.fields.values.FieldValuesError;
import com.documentplatform.fields.values.FieldValuesException;
import com.documentplatform.fields.values.RecordRef;
import com.documentplatform.fields.values.SearchMatch;
import com.documentplatform.fields.values.StoredRecord;
import com.documentplatform.fields.values.StoredValue;
import com.documentplatform.fields.values.ValueStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Field values in memory, with the same rules as the Postgres store: one record per record type and ID, one per
 * identity in each entity, product and record type, a change only from the version read. Partial search can be
 * switched off to test the prefix fallback. For tests.
 */
public final class InMemoryValueStore implements ValueStore {

    private final Map<UUID, StoredRecord> records = new LinkedHashMap<>();
    private volatile boolean partial = true;

    /** Whether partial search is available (Postgres: the trigram index exists). On by default. */
    public InMemoryValueStore partialSearch(boolean available) {
        this.partial = available;
        return this;
    }

    /** Every stored record, for tests that look at the raw rows. */
    public synchronized List<StoredRecord> all() {
        return List.copyOf(records.values());
    }

    @Override
    public synchronized Optional<StoredRecord> find(RecordRef ref) {
        return records.values().stream().filter(r -> r.ref().equals(ref)).findFirst();
    }

    @Override
    public synchronized Optional<StoredRecord> findByIdentity(FieldScope scope, String recordType,
                                                              Collection<String> identities) {
        return records.values().stream().filter(r -> sameKind(r, scope, recordType) && r.identity() != null
                && identities.contains(r.identity())).min(Comparator.comparing(StoredRecord::createdAt));
    }

    @Override
    public synchronized Inserted insert(StoredRecord record) {
        Optional<StoredRecord> other = find(record.ref());
        if (other.isEmpty() && record.identity() != null) {
            other = findByIdentity(record.ref().scope(), record.ref().recordType(), List.of(record.identity()));
        }
        if (other.isPresent()) {
            return new Inserted(other.get(), false);
        }
        StoredRecord stored = new StoredRecord(record.id(), record.customer(), record.ref(), record.identity(),
                record.envelope(), 1, record.createdAt(), record.updatedAt(), record.values());
        records.put(stored.id(), stored);
        return new Inserted(stored, true);
    }

    @Override
    public synchronized StoredRecord replace(StoredRecord next) {
        if (next.values().isEmpty()) {
            // Same rule as the Postgres store (P044, SEC-5): taking every value away is an erasure.
            throw new FieldValuesException(FieldValuesError.VALUES_ERASE_OUTSIDE_LOG, "Record " + next.ref() + " was "
                    + "not changed: removing every value of a record is an erasure, done only through the erase log");
        }
        StoredRecord current = records.get(next.id());
        if (current == null) {
            throw new FieldValuesException(FieldValuesError.VALUES_RECORD_UNKNOWN, "No record " + next.ref());
        }
        if (current.version() != next.version()) {
            throw new FieldValuesException(FieldValuesError.VALUES_CHANGED_MEANWHILE, "Record " + next.ref()
                    + " was changed meanwhile");
        }
        if (next.identity() != null) {
            for (StoredRecord r : records.values()) {
                if (!r.id().equals(next.id()) && sameKind(r, next.ref().scope(), next.ref().recordType())
                        && next.identity().equals(r.identity())) {
                    throw new FieldValuesException(FieldValuesError.VALUES_PRIMARY_TAKEN, "Another record already "
                            + "has the primary value(s) given for record " + next.ref());
                }
            }
        }
        StoredRecord stored = next.next();
        records.put(stored.id(), stored);
        return stored;
    }

    @Override
    public synchronized void delete(StoredRecord record, com.documentplatform.fields.values.EraseTicket ticket) {
        com.documentplatform.fields.values.EraseTicket.check(ticket, record);
        StoredRecord current = records.get(record.id());
        if (current == null) {
            throw new FieldValuesException(FieldValuesError.VALUES_RECORD_UNKNOWN, "No record " + record.ref());
        }
        if (current.version() != record.version()) {
            throw new FieldValuesException(FieldValuesError.VALUES_CHANGED_MEANWHILE, "Record " + record.ref()
                    + " was changed meanwhile");
        }
        records.remove(record.id());
    }

    @Override
    public synchronized List<RecordRef> search(UUID customer, FieldScope scope, String recordType, FieldKey key,
                                               SearchMatch match, int limit) {
        List<RecordRef> out = new ArrayList<>();
        for (StoredRecord r : records.values()) {
            if (!r.customer().equals(customer) || !sameKind(r, scope, recordType)) {
                continue;
            }
            for (StoredValue v : r.values()) {
                if (v.key().equals(key) && matches(v, match)) {
                    out.add(r.ref());
                    break;
                }
            }
        }
        out.sort(Comparator.comparing(RecordRef::recordId));
        return out.size() > limit ? List.copyOf(out.subList(0, limit)) : List.copyOf(out);
    }

    @Override
    public boolean partialSearchAvailable() {
        return partial;
    }

    @Override
    public synchronized boolean valuesExist(FieldScope scope, FieldKey key) {
        return records.values().stream().filter(r -> r.ref().scope().equals(scope))
                .flatMap(r -> r.values().stream()).anyMatch(v -> v.key().sameIgnoringCase(key));
    }

    private static boolean matches(StoredValue v, SearchMatch match) {
        return switch (match) {
            case SearchMatch.Exact e -> v.search() != null && e.values().contains(v.search());
            case SearchMatch.Prefix p -> v.search() != null && v.search().startsWith(p.start());
            case SearchMatch.Partial p -> v.search() != null && v.search().contains(p.part());
            case SearchMatch.Fingerprints f -> v.fingerprint() != null && f.fingerprints().contains(v.fingerprint());
        };
    }

    private static boolean sameKind(StoredRecord r, FieldScope scope, String recordType) {
        return r.ref().scope().equals(scope) && Objects.equals(r.ref().recordType(), recordType);
    }

    @Override
    public synchronized String toString() {
        return "InMemoryValueStore[" + records.size() + " records]";
    }
}
