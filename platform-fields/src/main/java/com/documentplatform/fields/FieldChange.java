package com.documentplatform.fields;

import java.util.Objects;

/**
 * One change in {@link FieldDefinitions#change(FieldScope, java.util.List, String)}: the field as it is to be, and the
 * version the person saw; version 0 adds the field.
 */
public record FieldChange(FieldSpec next, int expectedVersion) {

    public FieldChange {
        Objects.requireNonNull(next, "next");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion is 0 (a new field) or the version that was seen");
        }
    }

    /** Adds a field. */
    public static FieldChange create(FieldSpec spec) {
        return new FieldChange(spec, 0);
    }
}
