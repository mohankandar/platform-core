package com.documentplatform.crypto.vault;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.envelope.RecordCipher;
import com.documentplatform.crypto.fingerprint.Fingerprinter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The record envelope and batch fingerprints through a real Vault, with the app's AppRole login and the
 * {@code platform-crypto-app} policy; keys created by the set-up policy.
 */
class RecordEnvelopeVaultTest {

    static final CustomerId A = CustomerId.fromTenantId("TEN-VAULT-0001");
    static final CustomerId B = CustomerId.fromTenantId("TEN-VAULT-0002");

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
        appClient = new VaultClient(VaultDev.address(), null,
                VaultAuth.appRole("approle", VaultDev.roleId(), VaultDev.newSecretId()), Duration.ofSeconds(10));
        app = new VaultKeyService(appClient);
    }

    @AfterAll
    static void stop() {
        appClient.close();
        setupClient.close();
    }

    @Test
    void manyValuesOneKeyAndTheyOpenOnlyInTheirPlace() {
        RecordCipher cipher = new RecordCipher(app);
        String envelope;
        String name;
        String street;
        try (RecordCipher.RecordKey key = cipher.newKey(A, "fld/record-1")) {
            envelope = key.envelope();
            name = key.seal("borrower_name/0", "Philippa Quillfeather");
            street = key.seal("mailing_address/0", "742 Evergreen Terrace");
        }
        assertTrue(envelope.startsWith(RecordCipher.ENVELOPE_PREFIX));
        assertTrue(name.startsWith(RecordCipher.ITEM_PREFIX));
        assertEquals(A, RecordCipher.customerOf(envelope));
        try (RecordCipher.RecordKey key = cipher.openKey(A, "fld/record-1", envelope)) {
            assertEquals("Philippa Quillfeather", key.openText("borrower_name/0", name));
            assertEquals("742 Evergreen Terrace", key.openText("mailing_address/0", street));
            assertEquals(CryptoError.CRYPTO_CIPHERTEXT_INVALID, assertThrows(CryptoException.class,
                    () -> key.open("mailing_address/0", name)).code(), "an item moved to another field");
            String more = key.seal("borrower_name/1", "Second Borrower");
            assertEquals("Second Borrower", key.openText("borrower_name/1", more));
        }
        try (RecordCipher.RecordKey key = cipher.openKey(A, "fld/record-2", envelope)) {
            assertEquals(CryptoError.CRYPTO_CIPHERTEXT_INVALID, assertThrows(CryptoException.class,
                    () -> key.open("borrower_name/0", name)).code(), "an item moved to another record");
        }
        assertEquals(CryptoError.CRYPTO_WRONG_CUSTOMER, assertThrows(CryptoException.class,
                () -> cipher.openKey(B, "fld/record-1", envelope)).code());
        String tampered = envelope.substring(0, envelope.length() - 3) + (envelope.endsWith("A") ? "BBB" : "AAA");
        assertThrows(CryptoException.class, () -> cipher.openKey(A, "fld/record-1", tampered));
    }

    @Test
    void rotationAndRewrapLeaveTheItemsAsTheyAre() {
        RecordCipher cipher = new RecordCipher(app);
        CustomerId c = CustomerId.fromTenantId("TEN-VAULT-ROT1");
        setup.createKeys(c);
        String envelope;
        String item;
        try (RecordCipher.RecordKey key = cipher.newKey(c, "fld/r")) {
            envelope = key.envelope();
            item = key.seal("k/0", "value one");
        }
        assertEquals(1, cipher.keyVersion(envelope));
        setup.rotate(c);
        String again = cipher.rewrap(c, envelope);
        assertEquals(2, cipher.keyVersion(again));
        try (RecordCipher.RecordKey key = cipher.openKey(c, "fld/r", again)) {
            assertEquals("value one", key.openText("k/0", item));
        }
    }

    @Test
    void batchFingerprintsEqualSingleOnesInOneCall() {
        Fingerprinter fp = new Fingerprinter(app);
        List<Fingerprinter.Input> inputs = List.of(new Fingerprinter.Input("borrower_name", "Philippa Quillfeather"),
                new Fingerprinter.Input("borrower_name", "  PHILIPPA   quillfeather "),
                new Fingerprinter.Input("member_number", "Philippa Quillfeather"));
        List<Fingerprinter.Fingerprint> batch = fp.fingerprintAll(A, inputs);
        assertEquals(3, batch.size());
        assertEquals(fp.fingerprint(A, "borrower_name", "Philippa Quillfeather"), batch.get(0));
        assertEquals(batch.get(0), batch.get(1), "capitals and spaces ignored");
        assertNotEquals(batch.get(0), batch.get(2), "another context");
        assertNotEquals(batch.get(0), fp.fingerprintAll(B, inputs).get(0), "another customer");
        assertEquals(List.of(), fp.fingerprintAll(A, List.of()));
    }

    @Test
    void aTenantIdAlwaysGivesTheSameCustomer() {
        // Pinned (sha256sum of "platform-customer|TEN-TEST-0001", version 5 and variant bits set, computed outside
        // Java): every customer's key names depend on it. Never "fix" the expectation.
        assertEquals(UUID.fromString("a10ed06a-aee3-57ff-8af8-3aec5f6a1113"),
                CustomerId.fromTenantId("TEN-TEST-0001").value());
        assertEquals(5, A.value().version());
        assertNotEquals(A, B);
        assertEquals("customer-" + A.hex(), app.wrappingKeyName(A));
        for (String bad : new String[]{null, "", "TEN-", "ten-1", "ENT-1", "TEN-a"}) {
            assertThrows(IllegalArgumentException.class, () -> CustomerId.fromTenantId(bad), String.valueOf(bad));
        }
    }
}
