package com.documentplatform.crypto.testkit;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.KeyService;
import com.documentplatform.crypto.envelope.EnvelopeInfo;
import com.documentplatform.crypto.envelope.StreamCipher;
import com.documentplatform.crypto.envelope.ValueCipher;
import com.documentplatform.crypto.fingerprint.Fingerprinter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The in-memory key service behaves like the Vault one, and the formats refuse every kind of damage. */
class InMemoryKeyServiceTest {

    static final CustomerId A = CustomerId.of(UUID.randomUUID());
    static final CustomerId B = CustomerId.of(UUID.randomUUID());

    private final InMemoryKeyService keys = new InMemoryKeyService();

    InMemoryKeyServiceTest() {
        keys.createKeys(A);
        keys.createKeys(B);
    }

    @Test
    void keysExistOnlyAfterCreation() {
        CustomerId c = CustomerId.of(UUID.randomUUID());
        refused(CryptoError.CRYPTO_KEY_MISSING, () -> keys.newDataKey(c));
        keys.createKeys(c);
        keys.createKeys(c);
        assertEquals(new KeyService.KeyVersions(1, 1), keys.wrappingKey(c));
        InMemoryKeyService lazy = new InMemoryKeyService().createKeysOnDemand();
        assertEquals(PLAIN, new ValueCipher(lazy).openText(c, "f", new ValueCipher(lazy).seal(c, "f", PLAIN)));
    }

    static final String PLAIN = "Ann Lee, 1 Main St";

    @Test
    void rotationLowestUsableAndRewrap() {
        ValueCipher values = new ValueCipher(keys);
        CustomerId c = CustomerId.of(UUID.randomUUID());
        keys.createKeys(c);
        String v1 = values.seal(c, "f", PLAIN);
        assertEquals(2, keys.rotate(c));
        assertEquals(3, keys.rotate(c));
        String v3 = values.seal(c, "f", PLAIN);
        assertEquals(1, values.keyVersion(v1));
        assertEquals(3, values.keyVersion(v3));
        assertEquals(PLAIN, values.openText(c, "f", v1));
        String moved = values.rewrap(c, v1);
        assertEquals(3, values.keyVersion(moved));
        keys.setLowestUsable(c, 3);
        refused(CryptoError.CRYPTO_KEY_DISABLED, () -> values.open(c, "f", v1));
        assertEquals(PLAIN, values.openText(c, "f", moved));
        assertEquals(new KeyService.KeyVersions(3, 3), keys.wrappingKey(c));
    }

    @Test
    void outagesHaveTheirCodes() {
        ValueCipher values = new ValueCipher(keys);
        String sealed = values.seal(A, "f", PLAIN);
        keys.down(true);
        refused(CryptoError.CRYPTO_KEY_SERVICE_UNREACHABLE, () -> values.open(A, "f", sealed));
        assertFalse(keys.health().up());
        assertEquals(CryptoError.CRYPTO_KEY_SERVICE_UNREACHABLE, keys.health().code());
        keys.down(false).denied(true);
        refused(CryptoError.CRYPTO_PERMISSION_DENIED, () -> values.seal(A, "f", PLAIN));
        keys.denied(false);
        assertTrue(keys.health().up());
        int before = keys.calls();
        assertEquals(PLAIN, values.openText(A, "f", sealed));
        assertEquals(before + 1, keys.calls(), "one key service call per open");
    }

