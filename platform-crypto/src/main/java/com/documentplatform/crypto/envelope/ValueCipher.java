package com.documentplatform.crypto.envelope;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.KeyService;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Envelope encryption of one small value (a sensitive case field value, a secret the app keeps): AES-256-GCM with a
 * new data key per value, the data key wrapped by the customer's wrapping key in the key service. The result is one
 * self-contained text, {@code pcv1.<base64url>}, to store in one column:
 *
 * <pre>
 * version (1) | customer UUID (16) | key service id (1 + n) | wrapped data key (2 + n) | nonce (12) | ciphertext + tag
 * </pre>
 *
 * The customer, the key service, the format and the caller's {@code context} (for example
 * {@code case:7f3e…:field:borrower_name}) are bound to the ciphertext as associated data, so a value cannot be moved
 * to another customer, record or field. The wrapped data key is not part of the associated data, so
 * {@link #rewrap} can move a value to the latest key version without touching its ciphertext.
 *
 * <p>Each {@link #seal} and {@link #open} is one call to the key service. Thread-safe; logs nothing.
 */
public final class ValueCipher {

    public static final String PREFIX = "pcv1.";
    /** The largest value, in bytes. Larger data goes through {@link StreamCipher}. */
    public static final int MAX_VALUE_BYTES = 64 * 1024;

    private static final byte VERSION = 1;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final Pattern CONTEXT = Pattern.compile("[A-Za-z0-9_.:/@-]{1,200}");
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final KeyService keys;
    private final SecureRandom random;

    public ValueCipher(KeyService keys) {
        this(keys, new SecureRandom());
    }

    ValueCipher(KeyService keys, SecureRandom random) {
        this.keys = Objects.requireNonNull(keys, "keys");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Encrypts {@code value} for the customer, bound to {@code context}. */
    public String seal(CustomerId customer, String context, byte[] value) {
        Objects.requireNonNull(customer, "customer");
        Objects.requireNonNull(value, "value");
        checkContext(context);
        if (value.length > MAX_VALUE_BYTES) {
            throw new IllegalArgumentException("A sealed value has at most " + MAX_VALUE_BYTES + " bytes");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try (KeyService.DataKey dk = keys.newDataKey(customer)) {
            byte[] ciphertext = gcm(Cipher.ENCRYPT_MODE, dk.key(), nonce, aad(customer, keys.id(), context), value);
            return PREFIX + B64.encodeToString(encode(new Parts(customer.value(), keys.id(), dk.wrapped(), nonce,
                    ciphertext)));
        }
    }

    /** {@link #seal} of UTF-8 text. */
    public String seal(CustomerId customer, String context, String value) {
        return seal(customer, context, Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decrypts a sealed value of the customer, made with the same {@code context}. Refused with
     * {@link CryptoError#CRYPTO_WRONG_CUSTOMER} (before any key is used) if it belongs to another customer,
     * {@link CryptoError#CRYPTO_CIPHERTEXT_INVALID} if it or its context was changed.
     */
    public byte[] open(CustomerId customer, String context, String sealed) {
        Objects.requireNonNull(customer, "customer");
        checkContext(context);
        Parts p = decode(sealed);
        check(customer, p);
        byte[] key = keys.unwrap(customer, p.wrapped());
        try {
            return gcm(Cipher.DECRYPT_MODE, key, p.nonce(), aad(customer, p.keyService(), context), p.ciphertext());
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /** {@link #open} as UTF-8 text. */
    public String openText(CustomerId customer, String context, String sealed) {
        byte[] clear = open(customer, context, sealed);
        try {
            return new String(clear, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(clear, (byte) 0);
        }
    }

    /**
     * The same value with its data key wrapped by the latest version of the customer's wrapping key. The ciphertext
     * is unchanged and the data key never leaves the key service. Use it to move data off a key version before that
     * version is switched off.
     */
    public String rewrap(CustomerId customer, String sealed) {
        Parts p = decode(sealed);
        check(customer, p);
        String again = keys.rewrap(customer, p.wrapped());
        return PREFIX + B64.encodeToString(encode(new Parts(p.customer(), p.keyService(), again, p.nonce(),
                p.ciphertext())));
    }

    /** The version of the wrapping key a sealed value's data key is wrapped with. Calls nothing. */
    public int keyVersion(String sealed) {
        return keys.versionOf(decode(sealed).wrapped());
    }

    /** The customer a sealed value belongs to, read from it. Calls nothing. */
    public static CustomerId customerOf(String sealed) {
        return CustomerId.of(decode(sealed).customer());
    }

    // ---- inside ----

    private record Parts(UUID customer, String keyService, String wrapped, byte[] nonce, byte[] ciphertext) {
    }

    private void check(CustomerId customer, Parts p) {
        if (!p.customer().equals(customer.value())) {
            throw new CryptoException(CryptoError.CRYPTO_WRONG_CUSTOMER, "the sealed value belongs to customer "
                    + p.customer() + ", not " + customer);
        }
        if (!p.keyService().equals(keys.id())) {
            throw new CryptoException(CryptoError.CRYPTO_KEY_SERVICE_MISMATCH, "the sealed value was made with key"
                    + " service " + safe(p.keyService()) + "; this one is " + keys.id());
        }
    }

    static byte[] aad(CustomerId customer, String keyService, String context) {
        return ("platform-crypto/value/1|customer=" + customer + "|keys=" + keyService + "|context=" + context)
                .getBytes(StandardCharsets.UTF_8);
    }

    static byte[] gcm(int mode, byte[] key, byte[] nonce, byte[] aad, byte[] input) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            c.updateAAD(aad);
            return c.doFinal(input);
        } catch (AEADBadTagException e) {
            throw new CryptoException(CryptoError.CRYPTO_CIPHERTEXT_INVALID,
                    "authentication failed: the ciphertext or its context was changed");
        } catch (GeneralSecurityException e) {
            throw new CryptoException(CryptoError.CRYPTO_CIPHERTEXT_INVALID, "AES-GCM failed ("
                    + e.getClass().getSimpleName() + ")");
        }
    }

    private static void checkContext(String context) {
        if (context == null || !CONTEXT.matcher(context).matches()) {
            throw new IllegalArgumentException("A context is 1 to 200 of A-Z a-z 0-9 _ . : / @ -");
        }
    }

    private static byte[] encode(Parts p) {
        byte[] ks = p.keyService().getBytes(StandardCharsets.US_ASCII);
        byte[] w = p.wrapped().getBytes(StandardCharsets.US_ASCII);
        ByteBuffer b = ByteBuffer.allocate(1 + 16 + 1 + ks.length + 2 + w.length + NONCE_BYTES
                + p.ciphertext().length);
        b.put(VERSION);
        b.putLong(p.customer().getMostSignificantBits()).putLong(p.customer().getLeastSignificantBits());
        b.put((byte) ks.length).put(ks);
        b.putShort((short) w.length).put(w);
        b.put(p.nonce()).put(p.ciphertext());
        return b.array();
    }

    private static Parts decode(String sealed) {
        if (sealed == null || !sealed.startsWith(PREFIX)) {
            throw new CryptoException(CryptoError.CRYPTO_FORMAT_UNKNOWN, "not a sealed value (" + PREFIX + "...)");
        }
        byte[] all;
        try {
            all = B64D.decode(sealed.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        try {
            ByteBuffer b = ByteBuffer.wrap(all);
            if (b.get() != VERSION) {
                throw new CryptoException(CryptoError.CRYPTO_FORMAT_UNKNOWN, "a sealed value of an unknown version");
            }
            UUID customer = new UUID(b.getLong(), b.getLong());
            byte[] ks = new byte[b.get() & 0xff];
            b.get(ks);
            byte[] w = new byte[b.getShort() & 0xffff];
            b.get(w);
            byte[] nonce = new byte[NONCE_BYTES];
            b.get(nonce);
            if (b.remaining() < TAG_BITS / 8) {
                throw invalid();
            }
            byte[] ciphertext = new byte[b.remaining()];
            b.get(ciphertext);
            return new Parts(customer, new String(ks, StandardCharsets.US_ASCII), new String(w,
                    StandardCharsets.US_ASCII), nonce, ciphertext);
        } catch (java.nio.BufferUnderflowException e) {
            throw invalid();
        }
    }

    private static CryptoException invalid() {
        return new CryptoException(CryptoError.CRYPTO_CIPHERTEXT_INVALID, "the sealed value is cut short or damaged");
    }

    static String safe(String v) {
        String s = v.replaceAll("[^A-Za-z0-9._-]", "?");
        return s.length() > 40 ? s.substring(0, 40) + "..." : s;
    }
}
