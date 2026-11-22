package com.documentplatform.fields.values;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One stored record with its values, as a {@link ValueStore} keeps it. Built by {@link FieldValues}.
 *
 * @param id        the store's own ID of the record
 * @param customer  the customer (key owner) the record belongs to
 * @param ref       the app's record
 * @param identity  the hash of the record's primary value(s), or null when it has none
 * @param envelope  the record envelope ({@code pck1.}) when it has sensitive values, else null
 * @param version   1 when new, one more with each change
 * @param createdAt when it was first written
 * @param updatedAt when it was last written
 * @param values    every value row
 */
public record StoredRecord(UUID id, UUID customer, RecordRef ref, String identity, String envelope, int version,
                           Instant createdAt, Instant updatedAt, List<StoredValue> values) {

    public StoredRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(customer, "customer");
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        values = List.copyOf(values);
        if (version < 1) {
            throw new IllegalArgumentException("A record version is 1 or more");
        }
    }

    /** The same record (same version) with other contents, for {@link ValueStore#replace}. */
    public StoredRecord with(String identity, String envelope, Instant at, List<StoredValue> values) {
        return new StoredRecord(id, customer, ref, identity, envelope, version, createdAt, at, values);
    }

    /** The record as stored at the next version. */
    public StoredRecord next() {
        return new StoredRecord(id, customer, ref, identity, envelope, version + 1, createdAt, updatedAt, values);
    }

    @Override
    public String toString() {
        return "StoredRecord[" + ref + ", version " + version + ", " + values.size() + " values]";
    }
}
