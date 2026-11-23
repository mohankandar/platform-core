package com.documentplatform.fields;

/**
 * Why a value was refused. Stable codes; each comes with a plain message that names the field's label and the rule,
 * never the value.
 */
public enum ValueError implements com.documentplatform.errors.PlatformCode {
    /** The field is required and no value was given. */
    VALUE_REQUIRED,
    /** The value is blank. */
    VALUE_EMPTY,
    /** Longer than 512 characters (an address: all parts together). */
    VALUE_TOO_LONG,
    /** Has control characters such as line breaks. */
    VALUE_CONTROL_CHARACTERS,
    /** NUMBER: not a whole number. */
    VALUE_NOT_A_NUMBER,
    /** DECIMAL: not a number. */
    VALUE_NOT_A_DECIMAL,
    /** MONEY: not an amount. */
    VALUE_NOT_MONEY,
    /** MONEY: more decimals than the currency has. */
    VALUE_TOO_MANY_DECIMALS,
    /** NUMBER, DECIMAL, MONEY: below the lowest value. */
    VALUE_BELOW_MINIMUM,
    /** NUMBER, DECIMAL, MONEY: above the highest value. */
    VALUE_ABOVE_MAXIMUM,
    /** DATE: not a calendar date. */
    VALUE_NOT_A_DATE,
    /** DATE_TIME: not a date and time with its offset from UTC. */
    VALUE_NOT_A_DATE_TIME,
    /** EMAIL: not an e-mail address. */
    VALUE_NOT_AN_EMAIL,
    /** UUID: not a UUID. */
    VALUE_NOT_A_UUID,
    /** DROPDOWN: not one of the listed values. */
    VALUE_NOT_AN_OPTION,
    /** Does not match the field's pattern. */
    VALUE_PATTERN_MISMATCH,
    /** Not exactly the required number of characters. */
    VALUE_LENGTH_NOT_EXACT,
    /** Shorter than the minimum length. */
    VALUE_TOO_SHORT,
    /** Longer than the field's maximum length. */
    VALUE_ABOVE_MAX_LENGTH,
    /** ADDRESS: the street is missing. */
    ADDRESS_STREET_MISSING,
    /** ADDRESS: the city is missing. */
    ADDRESS_CITY_MISSING,
    /** ADDRESS: the state is not a two-letter US state or territory code. */
    ADDRESS_STATE_INVALID,
    /** ADDRESS: the ZIP is not 5 digits or ZIP+4. */
    ADDRESS_ZIP_INVALID,
    /** ADDRESS: only US addresses for now. */
    ADDRESS_COUNTRY_NOT_SUPPORTED,
    /** An address was given for a field that is not ADDRESS, or text for an ADDRESS field. */
    VALUE_WRONG_SHAPE,
    /** Several values for a field that takes one. */
    VALUE_NOT_MULTI,
    /** More than 50 values. */
    VALUE_TOO_MANY,
    /** The same value twice (once normalised). */
    VALUE_REPEATED,
    /** A value for a key the entity has no field for. */
    FIELD_UNKNOWN,
    /** A value for a field that is switched off. */
    FIELD_INACTIVE
}
