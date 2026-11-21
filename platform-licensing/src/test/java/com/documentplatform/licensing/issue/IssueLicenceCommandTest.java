package com.documentplatform.licensing.issue;

import com.documentplatform.licensing.AllowanceType;
import com.documentplatform.licensing.Ed25519;
import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.LicenceKind;
import com.documentplatform.licensing.LicenceType;
import com.documentplatform.licensing.LicenceVerifier;
import com.documentplatform.licensing.TrustedKeys;
import com.documentplatform.licensing.json.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;

class IssueLicenceCommandTest {

    private static final KeyPair KEY = Ed25519.generate();
    private static final String KEY_ID = "test-2026-01";
    private static final String PRIVATE_B64 = Ed25519.encodePrivate(KEY.getPrivate());

    @TempDir
    Path dir;
    private Path records;
    private final Map<String, String> env = new HashMap<>();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-21T10:00:00Z"), ZoneOffset.UTC);
    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private int counter;

    @BeforeEach
    void setUp() {
        records = dir.resolve("issued.jsonl");
        env.put(EnvironmentSigner.variableFor(KEY_ID), PRIVATE_B64);
    }

    private int run(RandomGenerator random, String... args) {
        return new IssueLicenceCommand(new EnvironmentSigner(env), clock, random)
                .run(Arrays.asList(args), new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                        new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    }

    private int run(String... args) {
        return run(new java.security.SecureRandom(), args);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private Path nextOut() {
        return dir.resolve("licence-" + (++counter) + ".json");
    }

    private List<String> base(Path out, String kind) {
        return new ArrayList<>(List.of("--customer", "Example Customer Ltd", "--products", "PRODUCT_A,PRODUCT_B",
                "--kind", kind, "--starts", "2026-12-01", "--expires", "2027-11-30", "--deployment", "ON_PREM",
                "--slot", "PRODUCT_A:PAGES_PER_MONTH=10000,PRODUCT_A:NAMED_SEATS=25",
                "--slot", "PRODUCT_B:STORAGE=500,PRODUCT_B:CONCURRENT_SEATS=5",
                "--key-id", KEY_ID, "--records", records.toString(), "--out", out.toString()));
    }

    private Licence read(Path file) throws Exception {
        return new LicenceVerifier(TrustedKeys.of(Map.of(KEY_ID, KEY.getPublic())))
                .verify(Files.readAllBytes(file), null);
    }

    private static List<String> with(List<String> args, String... more) {
        List<String> l = new ArrayList<>(args);
        l.addAll(List.of(more));
        return l;
    }

    private int run(List<String> args) {
        return run(args.toArray(String[]::new));
    }

    @Test
    void issuesASignedLicenceThatVerifies() throws Exception {
        Path out = nextOut();
        assertEquals(0, run(base(out, "PRODUCTION")), err());
        Licence l = read(out);
        assertEquals("Example Customer Ltd", l.customer());
        assertEquals(LicenceType.SUBSCRIPTION, l.type()); // the default
        assertEquals(LicenceKind.PRODUCTION, l.kind());
        assertEquals(1, l.version());
        assertEquals(2, l.entitySlots().size());
        assertEquals(10000, l.entitySlots().get(0).allowance("PRODUCT_A", AllowanceType.PAGES_PER_MONTH).orElseThrow().amount());
        assertTrue(l.tenantId().matches("TEN(-[0-9A-HJKMNP-TV-Z]{4}){4}"), l.tenantId());
        assertTrue(l.entitySlots().get(0).slotId().matches("ENT(-[0-9A-HJKMNP-TV-Z]{4}){4}"));
        assertTrue(l.licenceId().startsWith("LIC-"));
        assertEquals(Instant.parse("2026-11-21T10:00:00Z"), l.issuedAt());
        assertTrue(out().startsWith("Issued " + l.licenceId() + " v1 for tenant " + l.tenantId()), out());
    }

    @Test
    void recordsAndRunLogHoldNoKeyMaterialAndNoSignature() throws Exception {
        Path out = nextOut();
        assertEquals(0, run(base(out, "PRODUCTION")));
        assertNotEquals(0, run(with(base(nextOut(), "PRODUCTION"), "--tenant-id", "TEN-NOPE")));
        @SuppressWarnings("unchecked")
        String signature = (String) ((Map<String, Object>) Json.parseObject(Files.readString(out)).get("signature")).get("value");
        String recordText = Files.readString(records);
        String runLog = Files.readString(dir.resolve("issued.jsonl.runs.log"));
        for (String text : List.of(recordText, runLog, out(), err())) {
            assertFalse(text.contains(PRIVATE_B64));
            assertFalse(text.contains(PRIVATE_B64.substring(20, 40)));
            assertFalse(text.contains(signature));
            assertFalse(text.contains(signature.substring(0, 16)));
        }
        Licence l = read(out);
        Map<String, Object> rec = Json.parseObject(recordText.lines().findFirst().orElseThrow());
        assertEquals(l.licenceId(), rec.get("licenceId"));
        assertEquals(List.of(l.entitySlots().get(0).slotId(), l.entitySlots().get(1).slotId()), rec.get("slotIds"));
        List<String> runs = runLog.lines().toList();
        assertEquals(2, runs.size(), "every run is logged");
        assertEquals("ISSUED", Json.parseObject(runs.get(0)).get("outcome"));
        assertEquals("REFUSED", Json.parseObject(runs.get(1)).get("outcome"));
        assertEquals("ISSUE_UNKNOWN_TENANT", Json.parseObject(runs.get(1)).get("code"));
    }

    @Test
    void aNewVersionKeepsTheTenantAndChosenSlotsAndAddsNewOnes() throws Exception {
        Path first = nextOut();
        assertEquals(0, run(base(first, "PRODUCTION")));
        Licence v1 = read(first);
        String keep = v1.entitySlots().get(0).slotId();
        Path second = nextOut();
        List<String> args = List.of("--customer", "Example Customer Ltd", "--products", "PRODUCT_A",
                "--kind", "PRODUCTION", "--starts", "2027-12-01", "--expires", "2028-11-30", "--deployment", "ON_PREM",
                "--tenant-id", v1.tenantId(),
                "--slot", keep + "@PRODUCT_A:PAGES_PER_MONTH=20000",
                "--slot", "PRODUCT_A:PAGES_PER_MONTH=5000",
                "--key-id", KEY_ID, "--records", records.toString(), "--out", second.toString());
        assertEquals(0, run(args), err());
        Licence v2 = read(second);
        assertEquals(v1.tenantId(), v2.tenantId());
        assertEquals(2, v2.version());
        assertEquals(keep, v2.entitySlots().get(0).slotId());
        assertNotEquals(keep, v2.entitySlots().get(1).slotId());
        assertNotEquals(v1.entitySlots().get(1).slotId(), v2.entitySlots().get(1).slotId());
        assertNotEquals(v1.licenceId(), v2.licenceId());
    }

    @Test
    void installThenProductionShareATenantButNonProductionNeverDoes() throws Exception {
        Path install = nextOut();
        List<String> installArgs = base(install, "INSTALL");
        installArgs.set(installArgs.indexOf("--expires") + 1, "2027-01-29"); // 60 days inclusive
        assertEquals(0, run(installArgs), err());
        String tenant = read(install).tenantId();

        assertEquals(0, run(with(base(nextOut(), "PRODUCTION"), "--tenant-id", tenant)), err());
        assertEquals(1, run(with(base(nextOut(), "NON_PRODUCTION"), "--tenant-id", tenant)));
        assertTrue(err().contains("ISSUE_TENANT_KIND_MISMATCH"), err());

        Path test = nextOut();
        assertEquals(0, run(base(test, "NON_PRODUCTION")));
        Licence testLicence = read(test);
        assertNotEquals(tenant, testLicence.tenantId());
        assertTrue(testLicence.testEnvironment());
        assertEquals(1, run(with(base(nextOut(), "PRODUCTION"), "--tenant-id", testLicence.tenantId())));
    }

    @Test
    void anInstallLicenceRunsAtMostSixtyDays() {
        List<String> args = base(nextOut(), "INSTALL");
        args.set(args.indexOf("--expires") + 1, "2027-01-30"); // 61 days inclusive
        assertEquals(1, run(args));
        assertTrue(err().startsWith("ISSUE_INSTALL_TOO_LONG"), err());
    }

    @Test
    void perpetualLicences() throws Exception {
        Path out = nextOut();
        List<String> args = base(out, "PRODUCTION");
        int i = args.indexOf("--expires");
        args.set(i, "--updates-until");
        args.addAll(List.of("--type", "PERPETUAL"));
        assertEquals(0, run(args), err());
        Licence l = read(out);
        assertEquals(LicenceType.PERPETUAL, l.type());
        assertTrue(l.expiry().isEmpty());
        assertEquals("2027-11-30", l.updatesUntil().toString());

        List<String> both = with(base(nextOut(), "PRODUCTION"), "--type", "PERPETUAL", "--updates-until", "2028-01-01");
        assertEquals(2, run(both));
        assertTrue(err().contains("a perpetual licence never expires"), err());
    }

    @Test
    void refusalsHaveSpecificCodes() throws Exception {
        Path first = nextOut();
        assertEquals(0, run(base(first, "PRODUCTION")));
        Licence v1 = read(first);

        Map<String, List<String>> cases = new java.util.LinkedHashMap<>();
        cases.put("ISSUE_BAD_OPTION", with(base(nextOut(), "PRODUCTION"), "--colour", "red"));
        cases.put("ISSUE_MISSING_OPTION", List.of("--records", records.toString(), "--out", nextOut().toString()));
        cases.put("ISSUE_UNKNOWN_TENANT", with(base(nextOut(), "PRODUCTION"), "--tenant-id", "TEN-0000-0000-0000-0000"));
        List<String> otherCustomer = with(base(nextOut(), "PRODUCTION"), "--tenant-id", v1.tenantId());
        otherCustomer.set(1, "Someone Else");
        cases.put("ISSUE_TENANT_CUSTOMER_MISMATCH", otherCustomer);
        List<String> foreignSlot = base(nextOut(), "PRODUCTION");
        foreignSlot.set(foreignSlot.indexOf("--slot") + 1, v1.entitySlots().get(0).slotId() + "@PRODUCT_A:PAGES_PER_MONTH=1");
        cases.put("ISSUE_UNKNOWN_SLOT", foreignSlot); // a slot ID given without its tenant
        List<String> badProduct = base(nextOut(), "PRODUCTION");
        badProduct.set(badProduct.indexOf("--slot") + 1, "PRODUCT_C:PAGES_PER_MONTH=1");
        cases.put("ISSUE_INVALID_LICENCE", badProduct);
        List<String> badType = base(nextOut(), "PRODUCTION");
        badType.set(badType.indexOf("--slot") + 1, "PRODUCT_A:MINUTES=1");
        cases.put("ISSUE_BAD_OPTION ", badType);
        List<String> unknownKey = base(nextOut(), "PRODUCTION");
        unknownKey.set(unknownKey.indexOf("--key-id") + 1, "test-2099-01");
        cases.put("ISSUE_KEY_NOT_FOUND", unknownKey);
        cases.put("ISSUE_OUTPUT_EXISTS", base(first, "PRODUCTION"));

        for (Map.Entry<String, List<String>> c : cases.entrySet()) {
            errBytes.reset();
            int code = run(c.getValue());
            assertNotEquals(0, code, c.getKey());
            assertTrue(err().startsWith(c.getKey().strip() + ": "), c.getKey() + " -> " + err());
        }
        assertEquals(1, records.toFile().length() > 0 ? Files.readAllLines(records).size() : 0,
                "no refused run added a record");
    }

    @Test
    void anInvalidStoredKeyIsReportedWithoutItsValue() {
        env.put(EnvironmentSigner.variableFor(KEY_ID), "bm90LWEta2V5LXNlY3JldA==");
        assertEquals(1, run(base(nextOut(), "PRODUCTION")));
        assertTrue(err().startsWith("ISSUE_KEY_INVALID: "), err());
        assertFalse(err().contains("bm90LWEta2V5LXNlY3JldA"));
    }

    @Test
    void anUnreachableSecretStoreIsReported() {
        Signer down = (keyId, message) -> {
            throw new IllegalStateException("connection refused");
        };
        int code = new IssueLicenceCommand((Signer) down, clock, new java.security.SecureRandom())
                .run(base(nextOut(), "PRODUCTION"), new PrintStream(outBytes), new PrintStream(errBytes, true, StandardCharsets.UTF_8));
        assertEquals(1, code);
        assertTrue(err().startsWith("ISSUE_KEY_UNAVAILABLE: "), err());
    }

    @Test
    void idsAreUniqueAcrossManyCustomers() throws Exception {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 60; i++) {
            Path out = nextOut();
            assertEquals(0, run(base(out, i % 3 == 0 ? "NON_PRODUCTION" : "PRODUCTION")), err());
            Licence l = read(out);
            assertTrue(seen.add(l.tenantId()));
            assertTrue(seen.add(l.licenceId()));
            l.entitySlots().forEach(s -> assertTrue(seen.add(s.slotId())));
        }
        assertEquals(60 * 4, seen.size());
        assertEquals(seen, IssuedRecords.load(records).allIds());
    }

    @Test
    void aCollidingIdIsDrawnAgain() throws Exception {
        // Same seed twice: the second run draws exactly the IDs the first one issued, and must step past them.
        assertEquals(0, run(new Random(42), base(nextOut(), "PRODUCTION").toArray(String[]::new)));
        Path second = nextOut();
        assertEquals(0, run(new Random(42), base(second, "PRODUCTION").toArray(String[]::new)), err());
        Licence l = read(second);
        Set<String> all = IssuedRecords.load(records).allIds();
        assertEquals(8, all.size(), "8 distinct IDs over two licences");
        assertTrue(all.contains(l.tenantId()));
    }

    @Test
    void aRandomSourceThatOnlyRepeatsEndsWithACollisionCode() {
        RandomGenerator stuck = new RandomGenerator() {
            @Override
            public long nextLong() {
                return 0;
            }

            @Override
            public int nextInt(int bound) {
                return 0;
            }
        };
        // First licence: tenant TEN-0000..., then slot ENT-0000..., then the second slot collides for ever.
        assertEquals(1, run(stuck, base(nextOut(), "PRODUCTION").toArray(String[]::new)));
        assertTrue(err().startsWith("ISSUE_ID_COLLISION: "), err());
    }

    @Test
    void theIdGeneratorChecksAllPrefixes() {
        Set<String> taken = new HashSet<>();
        IdGenerator g = new IdGenerator(new Random(1));
        String a = g.next("TEN", taken);
        IdGenerator again = new IdGenerator(new Random(1));
        String b = again.next("TEN", taken);
        assertNotEquals(a, b);
        assertEquals(2, taken.size());
    }

    @Test
    void issuesASignedLastSeenResetForAKnownTenant() throws Exception {
        Path licence = nextOut();
        assertEquals(0, run(base(licence, "PRODUCTION")));
        String tenant = read(licence).tenantId();
        Path reset = dir.resolve("reset.json");
        assertEquals(0, run("--reset-last-seen", "2026-11-21T10:00:00Z", "--tenant-id", tenant, "--valid-days", "3",
                "--key-id", KEY_ID, "--records", records.toString(), "--out", reset.toString()), err());
        com.documentplatform.licensing.LastSeenReset r = com.documentplatform.licensing.LastSeenReset.verify(
                Files.readString(reset), TrustedKeys.of(Map.of(KEY_ID, KEY.getPublic())), tenant);
        assertEquals(Instant.parse("2026-11-21T10:00:00Z"), r.resetTo());
        assertEquals(Instant.parse("2026-11-24T10:00:00Z"), r.notAfter());
        assertTrue(r.resetId().startsWith("RST-"));
        assertTrue(out().contains("Issued last-seen reset " + r.resetId()), out());
        String runLog = Files.readString(dir.resolve("issued.jsonl.runs.log"));
        assertTrue(runLog.contains("\"outcome\":\"RESET_ISSUED\""), runLog);
        assertTrue(runLog.contains(r.resetId()), runLog);
        assertFalse(runLog.contains(PRIVATE_B64.substring(20, 40)));
    }

    @Test
    void resetRefusals() throws Exception {
        assertEquals(0, run(base(nextOut(), "PRODUCTION")));
        String[] common = {"--key-id", KEY_ID, "--records", records.toString()};
        assertEquals(1, run(with(List.of("--reset-last-seen", "2026-11-21T10:00:00Z", "--tenant-id", "TEN-NOPE",
                "--out", nextOut().toString()), common)));
        assertTrue(err().startsWith("ISSUE_UNKNOWN_TENANT: "), err());
        assertEquals(2, run(with(List.of("--reset-last-seen", "yesterday", "--tenant-id", "TEN-NOPE",
                "--out", nextOut().toString()), common)));
        assertEquals(2, run(with(List.of("--reset-last-seen", "2026-11-21T10:00:00Z", "--tenant-id", "TEN-NOPE",
                "--valid-days", "31", "--out", nextOut().toString()), common)));
        assertEquals(2, run(with(List.of("--reset-last-seen", "2026-11-21T10:00:00Z", "--tenant-id", "TEN-NOPE",
                "--customer", "X", "--out", nextOut().toString()), common)));
        assertTrue(err().contains("--customer is not used with --reset-last-seen"), err());
    }
}
