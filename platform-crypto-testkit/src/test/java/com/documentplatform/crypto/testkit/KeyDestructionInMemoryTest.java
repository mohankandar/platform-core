package com.documentplatform.crypto.testkit;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.KeyDestruction;
import com.documentplatform.crypto.command.CryptoCommand;
import com.documentplatform.crypto.envelope.ValueCipher;
import com.documentplatform.crypto.fingerprint.Fingerprinter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Customer key destruction in memory, and the command's confirmation rules (no key service call before them). */
class KeyDestructionInMemoryTest {

    static final String TENANT = "TEN-LEAVING-0001";
    static final CustomerId C = CustomerId.fromTenantId(TENANT);
    static final String PLAIN = "Philippa Quillfeather";

    @Test
    void destroyedKeysRefuseDecryptAndFingerprintWithTheirCode() {
        InMemoryKeyService keys = new InMemoryKeyService().createKeysOnDemand();
        ValueCipher values = new ValueCipher(keys);
        String sealed = values.seal(C, "f", PLAIN);
        keys.rotate(C);

        KeyDestruction d = keys.destroyCustomerKeys(C);
        assertEquals(List.of(KeyDestruction.Key.destroyed(KeyDestruction.Role.WRAPPING, "customer-" + C.hex(), 2),
                KeyDestruction.Key.destroyed(KeyDestruction.Role.FINGERPRINT, "customer-" + C.hex() + "-fp", 1)),
                d.keys());
        assertCode(CryptoError.CRYPTO_KEY_MISSING, () -> values.openText(C, "f", sealed));
        assertCode(CryptoError.CRYPTO_KEY_MISSING, () -> new Fingerprinter(keys).fingerprint(C, "f", PLAIN));
        assertCode(CryptoError.CRYPTO_KEY_MISSING, () -> values.seal(C, "f", PLAIN), "not re-created on demand");
        assertFalse(keys.destroyCustomerKeys(C).destroyedAny());
        assertFalse(d.toString().matches(".*[A-Za-z0-9+/]{40,}.*"), "no key material in the result: " + d);
    }

    @Test
    void theRuntimeLoginCannotDestroy() {
        InMemoryKeyService keys = new InMemoryKeyService();
        keys.createKeys(C);
        String sealed = new ValueCipher(keys).seal(C, "f", PLAIN);
        keys.denied(true);
        assertCode(CryptoError.CRYPTO_PERMISSION_DENIED, () -> keys.destroyCustomerKeys(C));
        keys.denied(false);
        assertEquals(PLAIN, new ValueCipher(keys).openText(C, "f", sealed));
    }

    @Test
    void confirmationMissingOrWrongIsRefusedBeforeTheKeyServiceIsCalled(@TempDir Path dir) {
        InMemoryKeyService keys = new InMemoryKeyService();
        keys.createKeys(C);
        AtomicInteger connects = new AtomicInteger();
        CryptoCommand command = new CryptoCommand(Map.of(), Clock.systemUTC(), env -> {
            connects.incrementAndGet();
            return new CryptoCommand.Connection(keys, () -> { });
        });
        Path result = dir.resolve("r.json");
        List<String> args = List.of("destroy-customer-keys", "--tenant-id", TENANT, "--operator", "Kim",
                "--result-out", result.toString());

        assertRefused(command, args, null, "CRYPTO_DESTROY_NOT_CONFIRMED");
        assertRefused(command, args, "\n", "CRYPTO_DESTROY_NOT_CONFIRMED");
        assertRefused(command, args, "TEN-LEAVING-0002\n", "CRYPTO_DESTROY_CONFIRMATION_WRONG");
        assertRefused(command, args, "ten-leaving-0001\n", "CRYPTO_DESTROY_CONFIRMATION_WRONG");
        assertRefused(command, List.of("destroy-customer-keys", "--tenant-id", TENANT, "--operator", "Kim",
                "--confirm", C.toString(), "--result-out", result.toString()), "", "CRYPTO_DESTROY_CONFIRMATION_WRONG");
        assertRefused(command, List.of("destroy-customer-keys", "--tenant-id", TENANT, "--confirm", TENANT), "",
                "CRYPTO_COMMAND_USAGE");
        assertRefused(command, List.of("destroy-keys"), "", "CRYPTO_COMMAND_USAGE");
        assertEquals(0, connects.get(), "the key service was never called");
        assertEquals(1, keys.wrappingKey(C).latest(), "nothing destroyed");
        assertFalse(Files.exists(result));
    }

