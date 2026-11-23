package com.documentplatform.licensing.jdbc;

/**
 * A read or write of the licensing tables failed (P037, xc-catalogue-19). The exception names the operation and the SQL
 * state, never a value; it implements {@code HasPlatformCode}.
 */
public enum LicensingStoreCode implements com.documentplatform.errors.PlatformCode {
    /** A read or write of the licensing tables failed; the message names the operation and the SQL state. */
    LIC_STORE_FAILED
}
