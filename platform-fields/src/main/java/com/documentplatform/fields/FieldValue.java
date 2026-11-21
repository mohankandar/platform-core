package com.documentplatform.fields;

import java.util.Objects;

/**
 * One value of a case field: a line of text, or the parts of an address. Values may be sensitive, so
 * {@link #toString()} never shows them; read the parts directly.
 */
public sealed interface FieldValue permits FieldValue.Text, FieldValue.Address {

    static Text text(String value) {
        return new Text(value);
    }

    static Address address(String street, String city, String state, String zip) {
        return new Address(street, city, state, zip, null);
    }

    /** A value of every type but ADDRESS. */
    record Text(String value) implements FieldValue {
        public Text {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String toString() {
            return "FieldValue.Text[" + value.length() + " characters]";
        }
    }

    /**
     * An ADDRESS value. US format for now: street, city, state (two letters), ZIP ({@code 12345} or
     * {@code 12345-6789}). The country is optional and means US when absent; other countries come later.
     */
    record Address(String street, String city, String state, String zip, String country) implements FieldValue {

        /** The only country checked for now. */
        public static final String US = "US";

        /** All parts together, for the length limit. */
        int length() {
            int n = 0;
            for (String p : new String[]{street, city, state, zip, country}) {
                n += p == null ? 0 : p.length();
            }
            return n;
        }

        @Override
        public String toString() {
            return "FieldValue.Address[hidden]";
        }
    }
}
