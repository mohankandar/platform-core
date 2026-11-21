package com.documentplatform.fields;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A stored field definition: the spec, which entity and product it belongs to, and its version.
 *
 * @param id        stable id of the definition (for the app's value tables later)
 * @param scope     the entity and product
 * @param spec      what the field is
 * @param version   1 when created, one more with every change
 * @param updatedBy the user id (or process name) of the last change
 */
public record FieldDefinition(UUID id, FieldScope scope, FieldSpec spec, int version, Instant createdAt,
                              Instant updatedAt, String updatedBy) {

    public FieldDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(spec, "spec");
        if (version < 1) {
            throw new IllegalArgumentException("A version starts at 1");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(updatedBy, "updatedBy");
    }

    public FieldKey key() {
        return spec.key();
    }

    public FieldType type() {
        return spec.type();
    }

    public boolean active() {
        return spec.active();
    }
}
