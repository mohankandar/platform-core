package com.documentplatform.crypto.testkit;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.KeyService;
import com.documentplatform.crypto.Unwrapped;
import com.documentplatform.crypto.envelope.RecordCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** Batch opening (P054): the in-memory key service's batch, and the interface's default over single unwraps. */
class UnwrapAllTest {

    static final CustomerId A = CustomerId.of(UUID.randomUUID());
    static final CustomerId B = CustomerId.of(UUID.randomUUID());

    private final InMemoryKeyService keys = new InMemoryKeyService();
    /** A key service written against 1.0.0: it has no batch of its own. */
    private final KeyService old = keyService((proxy, m, args) -> m.getName().equals("unwrapAll")
            ? InvocationHandler.invokeDefault(proxy, m, args) : call(keys, m, args));

    UnwrapAllTest() {
        keys.createKeys(A);
        keys.createKeys(B);
    }

    private static Object call(KeyService inner, java.lang.reflect.Method m, Object[] args) throws Throwable {
        try {
            return m.invoke(inner, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static KeyService keyService(InvocationHandler h) {
        return (KeyService) Proxy.newProxyInstance(KeyService.class.getClassLoader(), new Class<?>[]{KeyService.class},
                h);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 25, 100})
    void orderAndCalls(int n) {
        List<String> wrapped = new ArrayList<>();
        List<byte[]> plain = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            KeyService.DataKey dk = keys.newDataKey(A);
            wrapped.add(dk.wrapped());
            plain.add(dk.key());
        }
        for (KeyService k : List.of(keys, old)) {
            int before = keys.calls();
            List<Unwrapped> got = k.unwrapAll(A, wrapped);
            assertEquals(k == keys ? 1 : n, keys.calls() - before);
            assertEquals(n, got.size());
            for (int i = 0; i < n; i++) {
                assertArrayEquals(plain.get(i), got.get(i).key());
            }
            List<byte[]> held = got.stream().map(Unwrapped::key).toList();
            Unwrapped.closeAll(got);
            held.forEach(b -> assertArrayEquals(new byte[32], b));
        }
    }

    @Test
    void itemsFailAloneAndTheCallFailsWhole() {
        String a = keys.newDataKey(A).wrapped();
        String b = keys.newDataKey(B).wrapped();
        String changed = a.substring(0, a.length() - 5) + (a.endsWith("AAAAA") ? "BBBBB" : "AAAAA");
        keys.rotate(A);
        String a2 = keys.newDataKey(A).wrapped();
        for (KeyService k : List.of(keys, old)) {
            assertEquals(Arrays.asList(null, CryptoError.CRYPTO_CIPHERTEXT_INVALID,
                    CryptoError.CRYPTO_CIPHERTEXT_INVALID, null, CryptoError.CRYPTO_CIPHERTEXT_INVALID),
                    k.unwrapAll(A, List.of(a, changed, b, a2, "junk")).stream().map(Unwrapped::error).toList());
        }
        keys.setLowestUsable(A, 2);
        for (KeyService k : List.of(keys, old)) {
            assertEquals(Arrays.asList(CryptoError.CRYPTO_KEY_DISABLED, null),
                    k.unwrapAll(A, List.of(a, a2)).stream().map(Unwrapped::error).toList());
            int before = keys.calls();
            assertEquals(List.of(), k.unwrapAll(A, List.of()));
            assertThrows(IllegalArgumentException.class, () -> k.unwrapAll(A, Collections.nCopies(101, a)));
            assertThrows(NullPointerException.class, () -> k.unwrapAll(A, Arrays.asList(a, null)));
            assertEquals(before, keys.calls(), "no call");
            keys.down(true);
            whole(CryptoError.CRYPTO_KEY_SERVICE_UNREACHABLE, k, A, a2);
            keys.down(false).denied(true);
            whole(CryptoError.CRYPTO_PERMISSION_DENIED, k, A, a2);
            keys.denied(false);
            whole(CryptoError.CRYPTO_KEY_MISSING, k, CustomerId.of(UUID.randomUUID()), a2);
        }
    }

    private static void whole(CryptoError code, KeyService k, CustomerId c, String wrapped) {
        assertEquals(code, assertThrows(CryptoException.class, () -> k.unwrapAll(c, List.of(wrapped, "junk", wrapped)))
                .code());
    }

    /** The default wipes what it had opened when a later item shows that the key service is away. */
    @Test
    void nothingHalfReturned() {
        String a = keys.newDataKey(A).wrapped();
        List<byte[]> given = new ArrayList<>();
        KeyService failing = keyService((proxy, m, args) -> {
            if (m.getName().equals("unwrapAll")) {
                return InvocationHandler.invokeDefault(proxy, m, args);
            }
            if (given.size() == 2) {
                throw new CryptoException(CryptoError.CRYPTO_KEY_SERVICE_UNREACHABLE, "away");
            }
            byte[] k = keys.unwrap(A, a);
            given.add(k);
            return k;
        });
        assertThrows(CryptoException.class, () -> failing.unwrapAll(A, List.of(a, a, a)));
        assertEquals(2, given.size());
        given.forEach(k -> assertArrayEquals(new byte[32], k));
    }

    @Test
    void onlyItemCodesAreItemResults() {
        assertThrows(IllegalArgumentException.class, () -> Unwrapped.failed(new CryptoException(
                CryptoError.CRYPTO_KEY_SERVICE_UNREACHABLE, "x")));
        for (CryptoError e : CryptoError.values()) {
            assertEquals(e == CryptoError.CRYPTO_CIPHERTEXT_INVALID || e == CryptoError.CRYPTO_KEY_DISABLED,
                    Unwrapped.isItemError(e));
        }
        assertEquals(CryptoError.CRYPTO_CIPHERTEXT_INVALID, assertThrows(CryptoException.class,
                () -> Unwrapped.opened(new byte[16])).code());
        assertEquals("Unwrapped[ok]", Unwrapped.opened(new byte[32]).toString());
    }

    @Test
    void recordKeysOpenWithTheErrorsOfTheSingleCall() {
        RecordCipher cipher = new RecordCipher(keys);
        RecordCipher.RecordKey mine = cipher.newKey(A, "r/1");
        String item = mine.seal("f", "value");
        String foreign = cipher.newKey(B, "r/2").envelope();
        List<RecordCipher.StoredKey> in = new ArrayList<>();
        for (String e : Arrays.asList(mine.envelope(), foreign, "nope", null, "pck1.@@", mine.envelope())) {
            in.add(new RecordCipher.StoredKey("r/1", e));
        }
        int before = keys.calls();
        List<RecordCipher.OpenedKey> got = cipher.openKeys(A, in);
        assertEquals(1, keys.calls() - before);
        for (int i = 0; i < in.size(); i++) {
            String envelope = in.get(i).envelope();
            if (got.get(i).failure() == null) {
                assertEquals("value", got.get(i).key().openText("f", item));
            } else {
                assertEquals(assertThrows(CryptoException.class, () -> cipher.openKey(A, "r/1", envelope)).code(),
                        got.get(i).failure().code());
            }
        }
        assertEquals(List.of(0, 5), IntStream.range(0, 6).filter(i -> got.get(i).failure() == null).boxed().toList());
        assertEquals(List.of(), cipher.openKeys(A, List.of()));
        assertThrows(IllegalArgumentException.class, () -> cipher.openKeys(A, Collections.nCopies(101, in.get(0))));
        assertEquals("OpenedKey[ok]", got.get(0).toString());
        KeyService shortAnswer = keyService((p, m, args) -> m.getName().equals("unwrapAll") ? List.of()
                : call(keys, m, args));
        assertEquals(CryptoError.CRYPTO_KEY_SERVICE_ERROR, assertThrows(CryptoException.class,
                () -> new RecordCipher(shortAnswer).openKeys(A, in)).code());
    }
}
