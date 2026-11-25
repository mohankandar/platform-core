package com.documentplatform.crypto;

import java.util.Arrays;
import java.util.Objects;

/**
 * One item's result of {@link KeyService#unwrapAll}: the data key, or why this item did not open. {@link #close()}
 * wipes the key; {@link #closeAll} does so for a whole list. {@code toString()} shows neither the key nor the wrapped
 * value.
 */
public final class Unwrapped implements AutoCloseable {

    private final byte[] key;
    private final CryptoException failure;
    private volatile boolean closed;

    private Unwrapped(byte[] key, CryptoException failure) {
        this.key = key;
        this.failure = failure;
    }

    /** An item that opened. The array itself is kept (not a copy), and wiped by {@link #close()}. */
    public static Unwrapped opened(byte[] key) {
        Objects.requireNonNull(key, "key");
        if (key.length != 32) {
            Arrays.fill(key, (byte) 0);
            throw new CryptoException(CryptoError.CRYPTO_CIPHERTEXT_INVALID, "the wrapped data key is not a 256-bit "
                    + "key");
        }
        return new Unwrapped(key, null);
    }

    /**
     * An item that did not open, with the exception the single {@link KeyService#unwrap} throws for it. Only for the
     * codes that are about one item ({@link #isItemError}); any other code is about the whole call.
     */
    public static Unwrapped failed(CryptoException failure) {
        if (!isItemError(Objects.requireNonNull(failure, "failure").code())) {
            throw new IllegalArgumentException(failure.code() + " is not about one item");
        }
        return new Unwrapped(null, failure);
    }

    /** Whether the code is about one item (the rest of a batch still opens) and not about the whole call. */
    public static boolean isItemError(CryptoError code) {
        return code == CryptoError.CRYPTO_CIPHERTEXT_INVALID || code == CryptoError.CRYPTO_KEY_DISABLED;
    }

    /** Wipes every key of a batch. */
    public static void closeAll(Iterable<Unwrapped> results) {
        for (Unwrapped u : results) {
            u.close();
        }
    }

    public boolean ok() {
        return failure == null;
    }

    /** Why the item did not open, or null when it did. */
    public CryptoError error() {
        return failure == null ? null : failure.code();
    }

    /** The exception the single unwrap throws for this item, or null when it opened. */
    public CryptoException failure() {
        return failure;
    }

    /**
     * The data key itself (32 bytes, not a copy): use it, then {@link #close()}. Throws the item's failure if it did
     * not open.
     */
    public byte[] key() {
        if (failure != null) {
            throw failure;
        }
        if (closed) {
            throw new IllegalStateException("The data key is closed");
        }
        return key;
    }

    @Override
    public void close() {
        closed = true;
        if (key != null) {
            Arrays.fill(key, (byte) 0);
        }
    }

    @Override
    public String toString() {
        return failure == null ? "Unwrapped[ok]" : "Unwrapped[" + failure.code() + "]";
    }
}
