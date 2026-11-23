package com.documentplatform.crypto.vault;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.KeyService;
import com.documentplatform.crypto.envelope.EnvelopeInfo;
import com.documentplatform.crypto.envelope.StreamCipher;
import com.documentplatform.crypto.envelope.ValueCipher;
import com.documentplatform.crypto.fingerprint.Fingerprinter;
import com.documentplatform.crypto.fingerprint.Fingerprinter.Fingerprint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Everything through a real Vault (Testcontainers): the set-up step creates and rotates keys with the
 * {@code platform-crypto-setup} policy; the app works with an AppRole login and the {@code platform-crypto-app}
 * policy only.
 */
class VaultCryptoTest {

    static final CustomerId A = CustomerId.of(UUID.fromString("0a0a0a0a-0000-4000-8000-00000000000a"));
    static final CustomerId B = CustomerId.of(UUID.fromString("0b0b0b0b-0000-4000-8000-00000000000b"));
    static final String PLAIN = "Borrower Philippa Quillfeather, 742 Evergreen";

    static VaultClient setupClient;
    static VaultKeyService setup;
    static VaultClient appClient;
    static VaultKeyService app;

    @BeforeAll
    static void start() {
        setupClient = new VaultClient(VaultDev.address(), VaultAuth.token(VaultDev.token("platform-crypto-setup")));
        setup = new VaultKeyService(setupClient);
        setup.createKeys(A);
        setup.createKeys(B);
        setup.createKeys(A); // twice is fine
        appClient = new VaultClient(VaultDev.address(), null,
                VaultAuth.appRole("approle", VaultDev.roleId(), VaultDev.newSecretId()), Duration.ofSeconds(10));
        app = new VaultKeyService(appClient);
    }

    @AfterAll
    static void stop() {
        appClient.close();
        setupClient.close();
    }

    // ---- encrypt / decrypt ----

    @Test
    void valuesRoundTrip() {
        ValueCipher values = new ValueCipher(app);
        String sealed = values.seal(A, "case:1:field:borrower_name", PLAIN);
        assertTrue(sealed.startsWith("pcv1."), sealed);
        assertFalse(sealed.contains("Quillfeather"));
        assertEquals(PLAIN, values.openText(A, "case:1:field:borrower_name", sealed));
        assertNotEquals(sealed, values.seal(A, "case:1:field:borrower_name", PLAIN), "a new data key every time");
        assertEquals(A, ValueCipher.customerOf(sealed));
        assertEquals(1, values.keyVersion(sealed));
        assertArrayEquals(new byte[0], values.open(A, "c", values.seal(A, "c", new byte[0])), "empty values too");
    }

    @Test
    void streamsRoundTrip() throws IOException {
        StreamCipher streams = new StreamCipher(app);
        for (int size : new int[]{0, 1, 64 * 1024, 64 * 1024 + 1, 200 * 1024}) {
            byte[] clear = new byte[size];
            new SecureRandom().nextBytes(clear);
            ByteArrayOutputStream enc = new ByteArrayOutputStream();
            EnvelopeInfo info = streams.encrypt(A, new ByteArrayInputStream(clear), enc);
            assertEquals(enc.size(), info.encryptedBytes());
            assertEquals("vault-transit", info.keyService());
            assertEquals(A, info.customer());
            ByteArrayOutputStream dec = new ByteArrayOutputStream();
            streams.decrypt(A, info, new ByteArrayInputStream(enc.toByteArray()), dec);
            assertArrayEquals(clear, dec.toByteArray(), "size " + size);
        }
    }

    // ---- rotation ----

