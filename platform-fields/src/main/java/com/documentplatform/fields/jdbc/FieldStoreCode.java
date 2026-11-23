package com.documentplatform.fields.jdbc;

/**
 * A read or write of the case field tables failed (P037, xc-catalogue-19). The exception names the operation and the SQL
 * state, never a value; it implements {@code HasPlatformCode}.
 */
public enum FieldStoreCode implements com.documentplatform.errors.PlatformCode {
    /** A read or write of the case field tables failed; the message names the operation and the SQL state. */
    FLD_STORE_FAILED
}
