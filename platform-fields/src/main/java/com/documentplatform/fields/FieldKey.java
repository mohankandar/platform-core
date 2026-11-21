package com.documentplatform.fields;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A field's stable key, for example {@code contract_number}. Starts with a letter; at most 100 letters, digits, '_',
 * '.' or '-'. Kept exactly as given; two keys of one entity and product may not differ only in capitals.
 *
 * <p>Matching (import, delivery) is by the exact key.
 */
public record FieldKey(String value) implements Comparable<FieldKey> {

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,99}");

    public FieldKey {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new FieldDefinitionException(FieldDefinitionException.Code.FIELD_KEY_INVALID,
                    "A field key starts with a letter and has at most 100 letters, digits, '_', '.' or '-'");
        }
    }

    public static FieldKey of(String value) {
        return new FieldKey(value);
    }

    /** True if the text is a valid key. */
    public static boolean isValid(String value) {
        return value != null && FORMAT.matcher(value).matches();
    }

    /** The key in lower case: two keys with the same folded form cannot both exist in one entity and product. */
    public String folded() {
        return value.toLowerCase(Locale.ROOT);
    }

    public boolean sameIgnoringCase(FieldKey other) {
        return folded().equals(Objects.requireNonNull(other, "other").folded());
    }

    @Override
    public int compareTo(FieldKey o) {
        return value.compareTo(o.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
