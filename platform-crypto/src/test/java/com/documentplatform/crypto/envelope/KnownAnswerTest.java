package com.documentplatform.crypto.envelope;

import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.KeyService;
import com.documentplatform.crypto.fingerprint.Fingerprinter;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Known-answer tests of the stored formats (P038, crypto-23, -28, -40): fixed inputs give the fixed outputs kept here.
 * Each expected text is built a second time in this test from {@code docs/design/crypto.md} section 4 alone (layout,
 * associated data, Base64url without padding), so a change of the code's format, or of the design, fails here. The
 * fingerprint's expected value was computed outside Java with openssl:
 * {@code printf 'platform-crypto/fp/1\0field:borrower_name\0jane example' | openssl dgst -sha256 -mac HMAC
 * -macopt hexkey:000102…1f -binary}.
 */
class KnownAnswerTest {

    static final CustomerId CUSTOMER = CustomerId.of(UUID.fromString("11111111-2222-3333-4444-555555555555"));
    static final String KEYS_ID = "fixed-ks";
    static final String WRAPPED = "fixed:wrapped:1";
    static final byte[] DATA_KEY = range(0x20, 32);
    static final byte[] NONCE = range(0xa0, 12);
    static final byte[] FP_KEY = range(0x00, 32);

    /** pcv1. of "LN-100" in context case:c1:field:loan_number, with the fixed key, wrapped text and nonce. */
    static final String VALUE = "pcv1.AREREREiIjMzRERVVVVVVVUIZml4ZWQta3MAD2ZpeGVkOndyYXBwZWQ6MaChoqOkpaanqKmqqzJyiQX0570ouzfz"
            + "ZquIEI8b3CwIHi4";
    /** pck1. envelope of the fixed customer, key service and wrapped key. */
    static final String ENVELOPE = "pck1.AREREREiIjMzRERVVVVVVVUIZml4ZWQta3MAD2ZpeGVkOndyYXBwZWQ6MQ";
    /** pci1. item of "Jane Example" under record fld/r-1, item borrower_name/0, with the fixed nonce. */
    static final String ITEM = "pci1.AaChoqOkpaanqKmqqzRdylHkkvjLzFjB34D3UQ_ldfjg_6toLbPncOY";
    /** f1. HMAC-SHA256 (openssl) of the documented fingerprint input. */
    static final String FINGERPRINT = "A5YBhfTEhy-4RDp69XR9UpNzkZGO5xY-8XEArcz8e2U";

    @Test
    void aValueHasTheDesignsLayoutAndAssociatedData() {
        String context = "case:c1:field:loan_number";
        String built = "pcv1." + b64(ByteBuffer.allocate(1 + 16 + 1 + KEYS_ID.length() + 2 + WRAPPED.length() + 12
                        + 6 + 16)
                .put((byte) 1).put(uuid(CUSTOMER)).put((byte) KEYS_ID.length()).put(ascii(KEYS_ID))
                .putShort((short) WRAPPED.length()).put(ascii(WRAPPED)).put(NONCE)
                .put(gcm(DATA_KEY, NONCE, "platform-crypto/value/1|customer=" + CUSTOMER.value() + "|keys=" + KEYS_ID
                        + "|context=" + context, "LN-100")).array());
        assertEquals(VALUE, built, "the design's layout gives the kept text");
        ValueCipher code = new ValueCipher(new FixedKeys(), fixedRandom());
        assertEquals(VALUE, code.seal(CUSTOMER, context, "LN-100"), "the code writes the design's format");
        assertEquals("LN-100", code.openText(CUSTOMER, context, VALUE));
    }

