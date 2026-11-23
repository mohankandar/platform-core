package com.documentplatform.identity.jdbc;

/**
 * A read or write of the identity tables failed (P037, xc-catalogue-19). The exception names the operation and the SQL
 * state, never a value; it implements {@code HasPlatformCode}.
 */
public enum IdentityStoreCode implements com.documentplatform.errors.PlatformCode {
    /** A read or write of the identity tables failed; the message names the operation and the SQL state. */
    IDN_STORE_FAILED
}