    @Test
    void rotationKeepsOldVersionsDecryptingAndRewrapMovesData() throws IOException {
        CustomerId c = CustomerId.of(UUID.randomUUID());
        setup.createKeys(c);
        ValueCipher values = new ValueCipher(app);
        StreamCipher streams = new StreamCipher(app);
        String v1 = values.seal(c, "f", PLAIN);
        ByteArrayOutputStream enc = new ByteArrayOutputStream();
        EnvelopeInfo info1 = streams.encrypt(c, new ByteArrayInputStream(PLAIN.getBytes(StandardCharsets.UTF_8)), enc);
        assertEquals(1, info1.keyVersion());

        assertEquals(2, setup.rotate(c));
        assertEquals(new KeyService.KeyVersions(2, 1), app.wrappingKey(c));
        String v2 = values.seal(c, "f", PLAIN);
        assertEquals(2, values.keyVersion(v2));
        assertEquals(PLAIN, values.openText(c, "f", v1), "version 1 still decrypts");
        assertEquals(PLAIN, values.openText(c, "f", v2));

        String moved = values.rewrap(c, v1);
        assertEquals(2, values.keyVersion(moved));
        EnvelopeInfo info2 = streams.rewrap(c, info1);
        assertEquals(2, info2.keyVersion());
        assertEquals(info1.ciphertextSha256(), info2.ciphertextSha256(), "the stream is not touched");

        VaultDev.root("POST", "/v1/transit/keys/" + app.wrappingKeyName(c) + "/config",
                "{\"min_decryption_version\":2}");
        assertEquals(new KeyService.KeyVersions(2, 2), app.wrappingKey(c));
        refused(CryptoError.CRYPTO_KEY_DISABLED, () -> values.open(c, "f", v1));
        refused(CryptoError.CRYPTO_KEY_DISABLED, () -> streams.decrypt(c, info1,
                new ByteArrayInputStream(enc.toByteArray()), new ByteArrayOutputStream()));
        assertEquals(PLAIN, values.openText(c, "f", moved), "rewrapped data still opens");
        ByteArrayOutputStream dec = new ByteArrayOutputStream();
        streams.decrypt(c, info2, new ByteArrayInputStream(enc.toByteArray()), dec);
        assertEquals(PLAIN, dec.toString(StandardCharsets.UTF_8));
    }

    // ---- other customers, tampering ----

    @Test
    void anotherCustomersDataIsRefused() throws IOException {
        ValueCipher values = new ValueCipher(app);
        String sealedForA = values.seal(A, "f", PLAIN);
        refused(CryptoError.CRYPTO_WRONG_CUSTOMER, () -> values.open(B, "f", sealedForA));

        byte[] raw = Base64.getUrlDecoder().decode(sealedForA.substring(5));
        java.nio.ByteBuffer.wrap(raw, 1, 16).putLong(B.value().getMostSignificantBits())
                .putLong(B.value().getLeastSignificantBits());
        String relabelled = "pcv1." + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        refused(CryptoError.CRYPTO_CIPHERTEXT_INVALID, () -> values.open(B, "f", relabelled),
                "relabelled as B: B's key cannot unwrap A's data key");

        StreamCipher streams = new StreamCipher(app);
        ByteArrayOutputStream enc = new ByteArrayOutputStream();
        EnvelopeInfo info = streams.encrypt(A, new ByteArrayInputStream(new byte[100]), enc);
        refused(CryptoError.CRYPTO_WRONG_CUSTOMER, () -> streams.decrypt(B, info,
                new ByteArrayInputStream(enc.toByteArray()), new ByteArrayOutputStream()));
        EnvelopeInfo forged = new EnvelopeInfo(info.format(), info.formatVersion(), info.keyService(), B,
                info.wrappedKey(), info.keyVersion(), info.encryptedBytes(), info.ciphertextSha256());
        refused(CryptoError.CRYPTO_CIPHERTEXT_INVALID, () -> streams.decrypt(B, forged,
                new ByteArrayInputStream(enc.toByteArray()), new ByteArrayOutputStream()));
        refused(CryptoError.CRYPTO_CIPHERTEXT_INVALID, () -> app.unwrap(B, info.wrappedKey()));
    }

    @Test
    void tamperedCiphertextIsRefused() throws IOException {
        ValueCipher values = new ValueCipher(app);
        String sealed = values.seal(A, "case:1:field:x", PLAIN);
        byte[] raw = Base64.getUrlDecoder().decode(sealed.substring(5));
        raw[raw.length - 1] ^= 1;
        String flipped = "pcv1." + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        refused(CryptoError.CRYPTO_CIPHERTEXT_INVALID, () -> values.open(A, "case:1:field:x", flipped));
        refused(CryptoError.CRYPTO_CIPHERTEXT_INVALID, () -> values.open(A, "case:2:field:x", sealed),
                "moved to another record");
        refused(CryptoError.CRYPTO_CIPHERTEXT_INVALID, () -> values.open(A, "c", sealed.substring(0, 40)));
        refused(CryptoError.CRYPTO_FORMAT_UNKNOWN, () -> values.open(A, "c", "xyz1." + sealed.substring(5)));

        String wrapped = app.newDataKey(A).wrapped();
        String badWrap = wrapped.substring(0, wrapped.length() - 6) + (wrapped.endsWith("AAAA==") ? "BBBB==" : "AAAA==");
        refused(CryptoError.CRYPTO_CIPHERTEXT_INVALID, () -> app.unwrap(A, badWrap));

        StreamCipher streams = new StreamCipher(app);
        byte[] clear = new byte[150 * 1024];
        new SecureRandom().nextBytes(clear);
        ByteArrayOutputStream enc = new ByteArrayOutputStream();
        EnvelopeInfo info = streams.encrypt(A, new ByteArrayInputStream(clear), enc);
        byte[] good = enc.toByteArray();
        for (int at : new int[]{40, 70 * 1024, good.length - 1}) {
            byte[] t = good.clone();
            t[at] ^= 1;
            refused(CryptoError.CRYPTO_CIPHERTEXT_INVALID, () -> streams.decrypt(A, info, new ByteArrayInputStream(t),
                    new ByteArrayOutputStream()), "byte " + at);
        }
        refused(CryptoError.CRYPTO_CIPHERTEXT_INVALID, () -> streams.decrypt(A, info,
                new ByteArrayInputStream(Arrays.copyOf(good, good.length - 30)), new ByteArrayOutputStream()),
                "cut short");
    }

