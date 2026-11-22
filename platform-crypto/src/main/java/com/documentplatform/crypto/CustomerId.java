package com.documentplatform.crypto;

import java.util.Objects;
import java.util.UUID;

/**
 * The customer whose keys protect the data: the customer's tenant ID (the same UUID as in the licence and the
 * identity tables). Every customer has its own keys, also when several customers share one hosted installation.
 */
public record CustomerId(UUID value) {

    public CustomerId {
        Objects.requireNonNull(value, "value");
    }

    public static CustomerId of(UUID value) {
        return new CustomerId(value);
    }

    public static CustomerId of(String value) {
        return new CustomerId(UUID.fromString(value));
    }

    /** The 32 lower-case hex digits, as used in key names. */
    public String hex() {
        return value.toString().replace("-", "");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
