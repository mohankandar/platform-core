package com.documentplatform.fields;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Where field definitions are kept: Postgres ({@code jdbc.PostgresFieldStore}, the {@code fld_} tables) or in memory
 * for tests (testkit). Plain storage; the rules for changes are in {@link FieldDefinitions}, which apps use.
 *
 * <p>A store never deletes a definition, never changes a key, and keeps every version in the history.
 *
 * <p>SPI: implemented by stores; apps call {@link FieldDefinitions}.
 */
public interface FieldStore {

    /** Every field of the entity and product, switched-off ones included, by display order then key. */
    List<FieldDefinition> list(FieldScope scope);

    /** The field whose key equals this one ignoring capitals (there is at most one). */
    Optional<FieldDefinition> find(FieldScope scope, FieldKey key);

    /**
     * Saves new fields, all or none, each at version 1 with a history entry.
     *
     * @throws FieldDefinitionException {@code FIELD_KEY_TAKEN} if a key is already used (ignoring capitals)
     */
    List<FieldDefinition> create(FieldScope scope, List<FieldSpec> specs, Change change, String actor, Instant at);

    /**
     * Saves the next version of a field if it is still at {@code current.version()}, with a history entry.
     *
     * @throws FieldDefinitionException {@code FIELD_CHANGED_MEANWHILE} if another change came first,
     *                                  {@code FIELD_UNKNOWN} if there is no such field
     */
    FieldDefinition update(FieldDefinition current, FieldSpec next, Change change, String actor, Instant at);

    /**
     * Saves every field in the list, or none (one transaction), each as {@link #create} ({@code CREATED}) or
     * {@link #update} ({@code CHANGED}) would, with a history entry; answered in the list's order. The keys are
     * different ones.
     *
     * @throws FieldDefinitionException as {@link #create} and {@link #update}; nothing is saved then
     * @throws UnsupportedOperationException (the default) for a store that cannot save a list whole; nothing is saved
     */
    default List<FieldDefinition> saveAll(FieldScope scope, List<Save> saves, String actor, Instant at) {
        throw new UnsupportedOperationException(getClass().getName() + " cannot save a list of fields all or none: "
                + "implement FieldStore.saveAll");
    }

    /**
     * One field of {@link #saveAll}.
     *
     * @param current the stored field to change, or null to add one
     */
    record Save(FieldDefinition current, FieldSpec next) {
        public Save {
            Objects.requireNonNull(next, "next");
        }
    }

    /** Every version of the field, oldest first. Empty if there is no such field. */
    List<HistoryEntry> history(FieldScope scope, FieldKey key);

    /** The app's own settings for the field (for example "can be extracted"), by name. Not part of the model. */
    Map<String, String> extensions(FieldScope scope, FieldKey key);

    /** Sets, or with a null value removes, one of the app's settings for an existing field. */
    void putExtension(FieldScope scope, FieldKey key, String name, String value, Instant at);

    /** STUB P059 */
    default FieldStore joined(java.sql.Connection connection) {
        throw new UnsupportedOperationException("stub");
    }

    /** Why a version was written. */
    enum Change {
        CREATED, IMPORTED, CHANGED, SWITCHED_OFF, SWITCHED_ON
    }

    /** One version of a field. */
    record HistoryEntry(int version, Change change, Instant at, String by, FieldSpec spec) {
        public HistoryEntry {
            Objects.requireNonNull(change, "change");
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(by, "by");
            Objects.requireNonNull(spec, "spec");
        }
    }
}