    // ---- fingerprints ----

    @Test
    void fingerprintsAreStablePerCustomerAndDifferAcrossCustomers() {
        Fingerprinter fp = new Fingerprinter(app);
        Fingerprint a1 = fp.fingerprint(A, "borrower_name", "Ann Lee");
        assertEquals(a1, fp.fingerprint(A, "borrower_name", "Ann Lee"), "stable");
        assertEquals(a1, fp.fingerprint(A, "borrower_name", "  ANN   lee "), "capitals and spaces ignored");
        assertNotEquals(a1, fp.fingerprint(B, "borrower_name", "Ann Lee"), "per customer");
        assertNotEquals(a1, fp.fingerprint(A, "co_borrower_name", "Ann Lee"), "per context");
        assertNotEquals(a1, fp.fingerprint(A, "borrower_name", "Ann Leigh"));
        assertEquals(1, a1.version());
        assertEquals(a1, Fingerprint.parse(a1.text()));
        assertTrue(a1.text().matches("f1\\.[A-Za-z0-9_-]{43}"), a1.text());
        assertFalse(a1.toString().contains(a1.mac()));

        CustomerId c = CustomerId.of(UUID.randomUUID());
        setup.createKeys(c);
        Fingerprint before = fp.fingerprint(c, "loan_number", "LN-1");
        VaultDev.root("POST", "/v1/transit/keys/" + app.fingerprintKeyName(c) + "/rotate", null);
        List<Fingerprint> terms = fp.searchTerms(c, "loan_number", "LN-1");
        assertEquals(2, terms.size());
        assertEquals(2, terms.get(0).version());
        assertEquals(before, terms.get(1), "an old fingerprint still matches after a rotation");
    }

    // ---- failures with their own codes ----

    @Test
    void vaultDownGivesItsCode() throws IOException {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        VaultKeyService down = new VaultKeyService(new VaultClient(URI.create("http://127.0.0.1:" + port), null,
                VaultAuth.token("t-not-used"), Duration.ofSeconds(2)));
        refused(CryptoError.CRYPTO_KEY_SERVICE_UNREACHABLE, () -> down.newDataKey(A));
        refused(CryptoError.CRYPTO_KEY_SERVICE_UNREACHABLE, () -> new ValueCipher(down).seal(A, "f", PLAIN));
        KeyService.Health h = down.health();
        assertFalse(h.up());
        assertEquals(CryptoError.CRYPTO_KEY_SERVICE_UNREACHABLE, h.code());
        assertTrue(app.health().up());
        assertNull(app.health().code());
    }

    @Test
    void permissionMissingKeyAndLoginHaveTheirCodes() throws IOException {
        refused(CryptoError.CRYPTO_PERMISSION_DENIED, () -> app.createKeys(CustomerId.of(UUID.randomUUID())),
                "the app may not create keys");
        refused(CryptoError.CRYPTO_PERMISSION_DENIED, () -> app.rotate(A), "nor rotate them");
        VaultKeyService noPolicy = new VaultKeyService(new VaultClient(VaultDev.address(),
                VaultAuth.token(VaultDev.token("default"))));
        refused(CryptoError.CRYPTO_PERMISSION_DENIED, () -> noPolicy.newDataKey(A));

        CustomerId none = CustomerId.of(UUID.randomUUID());
        refused(CryptoError.CRYPTO_KEY_MISSING, () -> app.newDataKey(none));
        refused(CryptoError.CRYPTO_KEY_MISSING, () -> app.wrappingKey(none));
        refused(CryptoError.CRYPTO_KEY_MISSING, () -> new Fingerprinter(app).fingerprint(none, "f", "x"));

        VaultKeyService wrongSecret = new VaultKeyService(new VaultClient(VaultDev.address(),
                VaultAuth.appRole("approle", VaultDev.roleId(), UUID.randomUUID().toString())));
        refused(CryptoError.CRYPTO_LOGIN_REFUSED, () -> wrongSecret.newDataKey(A));

        Path dir = Files.createTempDirectory("vault-token");
        Path file = dir.resolve("token");
        Files.writeString(file, VaultDev.token("platform-crypto-app") + "\n");
        VaultKeyService fromFile = new VaultKeyService(new VaultClient(VaultDev.address(), VaultAuth.tokenFile(file)));
        assertEquals(PLAIN, new ValueCipher(fromFile).openText(A, "f", new ValueCipher(app).seal(A, "f", PLAIN)));
        Files.writeString(file, "  ");
        refused(CryptoError.CRYPTO_LOGIN_REFUSED, () -> new VaultKeyService(new VaultClient(VaultDev.address(),
                VaultAuth.tokenFile(file))).newDataKey(A));

        refused(CryptoError.CRYPTO_SETTINGS_INVALID, () -> new VaultClient(URI.create("ftp://vault"),
                VaultAuth.token("x")));
        refused(CryptoError.CRYPTO_SETTINGS_INVALID, () -> new VaultKeyService(appClient, "transit", "Bad Prefix"));
        refused(CryptoError.CRYPTO_SETTINGS_INVALID, () -> VaultAuth.token(" "));
    }

