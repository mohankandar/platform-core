package com.documentplatform.fields.values;

/**
 * Supplied by the app: whether a record is under legal hold, so that {@link FieldValues#erase} refuses it. The app
 * answers from its own data (a hold on the case, the customer or the entity). If it cannot answer, it throws, and the
 * erase is refused ({@code VALUES_LEGAL_HOLD_UNKNOWN}).
 */
@FunctionalInterface
public interface LegalHolds {

    /** No record is ever held: for apps without legal holds. */
    LegalHolds NONE = ref -> false;

    boolean held(RecordRef ref);
}