    @Test
    void valueFormatRefusals() {
        ValueCipher values = new ValueCipher(keys);
        String sealed = values.seal(A, "case:1:field:x", PLAIN);
        refused(CryptoError.CRYPTO_WRONG_CUSTOMER, () -> values.open(B, "case:1:field:x", sealed));
        refused(CryptoError.CRYPTO_CIPHERTEXT_INVALID, () -> values.open(A, "case:1:field:y", sealed));
        refused(CryptoError.CRYPTO_FORMAT_UNKNOWN, () -> values.open(A, "c", "nope"));
        refused(CryptoError.CRYPTO_CIPHERTEXT_INVALID, () -> values.open(A, "c", "pcv1.@@@"));
        refused(CryptoError.CRYPTO_CIPHERTEXT_INVALID, () -> values.open(A, "c", "pcv1.AQ"));
        byte[] raw = java.util.Base64.getUrlDecoder().decode(sealed.substring(5));
        raw[0] = 9;
        refused(CryptoError.CRYPTO_FORMAT_UNKNOWN, () -> values.open(A, "c", "pcv1."
                + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw)));
        ValueCipher other = new ValueCipher(new OtherId(keys));
        refused(CryptoError.CRYPTO_KEY_SERVICE_MISMATCH, () -> other.open(A, "case:1:field:x", sealed));
        assertThrows(IllegalArgumentException.class, () -> values.seal(A, "bad context!", PLAIN));
        assertThrows(IllegalArgumentException.class, () -> values.seal(A, "c", new byte[64 * 1024 + 1]));
    }

    @Test
    void streamFormatRefusesReorderingDroppingAndTrailingBytes() throws IOException {
        StreamCipher streams = new StreamCipher(keys);
        byte[] clear = new byte[3 * 64 * 1024];
        new SecureRandom().nextBytes(clear);
        ByteArrayOutputStream enc = new ByteArrayOutputStream();
        EnvelopeInfo info = streams.encrypt(A, new ByteArrayInputStream(clear), enc);
        byte[] good = enc.toByteArray();
        int header = 8 + 4 + 4 + 8;
        int record = 1 + 4 + 4 + 64 * 1024 + 16;

        byte[] swapped = good.clone();
        System.arraycopy(good, header, swapped, header + record, record);
        System.arraycopy(good, header + record, swapped, header, record);
        streamRefused(streams, info, swapped, "chunks swapped");

        byte[] dropped = new byte[good.length - record];
        System.arraycopy(good, 0, dropped, 0, header + record);
        System.arraycopy(good, header + 2 * record, dropped, header + record, good.length - header - 2 * record);
        streamRefused(streams, info, dropped, "a chunk dropped");

        streamRefused(streams, info, Arrays.copyOf(good, header + 3 * record), "final record dropped");
        byte[] trailing = Arrays.copyOf(good, good.length + 1);
        streamRefused(streams, info, trailing, "a byte after the end");

        byte[] reindexed = good.clone();
        ByteBuffer.wrap(reindexed, header + 1, 4).putInt(1);
        streamRefused(streams, info, reindexed, "index changed");

        EnvelopeInfo otherKey = info.withWrappedKey(keys.newDataKey(A).wrapped(), 1);
        streamRefused(streams, otherKey, good, "another data key");

        byte[] bad = good.clone();
        bad[0] = 'X';
        CryptoException e = assertThrows(CryptoException.class, () -> streams.decrypt(A, info,
                new ByteArrayInputStream(bad), new ByteArrayOutputStream()));
        assertEquals(CryptoError.CRYPTO_FORMAT_UNKNOWN, e.code());

        ByteArrayOutputStream dec = new ByteArrayOutputStream();
        streams.decrypt(A, info, new ByteArrayInputStream(good), dec);
        assertArrayEquals(clear, dec.toByteArray());
        assertFalse(info.toString().contains(info.wrappedKey()));
    }

    @Test
    void fingerprints() {
        Fingerprinter fp = new Fingerprinter(keys);
        assertEquals(fp.fingerprint(A, "name", "Ann Lee"), fp.fingerprint(A, "name", " ann\tLEE "));
        assertNotEquals(fp.fingerprint(A, "name", "Ann Lee"), fp.fingerprint(B, "name", "Ann Lee"));
        assertEquals(fp.fingerprint(A, "n", "été"), fp.fingerprint(A, "n", "été"),
                "composed and decomposed forms match (NFC)");
        Fingerprinter.Fingerprint old = fp.fingerprint(A, "name", "Ann Lee");
        assertEquals(2, keys.rotateFingerprintKey(A));
        assertEquals(2, fp.fingerprint(A, "name", "Ann Lee").version());
        assertTrue(fp.searchTerms(A, "name", "Ann Lee").contains(old));
        assertEquals("ann lee", Fingerprinter.canonical("  Ann \n  LEE "));
    }

    @Test
    void dataKeysAreWipedAndHidden() {
        KeyService.DataKey dk = keys.newDataKey(A);
        byte[] key = dk.key();
        assertFalse(dk.toString().contains(dk.wrapped()));
        dk.close();
        assertArrayEquals(new byte[32], key);
    }

    private static void streamRefused(StreamCipher s, EnvelopeInfo info, byte[] data, String why) {
        CryptoException e = assertThrows(CryptoException.class, () -> s.decrypt(A, info,
                new ByteArrayInputStream(data), new ByteArrayOutputStream()), why);
        assertEquals(CryptoError.CRYPTO_CIPHERTEXT_INVALID, e.code(), why + ": " + e.getMessage());
    }

    static void refused(CryptoError code, Executable e) {
        CryptoException ex = assertThrows(CryptoException.class, e);
        assertEquals(code, ex.code(), ex.getMessage());
    }

    /** The same keys under another key service name. */
    private record OtherId(KeyService inner) implements KeyService {
        public String id() {
            return "other";
        }

        public void createKeys(CustomerId c) {
            inner.createKeys(c);
        }

        public KeyVersions wrappingKey(CustomerId c) {
            return inner.wrappingKey(c);
        }

        public KeyVersions fingerprintKey(CustomerId c) {
            return inner.fingerprintKey(c);
        }

        public DataKey newDataKey(CustomerId c) {
            return inner.newDataKey(c);
        }

        public byte[] unwrap(CustomerId c, String w) {
            return inner.unwrap(c, w);
        }

        public String rewrap(CustomerId c, String w) {
            return inner.rewrap(c, w);
        }

        public int versionOf(String w) {
            return inner.versionOf(w);
        }

        public int rotate(CustomerId c) {
            return inner.rotate(c);
        }

        public byte[] fingerprint(CustomerId c, int v, byte[] in) {
            return inner.fingerprint(c, v, in);
        }

        public Health health() {
            return inner.health();
        }
    }
}
