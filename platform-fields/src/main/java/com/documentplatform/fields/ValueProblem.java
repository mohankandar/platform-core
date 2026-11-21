package com.documentplatform.fields;

import java.util.Objects;

/**
 * One refused value.
 *
 * @param key     the field's key; null when the key itself was not valid
 * @param index   for a multi-value field, which value (0-based); null for a field with one value
 * @param code    why
 * @param message plain text naming the field's label and the rule; never the value
 */
public record ValueProblem(FieldKey key, Integer index, ValueError code, String message) {

    public ValueProblem {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }

    @Override
    public String toString() {
        return code + (key == null ? "" : " " + key) + (index == null ? "" : "[" + index + "]") + ": " + message;
    }
}
