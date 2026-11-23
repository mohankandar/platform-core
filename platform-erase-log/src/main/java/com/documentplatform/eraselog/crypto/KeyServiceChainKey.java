package com.documentplatform.eraselog.crypto;

import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.KeyService;
import com.documentplatform.eraselog.ChainKey;
import com.documentplatform.eraselog.EraseLogError;
import com.documentplatform.eraselog.EraseLogException;

import java.util.Objects;

/**
 * The erase log's key in the platform's key service (P044, SEC-3): HMAC-SHA256 with the customer's fingerprint key,
 * which never leaves the key service. Every erase-log MAC input starts with its own domain ({@code platform-erase-log/
 * mac/1} or {@code platform-erase-log/seal/1}, then NUL), and every field fingerprint's with {@code platform-crypto/fp/1}
 * NUL, so a fingerprint is never a valid erase-log MAC and the other way round. Needs platform-crypto on the classpath
 * and the runtime policy's {@code transit/hmac} right, which the app already has for fingerprints.
 */
public final class KeyServiceChainKey implements ChainKey {

    private final KeyService keys;
    private final CustomerId customer;

    public KeyServiceChainKey(KeyService keys, CustomerId customer) {
        this.keys = Objects.requireNonNull(keys, "keys");
        this.customer = Objects.requireNonNull(customer, "customer");
    }

    @Override
    public int version() {
        try {
            return keys.fingerprintKey(customer).latest();
        } catch (CryptoException e) {
            throw unavailable(e);
        }
    }

    @Override
    public byte[] mac(int version, byte[] input) {
        try {
            byte[] mac = keys.fingerprint(customer, version, input);
            if (mac == null || mac.length != 32) {
                throw new EraseLogException(EraseLogError.ERASE_LOG_KEY_UNAVAILABLE, "the key service gave no "
                        + "32-byte MAC for the erase log");
            }
            return mac;
        } catch (CryptoException e) {
            throw unavailable(e);
        }
    }

    private static EraseLogException unavailable(CryptoException e) {
        return new EraseLogException(EraseLogError.ERASE_LOG_KEY_UNAVAILABLE, "the erase log's key could not be used ("
                + e.code() + ")");
    }

    @Override
    public String toString() {
        return "KeyServiceChainKey[" + keys.id() + "]";
    }
}
