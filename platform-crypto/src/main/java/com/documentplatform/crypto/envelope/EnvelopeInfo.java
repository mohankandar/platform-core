package com.documentplatform.crypto.envelope;

import com.documentplatform.crypto.CustomerId;

import java.util.Objects;

/**
 * What the app stores next to an encrypted stream (a document): everything needed to decrypt it except the bytes.
 * Holds the wrapped data key, never the data key itself. {@code toString()} leaves the wrapped key out.
 *
 * @param format             {@value StreamCipher#FORMAT}
 * @param formatVersion      {@value StreamCipher#VERSION}
 * @param keyService         the key service's {@code id()}, for example {@code vault-transit}
 * @param customer           whose keys protect it
 * @param wrappedKey         the data key wrapped by the customer's wrapping key
 * @param keyVersion         the version of the wrapping key {@code wrappedKey} is wrapped with
 * @param encryptedBytes     the size of the encrypted stream
 * @param ciphertextSha256   SHA-256 of the encrypted stream, lower-case hex (to check storage, not a secret)
 */
public record EnvelopeInfo(String format, int formatVersion, String keyService, CustomerId customer,
                           String wrappedKey, int keyVersion, long encryptedBytes, String ciphertextSha256) {

    public EnvelopeInfo {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(keyService, "keyService");
        Objects.requireNonNull(customer, "customer");
        Objects.requireNonNull(wrappedKey, "wrappedKey");
    }

    /** The same envelope with its data key wrapped again (see {@link StreamCipher#rewrap}). */
    public EnvelopeInfo withWrappedKey(String wrapped, int version) {
        return new EnvelopeInfo(format, formatVersion, keyService, customer, wrapped, version, encryptedBytes,
                ciphertextSha256);
    }

    @Override
    public String toString() {
        return "EnvelopeInfo[" + format + "/" + formatVersion + ", " + keyService + ", customer " + customer
                + ", key version " + keyVersion + ", " + encryptedBytes + " bytes]";
    }
}
