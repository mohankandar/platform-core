package com.documentplatform.fields.values;

import com.documentplatform.fields.FieldKey;

import java.util.Objects;

/**
 * One stored value row, as a {@link ValueStore} keeps it. Not sensitive: {@code display}, {@code normalised} and
 * {@code search} are set, {@code sealed} and {@code fingerprint} are not. Sensitive: only {@code sealed} and, when the
 * field is searchable or primary, {@code fingerprint}; the readable parts are null. Built by {@link FieldValues};
 * apps do not make these. {@code toString()} shows no value.
 *
 * @param key         the field's key
 * @param position    0 for a single value; 0–49 for the values of a multi-value field, in order
 * @param sensitive   the field was sensitive when the value was written
 * @param display     not sensitive: the display form (an address: its parts separated by U+001F)
 * @param normalised  not sensitive: the normalised form (same encoding)
 * @param search      not sensitive: the form search and the primary rule compare (lower case, single spaces)
 * @param sealed      sensitive: the value sealed under the record's envelope ({@code pci1.})
 * @param fingerprint sensitive and searchable or primary: the keyed fingerprint ({@code f<version>.<mac>})
 */
public record StoredValue(FieldKey key, int position, boolean sensitive, String display, String normalised,
                          String search, String sealed, String fingerprint) {

    public StoredValue {
        Objects.requireNonNull(key, "key");
        if (position < 0 || position > 49) {
            throw new IllegalArgumentException("A value position is 0 to 49");
        }
        if (sensitive) {
            if (sealed == null || display != null || normalised != null || search != null) {
                throw new IllegalArgumentException("A sensitive value is stored sealed only");
            }
        } else if (display == null || normalised == null || search == null || sealed != null || fingerprint != null) {
            throw new IllegalArgumentException("A value that is not sensitive has its display, normalised and search "
                    + "forms and nothing sealed");
        }
    }

    @Override
    public String toString() {
        return "StoredValue[" + key + "#" + position + (sensitive ? ", sensitive" : "") + "]";
    }
}