    @Test
    void anExistingResultFileIsNeverOverwrittenAndNothingIsDestroyed(@TempDir Path dir) throws Exception {
        InMemoryKeyService keys = new InMemoryKeyService();
        keys.createKeys(C);
        Path result = Files.writeString(dir.resolve("r.json"), "earlier certificate data");
        CryptoCommand command = new CryptoCommand(Map.of(), Clock.systemUTC(),
                env -> new CryptoCommand.Connection(keys, () -> { }));
        assertRefused(command, List.of("destroy-customer-keys", "--tenant-id", TENANT, "--operator", "Kim",
                "--confirm", TENANT, "--result-out", result.toString()), "", "CRYPTO_DESTROY_RESULT_FILE");
        assertEquals("earlier certificate data", Files.readString(result));
        assertEquals(1, keys.wrappingKey(C).latest());
    }

    @Test
    void theResultHoldsTheCertificateDataAndTheDefaultFileNameHasTheTenantAndTime(@TempDir Path dir)
            throws Exception {
        InMemoryKeyService keys = new InMemoryKeyService();
        keys.createKeys(C);
        Clock clock = Clock.fixed(Instant.parse("2026-11-22T10:15:30Z"), ZoneOffset.UTC);
        AtomicInteger closed = new AtomicInteger();
        CryptoCommand command = new CryptoCommand(Map.of(), clock,
                env -> new CryptoCommand.Connection(keys, closed::incrementAndGet));
        Path result = dir.resolve("customer-keys-destroyed-" + TENANT + "-20261122T101530Z.json");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exit = command.run(List.of("destroy-customer-keys", "--tenant-id", TENANT, "--operator", "Kim O'Brien",
                        "--result-out", result.toString()), new BufferedReader(new StringReader(TENANT + "\n")),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()));
        assertEquals(CryptoCommand.EXIT_DONE, exit);
        assertEquals(1, closed.get(), "the login is ended");
        String json = Files.readString(result);
        assertTrue(json.startsWith("{\n  \"format\": \"platform-key-destruction/1\",\n  \"tenantId\": \"" + TENANT
                + "\",\n  \"customer\": \"" + C + "\",\n  \"keyService\": \"in-memory\",\n  \"keys\": ["), json);
        assertTrue(json.contains("{\"role\": \"WRAPPING\", \"name\": \"customer-" + C.hex()
                + "\", \"versions\": \"1-1\", \"state\": \"DESTROYED\"}"), json);
        assertTrue(json.contains("\"operator\": \"Kim O'Brien\"\n}"), json);
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("Type the tenant ID to confirm: "), "asked on the console");
        assertTrue(text.contains("wrapping key customer-" + C.hex() + ": destroyed, versions 1 to 1"), text);
        assertCode(CryptoError.CRYPTO_KEY_MISSING, () -> keys.wrappingKey(C));
    }

    @Test
    void theSecondDateIsTheDestructionDayPlusTheBackupRetention() {
        KeyDestruction late = new KeyDestruction(C, "in-memory", List.of(), Instant.parse("2026-11-22T23:59:59Z"));
        assertEquals(java.time.LocalDate.parse("2026-11-22"), CryptoCommand.liveDataDestroyedOn(late), "UTC day");
        assertEquals(java.time.LocalDate.parse("2026-12-27"), CryptoCommand.unreadableEverywhereFrom(late, 35));
        assertEquals(java.time.LocalDate.parse("2026-12-22"), CryptoCommand.unreadableEverywhereFrom(late, 30));
        assertEquals(java.time.LocalDate.parse("2027-02-20"), CryptoCommand.unreadableEverywhereFrom(late, 90));
        KeyDestruction leap = new KeyDestruction(C, "in-memory", List.of(), Instant.parse("2028-02-01T00:00:00Z"));
        assertEquals(java.time.LocalDate.parse("2028-03-07"), CryptoCommand.unreadableEverywhereFrom(leap, 35));
    }

    @Test
    void theCertificateDataCarriesBothDatesWithTheDefaultOrTheGivenRetention(@TempDir Path dir) throws Exception {
        for (String days : new String[] {null, "60"}) {
            InMemoryKeyService keys = new InMemoryKeyService();
            keys.createKeys(C);
            CryptoCommand command = new CryptoCommand(Map.of(), Clock.systemUTC(),
                    env -> new CryptoCommand.Connection(keys, () -> { }));
            Path result = dir.resolve("r-" + days + ".json");
            List<String> args = new java.util.ArrayList<>(List.of("destroy-customer-keys", "--tenant-id", TENANT,
                    "--operator", "Kim", "--confirm", TENANT, "--result-out", result.toString()));
            if (days != null) {
                args.addAll(List.of("--backup-retention-days", days));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int exit = command.run(args, null, new PrintStream(out, true, StandardCharsets.UTF_8),
                    new PrintStream(new ByteArrayOutputStream()));
            assertEquals(CryptoCommand.EXIT_DONE, exit);
            String json = Files.readString(result);
            java.util.regex.Matcher at = java.util.regex.Pattern.compile("\"destroyedAt\": \"([^\"]+)\"").matcher(json);
            assertTrue(at.find(), json);
            java.time.LocalDate day = java.time.LocalDate.ofInstant(Instant.parse(at.group(1)), ZoneOffset.UTC);
            int retention = days == null ? 35 : 60;
            java.time.LocalDate from = day.plusDays(retention);
            assertTrue(json.contains("\"liveDataDestroyedOn\": \"" + day + "\",\n  \"backupRetentionDays\": "
                    + retention + ",\n  \"unreadableEverywhereFrom\": \"" + from + "\",\n  \"operator\": \"Kim\""), json);
            assertTrue(out.toString(StandardCharsets.UTF_8).contains("Live data destroyed on " + day
                    + "; unreadable everywhere, backups included, from " + from + " (backup retention " + retention
                    + " days)."), out.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void aBackupRetentionOutsideThirtyToNinetyDaysIsRefusedBeforeTheKeyServiceIsCalled(@TempDir Path dir) {
        InMemoryKeyService keys = new InMemoryKeyService();
        keys.createKeys(C);
        AtomicInteger connects = new AtomicInteger();
        CryptoCommand command = new CryptoCommand(Map.of(), Clock.systemUTC(), env -> {
            connects.incrementAndGet();
            return new CryptoCommand.Connection(keys, () -> { });
        });
        for (String days : List.of("29", "91", "0", "-35", "35.5", "abc")) {
            assertRefused(command, List.of("destroy-customer-keys", "--tenant-id", TENANT, "--operator", "Kim",
                    "--confirm", TENANT, "--result-out", dir.resolve("r.json").toString(), "--backup-retention-days",
                    days), "", "CRYPTO_COMMAND_USAGE");
        }
        assertEquals(0, connects.get(), "the key service was never called");
        assertFalse(Files.exists(dir.resolve("r.json")));
        assertEquals(1, keys.wrappingKey(C).latest(), "nothing destroyed");
    }

    @Test
    void aFailingKeyServiceIsReportedAndTheEmptyResultFileRemoved(@TempDir Path dir) {
        InMemoryKeyService keys = new InMemoryKeyService();
        keys.createKeys(C);
        keys.down(true);
        CryptoCommand command = new CryptoCommand(Map.of(), Clock.systemUTC(),
                env -> new CryptoCommand.Connection(keys, () -> { }));
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = command.run(List.of("destroy-customer-keys", "--tenant-id", TENANT, "--operator", "Kim",
                        "--confirm", TENANT, "--result-out", dir.resolve("r.json").toString()), null,
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(CryptoCommand.EXIT_FAILED, exit);
        assertTrue(err.toString(StandardCharsets.UTF_8).startsWith("CRYPTO_KEY_SERVICE_UNREACHABLE"));
        assertFalse(Files.exists(dir.resolve("r.json")));
    }

    private static void assertRefused(CryptoCommand command, List<String> args, String typed, String code) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = command.run(args, typed == null ? null : new BufferedReader(new StringReader(typed)),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(CryptoCommand.EXIT_REFUSED, exit, err.toString(StandardCharsets.UTF_8));
        assertTrue(err.toString(StandardCharsets.UTF_8).startsWith(code + ": "), err.toString(StandardCharsets.UTF_8));
    }

    private static void assertCode(CryptoError code, org.junit.jupiter.api.function.Executable call, String... why) {
        CryptoException e = assertThrows(CryptoException.class, call, why.length == 0 ? null : why[0]);
        assertEquals(code, e.code(), e.getMessage());
    }
}
