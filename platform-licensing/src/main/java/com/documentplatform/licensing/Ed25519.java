package com.documentplatform.licensing;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Ed25519 and SHA-256 with the JDK's own providers (no extra dependency). Public keys are exchanged as Base64 of their
 * X.509 (SubjectPublicKeyInfo) encoding. Signatures are 64 bytes.
 */
public final class Ed25519 {

    public static final String ALGORITHM = "Ed25519";
    public static final int SIGNATURE_LENGTH = 64;

    private Ed25519() {
    }

    /** False for a signature that does not verify, including one of the wrong shape. */
    public static boolean verify(PublicKey key, byte[] message, byte[] signature) {
        try {
            Signature s = Signature.getInstance(ALGORITHM);
            s.initVerify(key);
            s.update(message);
            return s.verify(signature);
        } catch (SignatureException e) {
            return false;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 verification failed to run", e);
        }
    }

    public static String encodePublic(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    /** @throws IllegalArgumentException if the text is not a Base64 X.509 Ed25519 public key */
    public static PublicKey decodePublic(String base64) {
        try {
            return KeyFactory.getInstance(ALGORITHM)
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new IllegalArgumentException("not a Base64 X.509 Ed25519 public key");
        }
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("this JDK has no SHA-256", e);
        }
    }

    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }
}
