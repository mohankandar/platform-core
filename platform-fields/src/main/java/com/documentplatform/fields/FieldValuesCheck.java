package com.documentplatform.fields;

/**
 * Supplied by the app, which stores the values: whether any value exists for a field (in a document, a case, a
 * delivery). Asked before a field's type changes; once values exist, its key and type never change.
 *
 * <p>Must answer from the app's own data, including switched-off fields' old values. If it cannot answer (database
 * down), it throws, and the change is refused.
 */
@FunctionalInterface
public interface FieldValuesCheck {

    boolean valuesExist(FieldScope scope, FieldKey key);
}
