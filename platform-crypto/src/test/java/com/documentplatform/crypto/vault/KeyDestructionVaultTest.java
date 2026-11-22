package com.documentplatform.crypto.vault;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.KeyDestruction;
import com.documentplatform.crypto.command.CryptoCommand;
import com.documentplatform.crypto.envelope.EnvelopeInfo;
import com.documentplatform.crypto.envelope.RecordCipher;
import com.documentplatform.crypto.envelope.StreamCipher;
import com.documentplatform.crypto.envelope.ValueCipher;
import com.documentplatform.crypto.fingerprint.Fingerprinter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Customer key destruction through a real Vault: the set-up policy destroys the customer's keys; afterwards nothing
 * of that customer opens or fingerprints; the app's runtime policy cannot destroy; the command refuses without the
 * typed-back tenant ID and writes a result without key material.
 */
class KeyDestructionVaultTest {

    static final String PLAIN = "Borrower Philippa Quillfeather, 742 Evergreen";

    static VaultClient setupClient;
    static VaultKeyService setup;
    static VaultClient appClient;
    static VaultKeyService app;

    @BeforeAll
    static void start() {
        setupClient = new VaultClient(VaultDev.address(), VaultAuth.token(VaultDev.token("platform-crypto-setup")));
        setup = new VaultKeyService(setupClient);
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
    void destroyingTheCustomersKeysMakesEverythingOfThatCustomerUnreadable() throws Exception {
        CustomerId c = CustomerId.of(UUID.randomUUID());
        CustomerId other = CustomerId.of(UUID.randomUUID());
        setup.createKeys(c);
        setup.createKeys(other);
        setup.rotate(c);
        ValueCipher values = new ValueCipher(app);
        RecordCipher records = new RecordCipher(app);
        StreamCipher streams = new StreamCipher(app);
        Fingerprinter fingerprints = new Fingerprinter(app);
        String sealed = values.seal(c, "case:1:field:name", PLAIN);
        String otherSealed = values.seal(other, "case:1:field:name", PLAIN);
        String envelope;
        try (RecordCipher.RecordKey key = records.newKey(c, "rec-1")) {
            envelope = key.envelope();
        }
        ByteArrayOutputStream enc = new ByteArrayOutputStream();
        EnvelopeInfo info = streams.encrypt(c, new ByteArrayInputStream(PLAIN.getBytes(StandardCharsets.UTF_8)), enc);
        // "A backup": the stored ciphertexts, kept aside.
        List<String> backup = List.of(sealed, envelope);

        KeyDestruction d = setup.destroyCustomerKeys(c);
        assertEquals(c, d.customer());
        assertEquals("vault-transit", d.keyService());
        assertEquals(List.of(
                KeyDestruction.Key.destroyed(KeyDestruction.Role.WRAPPING, setup.wrappingKeyName(c), 2),
                KeyDestruction.Key.destroyed(KeyDestruction.Role.FINGERPRINT, setup.fingerprintKeyName(c), 1)),
                d.keys());

        assertCode(CryptoError.CRYPTO_KEY_MISSING, () -> values.openText(c, "case:1:field:name", backup.get(0)));
        assertCode(CryptoError.CRYPTO_KEY_MISSING, () -> records.openKey(c, "rec-1", backup.get(1)));
        assertCode(CryptoError.CRYPTO_KEY_MISSING, () -> streams.decrypt(c, info,
                new ByteArrayInputStream(enc.toByteArray()), new ByteArrayOutputStream()));
        assertCode(CryptoError.CRYPTO_KEY_MISSING, () -> fingerprints.fingerprint(c, "name", PLAIN));
        assertCode(CryptoError.CRYPTO_KEY_MISSING, () -> values.seal(c, "case:2", PLAIN));
        assertCode(CryptoError.CRYPTO_KEY_MISSING, () -> app.wrappingKey(c));
        assertEquals(PLAIN, values.openText(other, "case:1:field:name", otherSealed), "other customers are untouched");

        KeyDestruction again = setup.destroyCustomerKeys(c);
        assertFalse(again.destroyedAny(), "a rerun finds both keys gone");
        assertTrue(again.keys().stream().allMatch(k -> k.state() == KeyDestruction.State.ABSENT));
    }

    @Test
    void theRuntimePolicyCannotDestroyKeys() {
        CustomerId c = CustomerId.of(UUID.randomUUID());
        setup.createKeys(c);
        String sealed = new ValueCipher(app).seal(c, "f", PLAIN);
        assertCode(CryptoError.CRYPTO_PERMISSION_DENIED, () -> app.destroyCustomerKeys(c));
        assertEquals(PLAIN, new ValueCipher(app).openText(c, "f", sealed), "nothing was destroyed");
        assertEquals(1, app.fingerprintKey(c).latest());
    }

    @Test
    void theCommandNeedsTheTenantIdTypedBackAndWritesAResultWithoutKeyMaterial(@TempDir Path dir) throws Exception {
        String tenant = "TEN-EXIT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        CustomerId c = CustomerId.fromTenantId(tenant);
        setup.createKeys(c);
        String sealed = new ValueCipher(app).seal(c, "f", PLAIN);
        String token = VaultDev.token("platform-crypto-setup");
        Map<String, String> env = new HashMap<>();
        env.put("PLATFORM_SETUP_VAULT_ADDR", VaultDev.address().toString());
        env.put("PLATFORM_SETUP_VAULT_TOKEN", token);
        CryptoCommand command = new CryptoCommand(env, Clock.systemUTC());
        Path result = dir.resolve("certificate-data.json");
        List<String> base = List.of("destroy-customer-keys", "--tenant-id", tenant, "--operator", "Kim Operator",
                "--result-out", result.toString());

        Run wrong = run(command, base, "TEN-SOMEONE-ELSE\n");
        assertEquals(CryptoCommand.EXIT_REFUSED, wrong.exit);
        assertTrue(wrong.err.startsWith("CRYPTO_DESTROY_CONFIRMATION_WRONG"), wrong.err);
        Run none = run(command, base, "");
        assertTrue(none.err.startsWith("CRYPTO_DESTROY_NOT_CONFIRMED"), none.err);
        assertFalse(Files.exists(result));
        assertEquals(PLAIN, new ValueCipher(app).openText(c, "f", sealed), "refused: nothing destroyed");

        Run done = run(command, base, tenant + "\n");
        assertEquals(CryptoCommand.EXIT_DONE, done.exit, done.err);
        String json = Files.readString(result);
        assertTrue(json.contains("\"format\": \"platform-key-destruction/1\""), json);
        assertTrue(json.contains("\"tenantId\": \"" + tenant + "\""));
        assertTrue(json.contains("\"name\": \"customer-" + c.hex() + "\", \"versions\": \"1-1\", \"state\": \"DESTROYED\""));
        assertTrue(json.contains("\"name\": \"customer-" + c.hex() + "-fp\""));
        assertTrue(json.contains("\"operator\": \"Kim Operator\"") && json.contains("\"destroyedAt\": \""));
        for (String output : List.of(json, done.out, done.err)) {
            assertFalse(output.contains(token), "no token");
            assertFalse(output.contains("vault:v"), "no wrapped key");
            assertFalse(output.contains(sealed.substring(5, 40)), "no ciphertext");
        }
        assertCode(CryptoError.CRYPTO_KEY_MISSING, () -> new ValueCipher(app).openText(c, "f", sealed));

        Run rerun = run(command, List.of("destroy-customer-keys", "--tenant-id", tenant, "--operator", "Kim",
                "--confirm", tenant, "--result-out", dir.resolve("again.json").toString()), "");
        assertEquals(CryptoCommand.EXIT_REFUSED, rerun.exit);
        assertTrue(rerun.err.startsWith("CRYPTO_DESTROY_NO_KEYS"), rerun.err);
        assertFalse(Files.exists(dir.resolve("again.json")));
    }

    @Test
    void theCommandWithTheRuntimeLoginFailsAndDestroysNothing(@TempDir Path dir) {
        String tenant = "TEN-STAY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        CustomerId c = CustomerId.fromTenantId(tenant);
        setup.createKeys(c);
        Map<String, String> env = Map.of("PLATFORM_SETUP_VAULT_ADDR", VaultDev.address().toString(),
                "PLATFORM_SETUP_VAULT_TOKEN", VaultDev.token("platform-crypto-app"));
        Run r = run(new CryptoCommand(env, Clock.systemUTC()), List.of("destroy-customer-keys", "--tenant-id", tenant,
                "--operator", "Kim", "--confirm", tenant, "--result-out", dir.resolve("r.json").toString()), "");
        assertEquals(CryptoCommand.EXIT_FAILED, r.exit);
        assertTrue(r.err.startsWith("CRYPTO_PERMISSION_DENIED"), r.err);
        assertFalse(Files.exists(dir.resolve("r.json")));
        assertEquals(1, setup.wrappingKey(c).latest(), "the keys are still there");
    }

    record Run(int exit, String out, String err) {
    }

    static Run run(CryptoCommand command, List<String> args, String typed) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = command.run(args, new BufferedReader(new StringReader(typed)),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    static void assertCode(CryptoError code, org.junit.jupiter.api.function.Executable call) {
        CryptoException e = assertThrows(CryptoException.class, call);
        assertEquals(code, e.code(), e.getMessage());
    }
}
