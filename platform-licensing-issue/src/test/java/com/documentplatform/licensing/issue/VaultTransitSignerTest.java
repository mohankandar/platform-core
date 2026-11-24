package com.documentplatform.licensing.issue;

import com.documentplatform.licensing.LastSeenReset;
import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.LicenceVerifier;
import com.documentplatform.licensing.TrustedKeys;
import com.documentplatform.licensing.json.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** The issuing command against a real Vault (dev mode, Testcontainers): sign, verify, rotate, refuse. */
class VaultTransitSignerTest {

    @TempDir
    Path dir;
    private Path records;
    private final Map<String, String> env = new HashMap<>();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-21T10:00:00Z"), ZoneOffset.UTC);
    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final List<String> logLines = new ArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord r) {
            logLines.add(new java.util.logging.SimpleFormatter().formatMessage(r));
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private int counter;
    private String signerToken;

    @BeforeEach
    void setUp() {
        records = dir.resolve("issued.jsonl");
        signerToken = VaultDev.token("licensing-signer");
        env.put("VAULT_ADDR", VaultDev.address());
        env.put("VAULT_TOKEN", signerToken);
        Logger.getLogger("com.documentplatform").addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger("com.documentplatform").removeHandler(capture);
    }

    private int run(VaultTransitSigner.PasswordPrompt prompt, List<String> args) {
        outBytes.reset();
        errBytes.reset();
        return new IssueLicenceCommand(o -> VaultTransitSigner.from(o, env, prompt), clock, new SecureRandom())
                .run(args, new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                        new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    }

    private int run(List<String> args) {
        return run(user -> null, args);
    }

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    private Path nextOut() {
        return dir.resolve("licence-" + (++counter) + ".json");
    }

    private List<String> issue(Path out, String keyId) {
        return new ArrayList<>(List.of("--customer", "Example Customer Ltd", "--products", "PRODUCT_A",
                "--kind", "PRODUCTION", "--starts", "2026-12-01", "--expires", "2027-11-30", "--deployment", "ON_PREM",
                "--slot", "PRODUCT_A:PAGES_PER_MONTH=10000", "--key-id", keyId, "--records", records.toString(),
                "--out", out.toString()));
    }

    private TrustedKeys export(String names) throws IOException {
        Path keys = dir.resolve("trusted-keys-" + (++counter) + ".json");
        assertEquals(0, run(List.of("--export-trusted-keys", names, "--out", keys.toString(),
                "--run-log", dir.resolve("export.runs.log").toString())), err());
        return TrustedKeys.parse(Files.readString(keys));
    }

    private Path runLog() {
        return records.resolveSibling(records.getFileName() + ".runs.log");
    }

    private Map<String, Object> lastRunLogEntry() throws IOException {
        List<String> lines = Files.readAllLines(runLog());
        return Json.parseObject(lines.get(lines.size() - 1));
    }

    @Test
    void signsInVaultAndTheLicenceVerifiesWithTheExportedPublicKey() throws Exception {
        Path out = nextOut();
        assertEquals(0, run(issue(out, "lic-2026")), err());
        assertTrue(out().contains("key lic-2026:1"), out());

        TrustedKeys trusted = export("lic-2026");
        assertEquals(java.util.Set.of("lic-2026:1"), trusted.keyIds());
        Licence l = new LicenceVerifier(trusted).verify(Files.readAllBytes(out), null);
        assertEquals("lic-2026:1", l.keyId(), "key ID = Transit key name + version");

        Map<String, Object> entry = lastRunLogEntry();
        assertEquals("ISSUED", entry.get("outcome"));
        assertEquals("lic-2026:1", entry.get("keyId"));
        assertEquals("vault-transit", entry.get("signer"));
        assertTrue(entry.get("signerAuditRefs") instanceof List<?> refs && refs.size() == 1,
                "the Vault request ID, to match Vault's audit log: " + entry);
        assertTrue(Files.readString(records).contains("\"keyId\":\"lic-2026:1\""));

        // The keys go into the product's build (P045, SEC-12), not into an installation's config.
        assertTrue(out().contains("META-INF/platform/licence-keys.json"), out());
    }

    @Test
    void aResetIsSignedInVaultToo() throws Exception {
        assertEquals(0, run(issue(nextOut(), "lic-2026:1")), err());
        String tenant = new LicenceVerifier(export("lic-2026")).verify(Files.readAllBytes(dir.resolve("licence-1.json")),
                null).tenantId();
        Path reset = dir.resolve("reset.json");
        assertEquals(0, run(List.of("--reset-last-seen", "2026-11-21T10:00:00Z", "--tenant-id", tenant,
                "--key-id", "lic-2026", "--records", records.toString(), "--out", reset.toString())), err());
        LastSeenReset r = LastSeenReset.verify(Files.readAllBytes(reset), export("lic-2026"), tenant);
        assertEquals("lic-2026:1", r.keyId());
    }

    @Test
    void afterARotationNewLicencesUseTheNewVersionAndOldOnesStillVerify() throws Exception {
        Path before = nextOut();
        assertEquals(0, run(issue(before, "lic-rot")), err());
        VaultDev.root("POST", "/v1/transit/keys/lic-rot/rotate", "{}");

        Path after = nextOut();
        assertEquals(0, run(issue(after, "lic-rot")), err());
        Path pinned = nextOut();
        assertEquals(0, run(issue(pinned, "lic-rot:1")), err());

        TrustedKeys trusted = export("lic-rot");
        assertEquals(java.util.Set.of("lic-rot:1", "lic-rot:2"), trusted.keyIds());
        LicenceVerifier verifier = new LicenceVerifier(trusted);
        assertEquals("lic-rot:1", verifier.verify(Files.readAllBytes(before), null).keyId());
        assertEquals("lic-rot:2", verifier.verify(Files.readAllBytes(after), null).keyId());
        assertEquals("lic-rot:1", verifier.verify(Files.readAllBytes(pinned), null).keyId());

        // An app that only ships version 1 still accepts the old licence and refuses the new one by key ID.
        TrustedKeys onlyV1 = TrustedKeys.of(Map.of("lic-rot:1", trusted.key("lic-rot:1").orElseThrow()));
        new LicenceVerifier(onlyV1).verify(Files.readAllBytes(before), null);
        var refused = assertThrows(com.documentplatform.licensing.LicenceException.class,
                () -> new LicenceVerifier(onlyV1).verify(Files.readAllBytes(after), null));
        assertEquals(com.documentplatform.licensing.LicenceRefusal.LICENCE_UNKNOWN_KEY_ID, refused.code());
    }

    @Test
    void aTokenWithoutThePolicyIsRefusedWithItsOwnCode() throws Exception {
        env.put("VAULT_TOKEN", VaultDev.token("default"));
        Path out = nextOut();
        assertEquals(1, run(issue(out, "lic-2026:1")));
        assertTrue(err().startsWith("ISSUE_VAULT_PERMISSION_DENIED: "), err());
        assertFalse(Files.exists(out));
        assertEquals("ISSUE_VAULT_PERMISSION_DENIED", lastRunLogEntry().get("code"));
        assertFalse(Files.exists(records) && Files.size(records) > 0, "nothing recorded");
    }

    @Test
    void thePolicyAllowsSigningAndReadingPublicKeysOnly() {
        assertEquals(200, VaultDev.call(signerToken, "GET", "/v1/transit/keys/lic-2026", null).status());
        assertEquals(403, VaultDev.call(signerToken, "GET", "/v1/transit/export/signing-key/lic-2026", null).status());
        assertEquals(403, VaultDev.call(signerToken, "GET", "/v1/transit/backup/lic-2026", null).status());
        assertEquals(403, VaultDev.call(signerToken, "POST", "/v1/transit/keys/lic-2026/rotate", "{}").status());
        assertEquals(403, VaultDev.call(signerToken, "POST", "/v1/transit/keys/lic-2026/config",
                "{\"exportable\":true}").status());
        assertEquals(403, VaultDev.call(signerToken, "POST", "/v1/transit/keys/lic-new", "{\"type\":\"ed25519\"}")
                .status());
        assertEquals(403, VaultDev.call(signerToken, "POST", "/v1/transit/sign/other-key",
                "{\"input\":\"aGk=\"}").status());
    }

    @Test
    void theInstallGuideShowsExactlyThePolicyTested() throws Exception {
        String guide = Files.readString(Path.of("..", "docs", "install", "licence-signing.md")).replace("\r\n", "\n");
        assertTrue(guide.contains(VaultDev.POLICY.replace("\r\n", "\n").strip()),
                "docs/install/licence-signing.md must show the policy in src/test/resources/vault/licensing-signer.hcl");
    }

    @Test
    void anUnreachableVaultHasItsOwnCode() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        env.put("VAULT_ADDR", "http://127.0.0.1:" + port);
        assertEquals(1, run(issue(nextOut(), "lic-2026:1")));
        assertTrue(err().startsWith("ISSUE_VAULT_UNREACHABLE: "), err());
        assertEquals("ISSUE_VAULT_UNREACHABLE", lastRunLogEntry().get("code"));
    }

    @Test
    void noTokenAndNoLoginIsRefused() {
        env.remove("VAULT_TOKEN");
        assertEquals(1, run(issue(nextOut(), "lic-2026:1")));
        assertTrue(err().startsWith("ISSUE_VAULT_NO_TOKEN: "), err());
    }

    @Test
    void aNamedUserLogsInAndTheLoginTokenIsRevokedAfterTheRun() throws Exception {
        env.remove("VAULT_TOKEN");
        int before = accessors();
        Path out = nextOut();
        List<String> args = issue(out, "lic-2026");
        args.addAll(List.of("--vault-login", "userpass", "--vault-user", VaultDev.USER));
        assertEquals(0, run(user -> VaultDev.PASSWORD.toCharArray(), args), err());
        assertTrue(Files.exists(out));
        assertEquals(before, accessors(), "the login token was revoked when the run ended");

        assertEquals(1, run(user -> "wrong-password".toCharArray(), with(issue(nextOut(), "lic-2026"),
                "--vault-login", "userpass", "--vault-user", VaultDev.USER)));
        assertTrue(err().startsWith("ISSUE_VAULT_LOGIN_REFUSED: "), err());
    }

    @Test
    void keysThatCouldLeaveVaultOrAreNotEd25519AreRefused() {
        assertEquals(1, run(issue(nextOut(), "lic-exp")));
        assertTrue(err().startsWith("ISSUE_KEY_EXPORTABLE: "), err());
        assertEquals(1, run(issue(nextOut(), "lic-ecdsa")));
        assertTrue(err().startsWith("ISSUE_KEY_INVALID: "), err());
        assertEquals(1, run(issue(nextOut(), "lic-missing")));
        assertTrue(err().startsWith("ISSUE_KEY_NOT_FOUND: "), err());
        assertEquals(1, run(issue(nextOut(), "lic-2026:9")));
        assertTrue(err().startsWith("ISSUE_KEY_NOT_FOUND: ") && err().contains("no version 9"), err());
        assertEquals(1, run(List.of("--export-trusted-keys", "lic-exp", "--out", dir.resolve("k.json").toString())));
        assertTrue(err().startsWith("ISSUE_KEY_EXPORTABLE: "), err());
        assertEquals(2, run(issue(nextOut(), "Lic 2026")), "a key ID of the wrong shape is an option error");
    }

    @Test
    void noTokenPasswordOrSignatureAppearsInAnyOutput() throws Exception {
        List<String> everything = new ArrayList<>();
        Path out = nextOut();
        assertEquals(0, run(issue(out, "lic-audit")), err());
        everything.add(out());
        everything.add(err());
        Path login = nextOut();
        env.remove("VAULT_TOKEN");
        assertEquals(0, run(user -> VaultDev.PASSWORD.toCharArray(), with(issue(login, "lic-audit"),
                "--vault-login", "userpass", "--vault-user", VaultDev.USER)), err());
        everything.add(out());
        everything.add(err());
        env.put("VAULT_TOKEN", VaultDev.token("default"));
        assertEquals(1, run(issue(nextOut(), "lic-audit")));
        everything.add(out());
        everything.add(err());
        env.put("VAULT_TOKEN", signerToken);
        Path keys = dir.resolve("keys.json");
        assertEquals(0, run(List.of("--export-trusted-keys", "lic-audit", "--out", keys.toString(),
                "--records", records.toString())), err());
        everything.add(out());
        everything.add(err());
        everything.add(Files.readString(runLog()));
        everything.add(Files.readString(records));
        everything.addAll(logLines);

        String signature = (String) ((Map<?, ?>) Json.parseObject(Files.readString(out)).get("signature")).get("value");
        for (String text : everything) {
            assertFalse(text.contains(signerToken), "signer token in: " + text);
            assertFalse(text.contains(VaultDev.ROOT_TOKEN), "root token in: " + text);
            assertFalse(text.contains(VaultDev.PASSWORD), "password in: " + text);
            assertFalse(text.contains(signature), "signature in: " + text);
            assertFalse(text.contains("hvs."), "a Vault token in: " + text);
        }
        assertTrue(logLines.stream().anyMatch(l -> l.contains("Signed with Vault Transit key lic-audit:1")), logLines.toString());
        // The licence file carries the signature (it must); nothing else does. No private key exists outside Vault.
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                String text = Files.readString(f);
                assertFalse(text.contains("PRIVATE KEY") || text.contains(signerToken), f.toString());
            }
        }
    }

    private static List<String> with(List<String> args, String... more) {
        List<String> l = new ArrayList<>(args);
        l.addAll(List.of(more));
        return l;
    }

    private static int accessors() {
        Map<String, Object> r = VaultDev.root("GET", "/v1/auth/token/accessors?list=true", null);
        return ((List<?>) VaultJson.object(r, "data").orElseThrow().get("keys")).size();
    }
}
