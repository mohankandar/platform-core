package com.documentplatform.licensing.issue;

import com.documentplatform.licensing.Ed25519;

import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Ed25519 private-key handling: for tests and for key ceremonies inside the secret store, never for an app (P041:
 * moved out of the customer's {@code Ed25519}, which only verifies). Never write a private key to a repository.
 */
public final class SigningKeys {

    private SigningKeys() {
    }

    /** A new key pair. */
    public static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance(Ed25519.ALGORITHM).generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("this JDK has no Ed25519", e);
        }
    }

    public static byte[] sign(PrivateKey key, byte[] message) throws InvalidKeyException {
        try {
            Signature s = Signature.getInstance(Ed25519.ALGORITHM);
            s.initSign(key);
            s.update(message);
            return s.sign();
        } catch (InvalidKeyException e) {
            throw e;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 signing failed", e);
        }
    }

    /** @throws IllegalArgumentException if the text is not a Base64 PKCS#8 Ed25519 private key (text not repeated) */
    public static PrivateKey decodePrivate(String base64) {
        try {
            return KeyFactory.getInstance(Ed25519.ALGORITHM)
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64.strip())));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new IllegalArgumentException("not a Base64 PKCS#8 Ed25519 private key");
        }
    }

    public static String encodePrivate(PrivateKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }
}
