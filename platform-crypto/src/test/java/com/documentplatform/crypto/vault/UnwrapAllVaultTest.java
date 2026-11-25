package com.documentplatform.crypto.vault;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.KeyService;
import com.documentplatform.crypto.Unwrapped;
import com.documentplatform.crypto.envelope.RecordCipher;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Batch opening of data keys (P054) through a real Vault, with the app's login and policy. A relay in front of Vault
 * counts the calls, keeps Vault's answers, and can put another answer in place of Vault's.
 */
class UnwrapAllVaultTest {

    record Answer(int status, String body) {
    }

    /** Wrapped keys with the keys themselves, to compare. */
    record Made(List<String> wrapped, List<byte[]> keys) {
    }

    static final CustomerId A = CustomerId.of(UUID.randomUUID());
    static final CustomerId B = CustomerId.of(UUID.randomUUID());
    static final List<String> requests = new CopyOnWriteArrayList<>();
    static final List<Answer> answers = new CopyOnWriteArrayList<>();
    static volatile UnaryOperator<Answer> change = a -> a;
    static volatile long delayMs;
    static HttpServer relay;
    static VaultKeyService setup;
    static VaultClient appClient;
    static VaultKeyService app;

    @BeforeAll
    static void start() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        relay = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        relay.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        relay.createContext("/", ex -> {
            try (ex) {
                byte[] in = ex.getRequestBody().readAllBytes();
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(VaultDev.address()
                        + ex.getRequestURI().toString())).method(ex.getRequestMethod(),
                        HttpRequest.BodyPublishers.ofByteArray(in));
                String token = ex.getRequestHeaders().getFirst("X-Vault-Token");
                if (token != null) {
                    b.header("X-Vault-Token", token);
                }
                HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                Answer a = new Answer(r.statusCode(), r.body());
                if (ex.getRequestURI().getPath().startsWith("/v1/transit/decrypt/")) {
                    requests.add(new String(in, StandardCharsets.UTF_8));
                    answers.add(a);
                    a = change.apply(a);
                    Thread.sleep(delayMs);
                }
                byte[] out = a.body().getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(a.status(), out.length == 0 ? -1 : out.length);
                ex.getResponseBody().write(out);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        relay.start();
        setup = new VaultKeyService(new VaultClient(VaultDev.address(),
                VaultAuth.token(VaultDev.token("platform-crypto-setup"))));
        setup.createKeys(A);
        setup.createKeys(B);
        appClient = client(VaultAuth.appRole("approle", VaultDev.roleId(), VaultDev.newSecretId()),
                Duration.ofSeconds(10));
        app = new VaultKeyService(appClient);
    }

    static VaultClient client(VaultAuth auth, Duration timeout) {
        return new VaultClient(URI.create("http://127.0.0.1:" + relay.getAddress().getPort()), null, auth, timeout);
    }

    @AfterAll
    static void stop() {
        appClient.close();
        relay.stop(0);
    }

    @AfterEach
    void reset() {
        change = a -> a;
        delayMs = 0;
        requests.clear();
        answers.clear();
    }

    static Made make(CustomerId c, int n) {
        Made m = new Made(new ArrayList<>(), new ArrayList<>());
        for (int i = 0; i < n; i++) {
            try (KeyService.DataKey dk = app.newDataKey(c)) {
                m.wrapped.add(dk.wrapped());
                m.keys.add(dk.key().clone());
            }
        }
        return m;
    }

    static String changed(String wrapped) {
        int at = wrapped.lastIndexOf(':') + 1;
        byte[] raw = Base64.getDecoder().decode(wrapped.substring(at));
        raw[raw.length - 1] ^= 1;
        return wrapped.substring(0, at) + Base64.getEncoder().encodeToString(raw);
    }

    static CryptoException refused(CryptoError code, Executable call) {
        CryptoException e = assertThrows(CryptoException.class, call);
        assertEquals(code, e.code(), e.getMessage());
        return e;
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 25, 100})
    void orderIsKeptInOneCall(int n) {
        Made made = make(A, n);
        List<Unwrapped> got = app.unwrapAll(A, made.wrapped);
        assertEquals(n, got.size());
        for (int i = 0; i < n; i++) {
            assertArrayEquals(made.keys.get(i), got.get(i).key(), "item " + i);
            assertNull(got.get(i).error());
        }
        assertEquals(1, requests.size(), "one decrypt call for " + n + " items");
        byte[] held = got.get(0).key();
        Unwrapped.closeAll(got);
        assertArrayEquals(new byte[32], held, "wiped");
        assertThrows(IllegalStateException.class, () -> got.get(0).key());
    }

