package com.documentplatform.fields;

import java.util.Objects;

/**
 * A field definition, or a change to one, is not allowed. {@link #code()} says why, as a stable code; the message is
 * plain text for the admin. Messages name the field's key or label and the rule, never a value.
 */
public final class FieldDefinitionException extends IllegalArgumentException {

    /** Why a definition or change was refused. */
    public enum Code {
        /** The key does not start with a letter or has other characters than letters, digits, '_', '.', '-'. */
        FIELD_KEY_INVALID,
        /** The label is blank, longer than 200 characters or has control characters. */
        FIELD_LABEL_INVALID,
        /** The product code is not 2–32 capital letters, digits or '_', starting with a letter. */
        FIELD_PRODUCT_INVALID,
        /** A MONEY field needs its currency. */
        FIELD_CURRENCY_REQUIRED,
        /** The currency is not an ISO 4217 code with a fixed number of decimals (USD, EUR, ...). */
        FIELD_CURRENCY_INVALID,
        /** A rule was given for a type it does not apply to (for example a currency on a TEXT field). */
        FIELD_RULE_NOT_FOR_TYPE,
        /** Length rules: below 1, above 512, an exact length together with a minimum or maximum, or min above max. */
        FIELD_LENGTH_INVALID,
        /** A CUSTOM_PATTERN field needs its pattern. */
        FIELD_PATTERN_REQUIRED,
        /** The pattern is not a valid regular expression or is longer than 512 characters. */
        FIELD_PATTERN_INVALID,
        /** A DROPDOWN field needs at least one value to choose from. */
        FIELD_OPTIONS_REQUIRED,
        /** A DROPDOWN field has more than 100 values. */
        FIELD_OPTIONS_TOO_MANY,
        /** A dropdown value is blank, longer than 512 characters or has control characters. */
        FIELD_OPTION_INVALID,
        /** Two dropdown values are the same once normalised. */
        FIELD_OPTION_REPEATED,
        /** The lowest or highest value: not a number of the type, or the lowest above the highest. */
        FIELD_RANGE_INVALID,
        /** A sensitive field is searchable by exact match only. */
        FIELD_SENSITIVE_EXACT_ONLY,
        /** A field that is not searchable keeps the search mode EXACT. */
        FIELD_SEARCH_MODE_INVALID,
        /** A primary identifier takes one value, not several. */
        FIELD_PRIMARY_MULTI_VALUE,
        /** A switched-off field cannot be a primary identifier. */
        FIELD_PRIMARY_INACTIVE,
        /** The display order is below 0 or above 10000. */
        FIELD_DISPLAY_ORDER_INVALID,
        /** The person or process making the change is not named (1–200 characters, no control characters). */
        FIELD_ACTOR_INVALID,
        /** An app setting's name or value is not valid. */
        FIELD_EXTENSION_INVALID,
        /** Another field of this entity and product already has this key (keys are compared ignoring capitals). */
        FIELD_KEY_TAKEN,
        /** No field with this key in this entity and product. */
        FIELD_UNKNOWN,
        /** A field's key never changes. */
        FIELD_KEY_LOCKED,
        /** Values exist for the field, so its type (and, for MONEY, its currency) cannot change: add a new field. */
        FIELD_TYPE_LOCKED,
        /** Values exist for the field, so it cannot become sensitive or stop being sensitive: add a new field. */
        FIELD_SENSITIVE_LOCKED,
        /** Values exist for the field, so it cannot become a primary identifier or stop being one: add a new field. */
        FIELD_PRIMARY_LOCKED,
        /** Someone else changed the field since it was read: read it again. */
        FIELD_CHANGED_MEANWHILE
    }

    private final Code code;

    public FieldDefinitionException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }

    /** {@code FIELD_KEY_TAKEN}, as every store says it. */
    public static FieldDefinitionException keyTaken(FieldKey key) {
        return new FieldDefinitionException(Code.FIELD_KEY_TAKEN, "A field with the key " + key
                + " already exists in this entity (keys are compared ignoring capitals)");
    }

    /** {@code FIELD_UNKNOWN}, as every store says it. */
    public static FieldDefinitionException unknown(FieldKey key) {
        return new FieldDefinitionException(Code.FIELD_UNKNOWN, "No field with the key " + key + " in this entity");
    }

    /** {@code FIELD_CHANGED_MEANWHILE}, as every store says it. */
    public static FieldDefinitionException changedMeanwhile(FieldKey key) {
        return new FieldDefinitionException(Code.FIELD_CHANGED_MEANWHILE,
                "Field " + key + " was changed by someone else since it was read: read it again");
    }
}
