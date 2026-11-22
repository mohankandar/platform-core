package com.documentplatform.crypto;

import java.util.Arrays;
import java.util.Objects;

/**
 * The plug-in point for the service that holds the customers' keys. Each customer has two keys of its own in it:
 * a <b>wrapping key</b> that protects data keys, and a <b>fingerprint key</b> for keyed fingerprints. Neither ever
 * leaves the key service: data keys are wrapped and unwrapped there, fingerprints are computed there.
 *
 * <p>Implementations: {@code vault.VaultKeyService} (HashiCorp Vault Transit). Later: AWS KMS and Azure Key Vault
 * (see {@code docs/design/crypto.md} for how their calls map). Tests: the in-memory key service of
 * {@code platform-crypto-testkit}.
 *
 * <p>Every method throws {@link CryptoException} with a specific {@link CryptoError}; nothing it throws or logs holds
 * a key, a token or a value. Implementations are thread-safe.
 */
public interface KeyService {

    /** A short, stable name of the kind of key service, written into every envelope (for example "vault-transit"). */
    String id();

    /**
     * Creates the customer's wrapping key and fingerprint key if they do not exist yet; does nothing if they do.
     * This is the set-up step's job: at runtime the app's credentials normally may not create keys.
     */
    void createKeys(CustomerId customer);

    /** The versions of the customer's wrapping key. {@link CryptoError#CRYPTO_KEY_MISSING} if it does not exist. */
    KeyVersions wrappingKey(CustomerId customer);

    /** The versions of the customer's fingerprint key. */
    KeyVersions fingerprintKey(CustomerId customer);

    /** A new random 256-bit data key, and the same key wrapped by the latest version of the wrapping key. */
    DataKey newDataKey(CustomerId customer);

    /**
     * The data key inside {@code wrapped}. Works for every version of the wrapping key that is still usable;
     * {@link CryptoError#CRYPTO_KEY_DISABLED} for a version below the lowest usable one,
     * {@link CryptoError#CRYPTO_CIPHERTEXT_INVALID} if it was changed or wrapped for another customer. The caller wipes
     * the array after use.
     */
    byte[] unwrap(CustomerId customer, String wrapped);

    /** {@code wrapped} wrapped again by the latest version of the wrapping key, without the data key leaving. */
    String rewrap(CustomerId customer, String wrapped);

    /** The version of the wrapping key {@code wrapped} was made with. Reads only the reference, calls nothing. */
    int versionOf(String wrapped);

    /** Adds a new version to the customer's wrapping key; older versions still unwrap. Returns the new version. */
    int rotate(CustomerId customer);

    /** HMAC-SHA256 of {@code input} with the given version of the customer's fingerprint key (32 bytes). */
    byte[] fingerprint(CustomerId customer, int version, byte[] input);

    /**
     * HMAC-SHA256 of each input with the given version of the customer's fingerprint key, in the order given, in as
     * few calls as the key service allows (Vault Transit: one call with a batch). The default makes one call per
     * input.
     */
    default java.util.List<byte[]> fingerprints(CustomerId customer, int version, java.util.List<byte[]> inputs) {
        java.util.List<byte[]> out = new java.util.ArrayList<>(inputs.size());
        for (byte[] input : inputs) {
            out.add(fingerprint(customer, version, input));
        }
        return out;
    }

    /** Whether the key service answers and can serve calls. Never throws. */
    Health health();

    /**
     * The versions of one key: new data is protected with {@code latest}; versions from {@code lowestUsable} to
     * {@code latest} still unwrap (or, for a fingerprint key, still compute).
     */
    record KeyVersions(int latest, int lowestUsable) {
        public KeyVersions {
            if (latest < 1 || lowestUsable < 1 || lowestUsable > latest) {
                throw new IllegalArgumentException("Key versions: latest " + latest + ", lowest usable "
                        + lowestUsable);
            }
        }
    }

    /**
     * A data key: the plaintext key (32 bytes) for one encryption, and the same key wrapped by version
     * {@code version} of the customer's wrapping key, to store with the data. {@link #close()} wipes the plaintext.
     * {@code toString()} shows neither.
     */
    final class DataKey implements AutoCloseable {
        private final byte[] key;
        private final String wrapped;
        private final int version;

        public DataKey(byte[] key, String wrapped, int version) {
            Objects.requireNonNull(key, "key");
            if (key.length != 32) {
                throw new CryptoException(CryptoError.CRYPTO_KEY_SERVICE_ERROR,
                        "the key service gave a data key that is not 256 bits");
            }
            this.key = key;
            this.wrapped = Objects.requireNonNull(wrapped, "wrapped");
            this.version = version;
        }

        /** The plaintext key itself (not a copy): use it, then {@link #close()}. */
        public byte[] key() {
            return key;
        }

        public String wrapped() {
            return wrapped;
        }

        public int version() {
            return version;
        }

        @Override
        public void close() {
            Arrays.fill(key, (byte) 0);
        }

        @Override
        public String toString() {
            return "DataKey[version " + version + "]";
        }
    }

    /** The key service's state for a health check. {@code code} is null when it is {@code UP}. */
    record Health(Status status, String keyService, CryptoError code, String message) {
        public enum Status { UP, DOWN }

        public boolean up() {
            return status == Status.UP;
        }
    }
}
