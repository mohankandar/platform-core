package com.documentplatform.crypto.vault;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.envelope.ValueCipher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P044, SEC-2 against a real Vault (Testcontainers): the set-up policy can create and rotate a customer's keys, but can
 * never make one unreadable. The review's attack (rotate, then raise the lowest usable version and trim the old
 * versions away) is refused step by step, so are export, plaintext backup, import and delete; afterwards the value
 * sealed under the first version still opens. Only the offboarding policy, alone, destroys keys; and a login whose
 * rights Vault cannot report is refused before the key is touched (SEC-48).
 */
class SetupPolicyVaultTest {

    static final String PLAIN = "a sensitive value sealed under version 1";

    static String setupToken;
    static VaultKeyService setup;
    static VaultClient setupClient;
    static VaultClient appClient;
    static VaultKeyService app;

    @BeforeAll
    static void start() {
        setupToken = VaultDev.token("platform-crypto-setup");
        setupClient = new VaultClient(VaultDev.address(), VaultAuth.token(setupToken));
        setup = new VaultKeyService(setupClient);
        appClient = new VaultClient(VaultDev.address(), null,
                VaultAuth.appRole("approle", VaultDev.roleId(), VaultDev.newSecretId()), Duration.ofSeconds(10));
        app = new VaultKeyService(appClient);
    }

    @AfterAll
    static void stop() {
        setupClient.close();
        appClient.close();
    }

    @Test
    void theSetUpLoginCreatesAndRotatesButCanNeverMakeAKeyUnreadable() {
        CustomerId c = CustomerId.of(UUID.randomUUID());
        setup.createKeys(c);
        String sealed = new ValueCipher(app).seal(c, "f", PLAIN);
        assertEquals(2, setup.rotate(c), "rotating is the set-up step's job");
        String key = "/v1/transit/keys/" + setup.wrappingKeyName(c);

        // The review's attack, step by step: every one is refused.
        assertEquals(403, VaultDev.statusWithToken("POST", key + "/config", setupToken,
                "{\"min_decryption_version\":2,\"min_encryption_version\":2}"), "lowest usable version");
        assertEquals(403, VaultDev.statusWithToken("POST", key + "/trim", setupToken,
                "{\"min_available_version\":2}"), "trim");
        assertEquals(403, VaultDev.statusWithToken("POST", key + "/config", setupToken,
                "{\"exportable\":true}"), "exportable");
        assertEquals(403, VaultDev.statusWithToken("POST", key + "/config", setupToken,
                "{\"allow_plaintext_backup\":true}"), "plaintext backup");
        assertEquals(403, VaultDev.statusWithToken("POST", key + "/config", setupToken,
                "{\"deletion_allowed\":true}"), "deletion allowed");
        assertEquals(403, VaultDev.statusWithToken("POST", key + "/import_version", setupToken,
                "{\"ciphertext\":\"x\"}"), "import a key version");
        assertEquals(403, VaultDev.statusWithToken("DELETE", key, setupToken), "delete");

        Map<String, Object> data = VaultJson.object(VaultDev.root("GET", key, null), "data").orElseThrow();
        assertEquals(1, ((Number) data.get("min_decryption_version")).intValue());
        assertEquals(Boolean.FALSE, data.get("exportable"));
        assertEquals(PLAIN, new ValueCipher(app).openText(c, "f", sealed), "version 1 still opens");
    }

    @Test
    void theOffboardPolicyAloneDestroysTheKeys() {
        CustomerId c = CustomerId.of(UUID.randomUUID());
        setup.createKeys(c);
        try (VaultClient offboardClient = new VaultClient(VaultDev.address(),
                VaultAuth.token(VaultDev.token("platform-crypto-offboard")))) {
            assertTrue(new VaultKeyService(offboardClient).destroyCustomerKeys(c).destroyedAny());
        }
        assertEquals(CryptoError.CRYPTO_KEY_MISSING,
                assertThrows(CryptoException.class, () -> app.wrappingKey(c)).code());
    }

    @Test
    void aLoginWhoseRightsVaultCannotReportIsRefusedBeforeTheKeyIsTouched() {
        CustomerId c = CustomerId.of(UUID.randomUUID());
        setup.createKeys(c);
        // Without Vault's default policy there is no sys/capabilities-self: before P044 the key was then marked
        // deletion_allowed and only the delete was refused, leaving that latch open.
        try (VaultClient blind = new VaultClient(VaultDev.address(),
                VaultAuth.token(VaultDev.tokenWithoutDefaultPolicy("platform-crypto-offboard")))) {
            CryptoException e = assertThrows(CryptoException.class,
                    () -> new VaultKeyService(blind).destroyCustomerKeys(c));
            assertEquals(CryptoError.CRYPTO_PERMISSION_DENIED, e.code(), e.getMessage());
        }
        Map<String, Object> data = VaultJson.object(VaultDev.root("GET", "/v1/transit/keys/"
                + setup.wrappingKeyName(c), null), "data").orElseThrow();
        assertEquals(Boolean.FALSE, data.get("deletion_allowed"), "the key was not touched");
    }
}
