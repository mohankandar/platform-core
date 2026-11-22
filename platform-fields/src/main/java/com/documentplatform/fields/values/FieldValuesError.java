package com.documentplatform.fields.values;

/**
 * Why a value storage call was refused. Stable codes; each comes with a plain message naming the record, the field's
 * key or label and the rule, never a value.
 */
public enum FieldValuesError {
    /** The record type or record ID is not valid. */
    VALUES_RECORD_REF_INVALID,
    /** One or more values broke their field's rules; {@link FieldValuesException#problems()} lists them. */
    VALUES_INVALID,
    /** No record with that type and ID in the entity and product. */
    VALUES_RECORD_UNKNOWN,
    /** The record belongs to another customer than the one this app instance serves. */
    VALUES_WRONG_CUSTOMER,
    /** The record's new primary value(s) are already another record's (one record per primary value set). */
    VALUES_PRIMARY_TAKEN,
    /**
     * A claim found the record by its primary value(s) (or its ID), but it already holds a different value for a
     * field given; nothing was written. Only the field keys are named.
     */
    VALUES_CONFLICT,
    /** The record kept changing while it was written (other writers); try again. */
    VALUES_CHANGED_MEANWHILE,
    /** Search or lookup named a key the entity has no field for. */
    VALUES_FIELD_UNKNOWN,
    /** Search on a field that is not searchable. */
    VALUES_NOT_SEARCHABLE,
    /** Prefix or partial search on a sensitive field: sensitive fields are searchable by exact match only. */
    VALUES_SENSITIVE_EXACT_ONLY,
    /** A search mode the field does not offer (for example partial search on an exact-match field). */
    VALUES_SEARCH_MODE_NOT_ALLOWED,
    /** A lookup by primary value named a field that is not primary, or gave no primary value. */
    VALUES_NOT_PRIMARY,
    /** The app's legal hold check says the record is held, so its values may not be erased. */
    VALUES_LEGAL_HOLD,
    /** The app's legal hold check could not answer, so the erase was refused (nothing was erased). */
    VALUES_LEGAL_HOLD_UNKNOWN,
    /**
     * {@link FieldValues#erase} was called from outside the erase log: a record is erased only through the erase
     * log's case-fields handler, so that every erasure is logged (nothing was erased).
     */
    VALUES_ERASE_OUTSIDE_LOG,
    /** The database refused or failed the call (the message has the SQL state only). */
    VALUES_STORE_FAILED
}
