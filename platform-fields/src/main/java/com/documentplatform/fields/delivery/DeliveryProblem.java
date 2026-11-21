package com.documentplatform.fields.delivery;

import com.documentplatform.fields.FieldKey;
import com.documentplatform.fields.ValueError;

import java.util.Objects;

/**
 * One reason a delivery's case fields were refused.
 *
 * @param code       why, as a stable code
 * @param entry      which entry of {@code fields} (1 = first); null for the delivery as a whole or a missing field
 * @param key        the field's key, when it is a valid key
 * @param valueError for {@code DELIVERY_VALUE_INVALID}: the value's own code
 * @param message    plain text; names entries, keys, labels and rules, never a value
 */
public record DeliveryProblem(Code code, Integer entry, FieldKey key, ValueError valueError, String message) {

    public enum Code {
        /** Not JSON of the strict subset used. */
        DELIVERY_UNREADABLE,
        /** Larger than 2 MB or more than 1000 entries. */
        DELIVERY_TOO_LARGE,
        /** Not a {@code platform-field-values/1} document. */
        DELIVERY_FORMAT_UNKNOWN,
        /** An entry or member is missing, unknown or of the wrong kind. */
        DELIVERY_SHAPE_INVALID,
        /** An entry's key is not a valid field key. */
        DELIVERY_KEY_INVALID,
        /** Two entries with the same key. */
        DELIVERY_KEY_REPEATED,
        /** No field with this key here. */
        DELIVERY_KEY_UNKNOWN,
        /** The field here is switched off. */
        DELIVERY_FIELD_INACTIVE,
        /** The entry's type is not the type of the field here. */
        DELIVERY_TYPE_MISMATCH,
        /** MONEY in another currency than the field here. */
        DELIVERY_CURRENCY_MISMATCH,
        /** A list of values for a field that takes one, or one value for a multi-value field. */
        DELIVERY_VALUE_COUNT_MISMATCH,
        /** A value breaks the field's rules; see {@link #valueError()}. */
        DELIVERY_VALUE_INVALID,
        /** A required field here has no entry. */
        DELIVERY_REQUIRED_MISSING
    }

    public DeliveryProblem {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }

    @Override
    public String toString() {
        return code + (entry == null ? "" : " entry " + entry) + (key == null ? "" : " " + key) + ": " + message;
    }
}
