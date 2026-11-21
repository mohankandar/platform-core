package com.documentplatform.fields;

/**
 * How a searchable field is searched. Sensitive fields are searched by exact match only (through a one-way
 * fingerprint of the normalised value, built with value storage); partial search is only for fields that are not
 * sensitive.
 */
public enum SearchMode {
    /** The whole normalised value. */
    EXACT,
    /** The start of the value. Not for sensitive fields. */
    PREFIX,
    /** Any part of the value. Not for sensitive fields. */
    PARTIAL
}
