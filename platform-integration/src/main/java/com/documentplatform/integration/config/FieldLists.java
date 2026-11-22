package com.documentplatform.integration.config;

import com.documentplatform.fields.FieldSet;

import java.util.Optional;
import java.util.UUID;

/**
 * The entity's case field list per product, from platform-fields (supplied by the app, for example over
 * {@code FieldDefinitions}). Used when a configuration is saved to check every {@code {field:<key>}} placeholder: the
 * field exists, and a sensitive one is allowed for the integration.
 */
@FunctionalInterface
public interface FieldLists {

    Optional<FieldSet> fieldsOf(UUID entity, String product);

    /** No field lists: any {@code {field:<key>}} placeholder is refused when saved. */
    FieldLists NONE = (entity, product) -> Optional.empty();
}
