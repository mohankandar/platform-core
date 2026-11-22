package com.documentplatform.crypto;

import java.util.Objects;

/**
 * A crypto or key service call failed; {@link #code()} says why. The message never holds a key, a token, a secret, a
 * value or ciphertext: only what was being done, ids (customer, key name and version) and neutral reasons. No cause
 * from the key service's client is attached, so nothing it carried can leak through a stack trace.
 */
public final class CryptoException extends RuntimeException {

    private final CryptoError code;

    public CryptoException(CryptoError code, String message) {
        super(Objects.requireNonNull(code, "code") + ": " + message, null, false, true);
        this.code = code;
    }

    public CryptoError code() {
        return code;
    }

    /** The message without the code in front. */
    public String reason() {
        return getMessage().substring(code.name().length() + 2);
    }
}