    @Test
    void aRecordEnvelopeAndItsItemsHaveTheDesignsLayoutAndAssociatedData() {
        String record = "fld/r-1";
        String item = "borrower_name/0";
        String envelope = "pck1." + b64(ByteBuffer.allocate(1 + 16 + 1 + KEYS_ID.length() + 2 + WRAPPED.length())
                .put((byte) 1).put(uuid(CUSTOMER)).put((byte) KEYS_ID.length()).put(ascii(KEYS_ID))
                .putShort((short) WRAPPED.length()).put(ascii(WRAPPED)).array());
        String sealed = "pci1." + b64(ByteBuffer.allocate(1 + 12 + 12 + 16).put((byte) 1).put(NONCE)
                .put(gcm(DATA_KEY, NONCE, "platform-crypto/record/1|customer=" + CUSTOMER.value() + "|keys=" + KEYS_ID
                        + "|record=" + record + "|item=" + item, "Jane Example")).array());
        assertEquals(ENVELOPE, envelope, "the design's envelope layout gives the kept text");
        assertEquals(ITEM, sealed, "the design's item layout gives the kept text");
        RecordCipher code = new RecordCipher(new FixedKeys());
        try (RecordCipher.RecordKey k = code.newKey(CUSTOMER, record)) {
            assertEquals(ENVELOPE, k.envelope(), "the code writes the design's envelope");
        }
        try (RecordCipher.RecordKey k = code.openKey(CUSTOMER, record, ENVELOPE)) {
            assertEquals("Jane Example", k.openText(item, ITEM), "the code reads the design's item");
            assertThrows(com.documentplatform.crypto.CryptoException.class, () -> k.openText("borrower_name/1", ITEM),
                    "an item moved to another position does not open");
        }
    }

    @Test
    void aFingerprintIsTheHmacOfTheDesignsInput() {
        Fingerprinter f = new Fingerprinter(new FixedKeys());
        Fingerprinter.Fingerprint fp = f.fingerprint(CUSTOMER, "field:borrower_name", "  Jane   EXAMPLE ");
        assertEquals(1, fp.version());
        assertEquals(FINGERPRINT, fp.mac(), "the code's HMAC input is the design's (computed with openssl)");
        assertEquals("f1." + FINGERPRINT, fp.text());
    }

    // ---- fixed inputs

    /** A key service with one fixed data key, wrapped text and fingerprint key (HMAC-SHA256 in the JDK). */
    static final class FixedKeys implements KeyService {
        @Override public String id() { return KEYS_ID; }
        @Override public void createKeys(CustomerId customer) { }
        @Override public KeyVersions wrappingKey(CustomerId customer) { return new KeyVersions(1, 1); }
        @Override public KeyVersions fingerprintKey(CustomerId customer) { return new KeyVersions(1, 1); }
        @Override public DataKey newDataKey(CustomerId customer) { return new DataKey(DATA_KEY.clone(), WRAPPED, 1); }
        @Override public byte[] unwrap(CustomerId customer, String wrapped) {
            assertEquals(WRAPPED, wrapped);
            return DATA_KEY.clone();
        }
        @Override public String rewrap(CustomerId customer, String wrapped) { return wrapped; }
        @Override public int versionOf(String wrapped) { return 1; }
        @Override public int rotate(CustomerId customer) { return 1; }
        @Override public byte[] fingerprint(CustomerId customer, int version, byte[] input) {
            try {
                Mac m = Mac.getInstance("HmacSHA256");
                m.init(new SecretKeySpec(FP_KEY, "HmacSHA256"));
                return m.doFinal(input);
            } catch (java.security.GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
        @Override public Health health() { return new Health(Health.Status.UP, KEYS_ID, null, "fixed"); }
    }

    /** Gives the fixed nonce. */
    static SecureRandom fixedRandom() {
        return new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                System.arraycopy(NONCE, 0, bytes, 0, bytes.length);
            }
        };
    }

    static byte[] gcm(byte[] key, byte[] nonce, String aad, String plaintext) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] range(int start, int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (start + i);
        return b;
    }

    static byte[] uuid(CustomerId c) {
        return ByteBuffer.allocate(16).putLong(c.value().getMostSignificantBits())
                .putLong(c.value().getLeastSignificantBits()).array();
    }

    static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
