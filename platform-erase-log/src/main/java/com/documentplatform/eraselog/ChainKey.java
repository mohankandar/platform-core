package com.documentplatform.eraselog;

/**
 * The key that makes the erase log's chain a keyed one (P044, SEC-3): every entry written with it carries an
 * HMAC-SHA256 of its hash under this key, and the log's seal ({@link Seal}) is made with it. Someone who can write the
 * log's location but has no use of the key cannot add an entry the system would act on. The key never leaves the key
 * service; only MACs come back.
 *
 * <p>Built: {@code crypto.KeyServiceChainKey} (the customer's fingerprint key in the platform's key service, used
 * with the erase log's own domain prefix, so no other MAC made with that key is ever a valid erase-log MAC).
 * Tests: any implementation with a fixed secret.
 */
public interface ChainKey {

    /** The key version new MACs are made with. */
    int version();

    /**
     * HMAC-SHA256 of {@code input} with the given version of the key: 32 bytes.
     *
     * @throws EraseLogException {@link EraseLogError#ERASE_LOG_KEY_UNAVAILABLE} when the key service cannot answer
     */
    byte[] mac(int version, byte[] input);
}
