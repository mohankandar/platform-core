package com.documentplatform.fields.values;

import com.documentplatform.fields.FieldKey;
import com.documentplatform.fields.FieldScope;
import com.documentplatform.fields.FieldValuesCheck;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Where field values are kept: Postgres ({@code jdbc.PostgresValueStore}, tables {@code fld_record} and
 * {@code fld_value}) or in memory for tests (testkit). Plain storage of rows {@link FieldValues} has prepared
 * (validated, sealed, fingerprinted); the rules are in {@link FieldValues}, which apps use.
 *
 * <p>Every store enforces, also with concurrent writers: one record per record type and ID, and one record per
 * identity (primary value set) in each entity, product and record type. A store is also the app's
 * {@link FieldValuesCheck}: values exist for a field once any record holds one.
 *
 * <p><b>An SPI, not for apps to call.</b> Apps read and write values only through {@link FieldValues}, and erase
 * only through the erase log; a store is handed to {@code FieldValues} and used by it alone.
 *
 * <p>SPI: implemented by stores; apps call {@link FieldValues}.
 */
public interface ValueStore extends FieldValuesCheck {

    /** The record with this type and ID in the entity and product. */
    Optional<StoredRecord> find(RecordRef ref);

    /**
     * The records with these types and IDs in their entities and products (those that exist), by what was asked for.
     * One query where the store can ({@code PostgresValueStore}); the default asks {@link #find} for each. Like
     * {@code find}, whatever the customer: {@link FieldValues} checks that.
     */
    default Map<RecordRef, StoredRecord> findAll(Collection<RecordRef> refs) {
        Map<RecordRef, StoredRecord> out = new LinkedHashMap<>();
        for (RecordRef ref : refs) {
            find(ref).ifPresent(r -> out.put(ref, r));
        }
        return out;
    }

    /** The record of the entity, product and record type that has one of these identities. */
    Optional<StoredRecord> findByIdentity(FieldScope scope, String recordType, Collection<String> identities);

    /**
     * Stores a new record at version 1, unless a record with the same type and ID, or the same identity, exists (or is
     * being written by another writer, which this call waits for): then nothing is written and that record is
     * returned.
     */
    Inserted insert(StoredRecord record);

    /** What {@link #insert} did. */
    record Inserted(StoredRecord record, boolean created) {
    }

    /**
     * Writes {@code next}'s identity, envelope and values in place of the record's, if the record is still at
     * {@code next.version()}; returns the record at the next version.
     *
     * @throws FieldValuesException {@code VALUES_CHANGED_MEANWHILE} if another write came first,
     *                              {@code VALUES_PRIMARY_TAKEN} if another record has the new identity,
     *                              {@code VALUES_RECORD_UNKNOWN} if there is no such record
     */
    StoredRecord replace(StoredRecord next);

    /**
     * Deletes the record, its envelope and every value row, if the record is still at {@code version}. Only with a
     * ticket for this record ({@link EraseTicket#check}), which only {@link FieldValues} makes during an erasure
     * through the erase log; without one: {@code VALUES_ERASE_OUTSIDE_LOG}, nothing deleted.
     *
     * @throws FieldValuesException {@code VALUES_CHANGED_MEANWHILE} if another write came first,
     *                              {@code VALUES_RECORD_UNKNOWN} if there is no such record
     */
    void delete(StoredRecord record, EraseTicket ticket);

    /**
     * The records of the customer, entity, product and record type with a value of the field that matches, ordered by
     * record ID, at most {@code limit}.
     */
    List<RecordRef> search(UUID customer, FieldScope scope, String recordType, FieldKey key, SearchMatch match,
                           int limit);

    /**
     * Whether partial search can run (Postgres: the trigram index exists). When it cannot, {@link FieldValues} runs
     * partial search as prefix search and says so.
     */
    boolean partialSearchAvailable();

    /** STUB P059 */
    default ValueStore joined(java.sql.Connection connection) {
        throw new UnsupportedOperationException("stub");
    }
}
