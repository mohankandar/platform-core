package com.documentplatform.fields;

import java.util.Objects;

/**
 * A value that passed its field's rules.
 *
 * @param display    the value as entered, trimmed, with single spaces (a DROPDOWN value: the listed value; an address:
 *                   its parts tidied, state in capitals, ZIP as {@code 12345} or {@code 12345-6789})
 * @param normalised the form used for matching and searching: the field's normalisation applied (NUMBER keeps leading
 *                   zeros; MONEY as {@code 1250.00}; DATE as {@code 2026-11-21}; UUID in lower case)
 */
public record ValidValue(FieldValue display, FieldValue normalised) {

    public ValidValue {
        Objects.requireNonNull(display, "display");
        Objects.requireNonNull(normalised, "normalised");
    }

    /** The normalised text of a value that is not an address. */
    public String normalisedText() {
        if (!(normalised instanceof FieldValue.Text t)) {
            throw new IllegalStateException("An address has parts, not one text");
        }
        return t.value();
    }

    @Override
    public String toString() {
        return "ValidValue[hidden]";
    }
}