    @Test
    void limitEmptyAndNullCallNothing() {
        assertEquals(List.of(), app.unwrapAll(A, List.of()));
        assertThrows(IllegalArgumentException.class, () -> app.unwrapAll(A,
                Collections.nCopies(101, "vault:v1:AAAA")));
        assertThrows(NullPointerException.class, () -> app.unwrapAll(A, Arrays.asList("vault:v1:AAAA", null)));
        assertThrows(NullPointerException.class, () -> app.unwrapAll(null, List.of()));
        assertEquals(0, requests.size());
    }

    @Test
    void aBadItemFailsAloneAndAsTheSingleUnwrapDoes() {
        Made a = make(A, 3);
        Made b = make(B, 1);
        List<String> items = List.of(a.wrapped.get(0), changed(a.wrapped.get(1)), a.wrapped.get(1), b.wrapped.get(0),
                "not a ciphertext", "vault:v1:AAAA", a.wrapped.get(0).replace("vault:v1:", "vault:v9:"),
                a.wrapped.get(2));
        List<Unwrapped> got = app.unwrapAll(A, items);
        assertEquals(1, requests.size());
        assertEquals(200, answers.get(0).status(), "partial failure answered with the code asked for");
        assertTrue(requests.get(0).contains("\"partial_failure_response_code\":200"));
        int[] good = {0, 2, 7};
        for (int i = 0; i < 3; i++) {
            assertArrayEquals(a.keys.get(i), got.get(good[i]).key());
        }
        for (int i : new int[]{1, 3, 4, 5, 6}) {
            CryptoException single = assertThrows(CryptoException.class, () -> app.unwrap(A, items.get(i)));
            assertEquals(CryptoError.CRYPTO_CIPHERTEXT_INVALID, got.get(i).error(), "item " + i);
            assertEquals(single.getMessage(), got.get(i).failure().getMessage());
            assertEquals(single.code(), assertThrows(CryptoException.class, got.get(i)::key).code());
        }
        Unwrapped.closeAll(got);
    }

    @Test
    void whenEveryItemFailsVaultAnswers400WithTheResults() {
        Made b = make(B, 2);
        List<Unwrapped> got = app.unwrapAll(A, b.wrapped);
        assertEquals(400, answers.get(0).status(), "all failed: 400 whatever was asked for");
        assertTrue(answers.get(0).body().contains("batch_results"));
        assertEquals(List.of(CryptoError.CRYPTO_CIPHERTEXT_INVALID, CryptoError.CRYPTO_CIPHERTEXT_INVALID),
                got.stream().map(Unwrapped::error).toList());
        assertEquals(1, requests.size());
        // Nothing to send at all: refused here, no call.
        assertEquals(CryptoError.CRYPTO_CIPHERTEXT_INVALID, app.unwrapAll(A, List.of("x")).get(0).error());
        assertEquals(1, requests.size());
    }

    @Test
    void versionsMixAndASwitchedOffOneFailsAlone() {
        CustomerId c = CustomerId.of(UUID.randomUUID());
        setup.createKeys(c);
        Made v1 = make(c, 1);
        setup.rotate(c);
        Made v2 = make(c, 1);
        List<String> items = List.of(v1.wrapped.get(0), v2.wrapped.get(0));
        List<Unwrapped> both = app.unwrapAll(c, items);
        assertArrayEquals(v1.keys.get(0), both.get(0).key());
        assertArrayEquals(v2.keys.get(0), both.get(1).key());
        VaultDev.root("POST", "/v1/transit/keys/" + app.wrappingKeyName(c) + "/config",
                "{\"min_decryption_version\":2}");
        List<Unwrapped> one = app.unwrapAll(c, items);
        assertEquals(CryptoError.CRYPTO_KEY_DISABLED, one.get(0).error());
        assertArrayEquals(v2.keys.get(0), one.get(1).key());
        assertEquals(CryptoError.CRYPTO_KEY_DISABLED, app.unwrapAll(c, v1.wrapped).get(0).error(), "all failed");
        refused(CryptoError.CRYPTO_KEY_DISABLED, () -> app.unwrap(c, v1.wrapped.get(0)));
    }

