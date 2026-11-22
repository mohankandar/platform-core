package com.documentplatform.crypto;

/**
 * Why an encryption, decryption, fingerprint or key service call failed. Stable codes; the message with each names
 * what was being done and the key or setting involved, never a key, a token, a secret or a value.
 */
public enum CryptoError {
    /** A setting is missing or not valid (address, mount, key prefix, login). The message names the setting. */
    CRYPTO_SETTINGS_INVALID,
    /** The key service does not answer: connection refused, no route, timeout. */
    CRYPTO_KEY_SERVICE_UNREACHABLE,
    /** The TLS handshake with the key service failed (usually its CA is not in the Java trust store). */
    CRYPTO_KEY_SERVICE_TLS,
    /** The key service is sealed or not ready (Vault: HTTP 503). */
    CRYPTO_KEY_SERVICE_SEALED,
    /** The login to the key service was refused (wrong AppRole role or secret ID, empty token file). */
    CRYPTO_LOGIN_REFUSED,
    /** The key service refused the call: the token or its policy does not allow it (Vault: HTTP 401/403). */
    CRYPTO_PERMISSION_DENIED,
    /** The customer's key does not exist in the key service (not created by the set-up step, or deleted). */
    CRYPTO_KEY_MISSING,
    /**
     * The key, or the key version the data was protected with, is switched off (Vault: below the key's
     * {@code min_decryption_version}; KMS / Key Vault: a disabled key). Rewrap the data while the version is usable.
     */
    CRYPTO_KEY_DISABLED,
    /** The key service answered with something else that is not usable. */
    CRYPTO_KEY_SERVICE_ERROR,
    /** A secret asked for does not exist (Vault KV: no such path). */
    CRYPTO_SECRET_MISSING,
    /** The data belongs to another customer than the one asked for. Refused before any key is used. */
    CRYPTO_WRONG_CUSTOMER,
    /** The data was protected by another key service than the one configured. */
    CRYPTO_KEY_SERVICE_MISMATCH,
    /** The ciphertext was changed, cut short, or does not belong with its context: authentication failed. */
    CRYPTO_CIPHERTEXT_INVALID,
    /** Not data of a format this library knows (wrong prefix, unknown version). */
    CRYPTO_FORMAT_UNKNOWN
}
