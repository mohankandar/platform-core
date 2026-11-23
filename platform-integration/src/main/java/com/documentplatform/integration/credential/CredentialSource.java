package com.documentplatform.integration.credential;

/** Where a client certificate and its key are kept. */
public enum CredentialSource {
    /** Uploaded (PKCS#12 or PEM) and stored sealed under the customer's keys ({@code int_credential}). */
    UPLOADED,
    /** In a keystore the customer manages: a file path, its password by secret reference, an alias. Nothing copied. */
    KEYSTORE
}
