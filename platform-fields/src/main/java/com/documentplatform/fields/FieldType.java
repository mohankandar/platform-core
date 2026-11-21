package com.documentplatform.fields;

/** What kind of value a field holds. A field's type never changes once values exist. */
public enum FieldType {
    /** Any single line of text. May have length rules and a pattern. */
    TEXT,
    /** A whole number: digits with an optional leading minus. Leading zeros are kept (account numbers). */
    NUMBER,
    /** An amount in the field's one currency (for example USD), with at most the currency's decimals. */
    MONEY,
    /** A calendar date: {@code 2026-11-21} or, US style, {@code 11/21/2026}. Normalised to {@code 2026-11-21}. */
    DATE,
    /** An e-mail address. */
    EMAIL,
    /** A UUID in its usual 36-character form. Normalised to lower case. */
    UUID,
    /** One of up to 100 values the admin lists. */
    DROPDOWN,
    /** A postal address in parts: street, city, state, ZIP, and a country (US only for now). */
    ADDRESS,
    /** Text that must match the field's own pattern. */
    CUSTOM_PATTERN;

    /** Types whose value is one line of text (all but ADDRESS). */
    public boolean isText() {
        return this != ADDRESS;
    }

    /** Types that take length rules: TEXT, NUMBER, EMAIL, CUSTOM_PATTERN. */
    public boolean takesLength() {
        return this == TEXT || this == NUMBER || this == EMAIL || this == CUSTOM_PATTERN;
    }

    /** Types that take a pattern: TEXT, NUMBER, EMAIL (optional) and CUSTOM_PATTERN (required). */
    public boolean takesPattern() {
        return takesLength();
    }

    /** Types whose value the admin's normalisation applies to: TEXT, NUMBER, EMAIL, DROPDOWN, CUSTOM_PATTERN. */
    public boolean takesNormalisation() {
        return takesLength() || this == DROPDOWN;
    }

    /** Types with a lowest and highest value: NUMBER and MONEY. */
    public boolean takesRange() {
        return this == NUMBER || this == MONEY;
    }
}
