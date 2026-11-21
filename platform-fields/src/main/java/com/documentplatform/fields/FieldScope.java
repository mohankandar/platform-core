package com.documentplatform.fields;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Whose field list: one entity (its {@code entity_uuid}, derived from the entity slot) in one product. Each app keeps
 * its own field definitions per entity.
 *
 * @param entity  the entity's UUID, as in the app's own tables
 * @param product the product code, as on group grants (2–32 capital letters, digits or '_', starting with a letter)
 */
public record FieldScope(UUID entity, String product) {

    private static final Pattern PRODUCT = Pattern.compile("[A-Z][A-Z0-9_]{1,31}");

    public FieldScope {
        Objects.requireNonNull(entity, "entity");
        if (product == null || !PRODUCT.matcher(product).matches()) {
            throw new FieldDefinitionException(FieldDefinitionException.Code.FIELD_PRODUCT_INVALID,
                    "A product code has 2 to 32 capital letters, digits or '_' and starts with a letter");
        }
    }

    public static FieldScope of(UUID entity, String product) {
        return new FieldScope(entity, product);
    }

    @Override
    public String toString() {
        return "entity " + entity + ", product " + product;
    }
}