    @Test
    void aFailureOfTheKeyServiceThrows() throws Exception {
        Made a = make(A, 2);
        VaultKeyService noPolicy = new VaultKeyService(client(VaultAuth.token(VaultDev.token("default")),
                Duration.ofSeconds(10)));
        refused(CryptoError.CRYPTO_PERMISSION_DENIED, () -> noPolicy.unwrapAll(A, a.wrapped));
        refused(CryptoError.CRYPTO_LOGIN_REFUSED, () -> new VaultKeyService(client(VaultAuth.appRole("approle",
                VaultDev.roleId(), UUID.randomUUID().toString()), Duration.ofSeconds(10))).unwrapAll(A, a.wrapped));
        refused(CryptoError.CRYPTO_KEY_MISSING, () -> app.unwrapAll(CustomerId.of(UUID.randomUUID()), a.wrapped));
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        VaultKeyService away = new VaultKeyService(new VaultClient(URI.create("http://127.0.0.1:" + port), null,
                VaultAuth.token("t-not-used"), Duration.ofSeconds(2)));
        refused(CryptoError.CRYPTO_KEY_SERVICE_UNREACHABLE, () -> away.unwrapAll(A, a.wrapped));
        VaultKeyService impatient = new VaultKeyService(client(VaultAuth.token(VaultDev.token("platform-crypto-app")),
                Duration.ofMillis(800)));
        delayMs = 2_500;
        refused(CryptoError.CRYPTO_KEY_SERVICE_UNREACHABLE, () -> impatient.unwrapAll(A, a.wrapped));
        delayMs = 0;
        change = x -> new Answer(503, "{\"errors\":[\"Vault is sealed\"]}");
        refused(CryptoError.CRYPTO_KEY_SERVICE_SEALED, () -> app.unwrapAll(A, a.wrapped));
    }

    @Test
    void anAnswerThatIsNotOneResultPerItemThrows() {
        Made a = make(A, 2);
        String key = Base64.getEncoder().encodeToString(a.keys.get(0));
        String first = "{\"data\":{\"batch_results\":[{\"plaintext\":\"" + key + "\"}";
        for (String body : List.of("{}", "{\"data\":{}}", "{\"data\":{\"batch_results\":\"x\"}}", first + "]}}",
                first + ",{}]}}", first + ",\"x\"]}}", first + ",{\"plaintext\":\"%%\"}]}}")) {
            for (int status : new int[]{200, 400}) {
                change = x -> new Answer(status, body);
                CryptoException e = refused(CryptoError.CRYPTO_KEY_SERVICE_ERROR, () -> app.unwrapAll(A, a.wrapped));
                assertFalse(e.getMessage().contains(key));
            }
        }
        change = x -> new Answer(400, "{\"errors\":[\"something odd\"]}");
        refused(CryptoError.CRYPTO_KEY_SERVICE_ERROR, () -> app.unwrapAll(A, a.wrapped));
        change = x -> new Answer(200, first + ",{\"plaintext\":\"AAAA\"}]}}");
        assertEquals(CryptoError.CRYPTO_CIPHERTEXT_INVALID, app.unwrapAll(A, a.wrapped).get(1).error(),
                "not a 256-bit key: as the single unwrap");
    }

    @Test
    void aPageOf25RecordsTakesOneCallInsteadOf25() {
        RecordCipher cipher = new RecordCipher(app);
        List<RecordCipher.StoredKey> page = new ArrayList<>();
        List<String> items = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            try (RecordCipher.RecordKey k = cipher.newKey(A, "fld/r" + i)) {
                page.add(new RecordCipher.StoredKey("fld/r" + i, k.envelope()));
                items.add(k.seal("name/0", "value " + i));
            }
        }
        for (RecordCipher.StoredKey s : page) {
            cipher.openKey(A, s.recordContext(), s.envelope()).close();
        }
        assertEquals(25, requests.size());
        requests.clear();
        List<RecordCipher.OpenedKey> opened = cipher.openKeys(A, page);
        assertEquals(1, requests.size());
        for (int i = 0; i < 25; i++) {
            assertEquals("value " + i, opened.get(i).key().openText("name/0", items.get(i)));
        }
        opened.forEach(RecordCipher.OpenedKey::close);
        assertThrows(IllegalStateException.class, () -> opened.get(0).key().openText("name/0", items.get(0)));
    }

    @Test
    void noKeyOrWrappedValueInAnyText() {
        Made a = make(A, 1);
        List<Unwrapped> got = app.unwrapAll(A, List.of(a.wrapped.get(0), changed(a.wrapped.get(0)), "junk-wrapped"));
        List<String> texts = new ArrayList<>(List.of(got.toString()));
        got.stream().filter(u -> !u.ok()).forEach(u -> texts.add(u.failure().toString()));
        texts.add(new RecordCipher.StoredKey("fld/r", "pck1.hidden").toString());
        assertEquals("Unwrapped[ok]", got.get(0).toString());
        String piece = a.wrapped.get(0).substring("vault:v1:".length(), 30);
        for (String t : texts) {
            assertFalse(t.contains(piece) || t.contains(Base64.getEncoder().encodeToString(a.keys.get(0)))
                    || t.contains("junk-wrapped") || t.contains("pck1.hidden"), t);
        }
    }
}
