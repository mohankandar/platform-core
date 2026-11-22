package com.documentplatform.fields.values;

import com.documentplatform.fields.FieldKey;
import com.documentplatform.fields.ValidValue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A record's values as read back. From {@link FieldValues#read} sensitive values are hidden (only how many there
 * are); from {@link FieldValues#readSensitive} they are opened. {@code toString()} shows no value.
 *
 * @param ref       the record
 * @param version   the record's version
 * @param opened    whether sensitive values were opened
 * @param fields    per field key, in key order
 */
public record RecordValues(RecordRef ref, int version, boolean opened, Map<FieldKey, Field> fields) {

    public RecordValues {
        Objects.requireNonNull(ref, "ref");
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    /**
     * One field's values.
     *
     * @param key       the field's key
     * @param sensitive the values are sensitive
     * @param count     how many values the record holds
     * @param values    the values in order; empty when they are sensitive and were not opened
     */
    public record Field(FieldKey key, boolean sensitive, int count, List<ValidValue> values) {
        public Field {
            Objects.requireNonNull(key, "key");
            values = List.copyOf(values);
        }

        /** Sensitive values that were not opened. */
        public boolean hidden() {
            return sensitive && count > 0 && values.isEmpty();
        }

        @Override
        public String toString() {
            return "Field[" + key + ", " + count + (sensitive ? " sensitive" : "") + (hidden() ? ", hidden" : "")
                    + "]";
        }
    }

    public Optional<Field> field(String key) {
        return FieldKey.isValid(key) ? Optional.ofNullable(fields.get(FieldKey.of(key))) : Optional.empty();
    }

    /** The values of a field, or an empty list (also when they are hidden). */
    public List<ValidValue> values(String key) {
        return field(key).map(Field::values).orElse(List.of());
    }

    @Override
    public String toString() {
        return "RecordValues[" + ref + ", version " + version + ", " + fields.size() + " fields"
                + (opened ? ", opened" : "") + "]";
    }
}