    // ---- key settings, login again ----

    @Test
    void bothKeysAreNeitherExportableNorInPlaintextBackups() {
        CustomerId c = CustomerId.of(UUID.randomUUID());
        setup.createKeys(c);
        for (String name : List.of(setup.wrappingKeyName(c), setup.fingerprintKeyName(c))) {
            Map<String, Object> key = VaultJson.object(VaultDev.root("GET", "/v1/transit/keys/" + name, null), "data")
                    .orElseThrow();
            assertEquals(Boolean.FALSE, key.get("exportable"), name + " exportable");
            assertEquals(Boolean.FALSE, key.get("allow_plaintext_backup"), name + " allow_plaintext_backup");
            assertEquals("aes256-gcm96", key.get("type"), name);
        }
    }

    @Test
    void anExpiredAppRoleTokenIsReplacedByOneNewLogin() throws InterruptedException {
        // What Vault answers for an expired token: 403 (not 401).
        String shortLived = VaultDev.tokenWithTtl("1s", "platform-crypto-app");
        String path = "/v1/transit/keys/" + app.wrappingKeyName(A);
        assertEquals(200, VaultDev.statusWithToken("GET", path, shortLived));
        Thread.sleep(2_500);
        assertEquals(403, VaultDev.statusWithToken("GET", path, shortLived), "Vault's answer to an expired token");

        try (VaultClient client = new VaultClient(VaultDev.address(), null, VaultAuth.appRole("approle",
                VaultDev.roleId(VaultDev.SHORT_ROLE), VaultDev.newSecretId(VaultDev.SHORT_ROLE)),
                Duration.ofSeconds(10))) {
            ValueCipher values = new ValueCipher(new VaultKeyService(client));
            String sealed = values.seal(A, "f", PLAIN);
            Thread.sleep(VaultDev.SHORT_TTL_SECONDS * 1000L + 1_500);
            assertEquals(PLAIN, values.openText(A, "f", sealed), "the call after the token expired succeeds");
            assertEquals(PLAIN, values.openText(A, "f", values.seal(A, "f", PLAIN)));
        }
    }

    // ---- KV ----

    @Test
    void kvSecretsAreReadAndHidden() {
        VaultDev.root("POST", "/v1/secret/data/platform/smtp",
                "{\"data\":{\"password\":\"kv-pw-Zq81\",\"user\":\"mailer\"}}");
        VaultClient.Secret s = appClient.readSecret("secret", "platform/smtp");
        assertEquals("kv-pw-Zq81", s.get("password"));
        assertEquals("mailer", s.get("user"));
        assertFalse(s.toString().contains("kv-pw-Zq81"), s.toString());
        refused(CryptoError.CRYPTO_SECRET_MISSING, () -> appClient.readSecret("secret", "platform/none"));
        refused(CryptoError.CRYPTO_PERMISSION_DENIED, () -> appClient.writeSecret("secret", "platform/smtp",
                Map.of("password", "x")), "the app only reads");
        VaultClient rootClient = new VaultClient(VaultDev.address(), VaultAuth.token(VaultDev.ROOT_TOKEN));
        rootClient.writeSecret("secret", "platform/other", Map.of("k", "v"));
        assertEquals("v", appClient.readSecret("secret", "platform/other").get("k"));
        refused(CryptoError.CRYPTO_SETTINGS_INVALID, () -> appClient.readSecret("secret", "../sys"));
    }

    static void refused(CryptoError code, Executable e) {
        refused(code, e, "");
    }

    static void refused(CryptoError code, Executable e, String why) {
        CryptoException ex = assertThrows(CryptoException.class, e, why);
        assertEquals(code, ex.code(), why + ": " + ex.getMessage());
    }
}
