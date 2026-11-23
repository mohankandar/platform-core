package com.documentplatform.modelgov.jdbc;

/**
 * A read or write of the model-governance tables failed (P037, xc-catalogue-19). The exception names the operation and the SQL
 * state, never a value; it implements {@code HasPlatformCode}.
 */
public enum ModelGovStoreCode implements com.documentplatform.errors.PlatformCode {
    /** A read or write of the model-governance tables failed; the message names the operation and the SQL state. */
    MG_STORE_FAILED
}
