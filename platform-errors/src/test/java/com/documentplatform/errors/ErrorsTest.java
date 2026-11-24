package com.documentplatform.errors;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** P032 B1, B3: the code shape, errors, references and the failure log keep secrets and content out. */
class ErrorsTest {

    /** A made-up code family; its texts are in SampleCode.codes.properties (test resources). */
    enum SampleCode implements PlatformCode {
        SAMPLE_DISK_FULL, SAMPLE_REFUSED, SAMPLE_CONFIG_BAD, SAMPLE_CONFIG_WORSE, SAMPLE_NO_TEXTS, SAMPLE_CONFIG_DEEP_ONE,
        SAMPLE_NEEDS_PERSON, SAMPLE_INFO, SAMPLE_NO_MESSAGE, SAMPLE_NO_FIX, SAMPLE_GAP, SAMPLE_SLOT, SAMPLE_INTERNALS
    }

    static final String JWT = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJwZXJzb24ifQ.c2lnbmF0dXJlLXZhbHVl";
    static final String PEM = "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\n-----END PRIVATE KEY-----";
    static final String KEY = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789AbCdEf";

    @Test
    void textsComeFromTheFamilyFileWithCodePrefixAndFamilyDefaults() {
        PlatformCode c = SampleCode.SAMPLE_DISK_FULL;
        assertEquals("SAMPLE_DISK_FULL", c.code());
        assertEquals(PlatformCode.Kind.FAILED, c.kind());
        assertEquals("The file could not be saved. Please try again later.", c.userMessage());
        assertEquals("The folder {folder} has no space left.", c.cause());
        assertEquals("Free space in the folder, or move it to a larger disk.", c.fix());
        assertEquals("Ask IT to free space.", c.supportNote());
        assertTrue(c.httpStatus().isEmpty());
        assertEquals("ErrorsTest.SampleCode", c.family());
        assertEquals(PlatformCode.Kind.REFUSED, SampleCode.SAMPLE_REFUSED.kind());
        assertEquals(403, SampleCode.SAMPLE_REFUSED.httpStatus().getAsInt());
        // Prefix defaults: SAMPLE_CONFIG_* gives kind and fix; the code's own text wins over the prefix.
        assertEquals(PlatformCode.Kind.REFUSED, SampleCode.SAMPLE_CONFIG_BAD.kind());
        assertEquals("Correct the setting.", SampleCode.SAMPLE_CONFIG_BAD.fix());
        assertEquals("Correct it twice.", SampleCode.SAMPLE_CONFIG_WORSE.fix());
        assertEquals("", SampleCode.SAMPLE_NO_TEXTS.cause(), "missing texts are empty; the catalogue refuses them");
        Catalogue cat = Catalogue.of(List.of(SampleCode.class));
        assertTrue(cat.problems().contains("platform-errors/ErrorsTest.SampleCode/SAMPLE_NO_TEXTS: no cause"),
                cat.problems().toString());
        assertTrue(cat.markdown().contains("| `SAMPLE_DISK_FULL` | ErrorsTest.SampleCode | FAILED |"));
        assertTrue(cat.json().contains("\"code\": \"SAMPLE_REFUSED\", \"kind\": \"REFUSED\""), cat.json());
    }

    /** error-codes-13, -18, xc-codes-setup-1, error-codes-6 (P037): longest prefix; what the check refuses. */
    @Test
    void theCatalogueCheckRefusesMissingTextsGapsSlotsAndInternals() {
        assertEquals("Correct the deep setting.", SampleCode.SAMPLE_CONFIG_DEEP_ONE.fix(), "the longest prefix wins");
        assertEquals("Correct it.", SampleCode.SAMPLE_CONFIG_DEEP_ONE.userMessage(), "a shorter prefix fills the rest");
        List<String> p = Catalogue.of(List.of(SampleCode.class)).problems();
        String f = "platform-errors/ErrorsTest.SampleCode/";
        assertTrue(p.contains(f + "SAMPLE_NO_MESSAGE: no message"), p.toString());
        assertTrue(p.contains(f + "SAMPLE_NO_FIX: no fix"), p.toString());
        assertTrue(p.contains(f + "SAMPLE_GAP: the cause has a gap or a %s slot: The settings () are missing."),
                p.toString());
        assertTrue(p.contains(f + "SAMPLE_GAP: the fix has a gap or a %s slot: Set , and in the environment."),
                p.toString());
        assertTrue(p.contains(f + "SAMPLE_SLOT: the cause has a gap or a %s slot: %s could not be reached."),
                p.toString());
        assertTrue(p.contains(f + "SAMPLE_INTERNALS: the message names internals: An SQLException was thrown."),
                p.toString());
        for (String fine : List.of("SAMPLE_DISK_FULL", "SAMPLE_REFUSED", "SAMPLE_NEEDS_PERSON", "SAMPLE_INFO")) {
            assertTrue(p.stream().noneMatch(x -> x.startsWith(f + fine + ":")), fine + " " + p);
        }
    }

    /** error-codes-19 (P037): one code name in two modules is refused. */
    @Test
    void aCodeNameInTwoModulesIsRefused() throws Exception {
        java.lang.reflect.Constructor<Catalogue> c = Catalogue.class.getDeclaredConstructor(List.class);
        c.setAccessible(true);
        Catalogue twice = c.newInstance(List.of(
                new Catalogue.Entry("platform-a", "ACode", "SAME_NAME", PlatformCode.Kind.FAILED, "m", "c", "f", "s", null),
                new Catalogue.Entry("platform-b", "BCode", "SAME_NAME", PlatformCode.Kind.FAILED, "m", "c", "f", "s", null)));
        assertEquals(List.of("SAME_NAME: used in two modules (platform-a, platform-b)"), twice.problems());
    }

    @Test
    void anErrorFillsSafeDetailsAndNeverHoldsSecretsOrContent() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("folder", "/var/app/export");
        details.put("password", "hunter2-Secret!");
        details.put("token", JWT);
        details.put("note", "Authorization: Bearer " + KEY + " and " + PEM + " and client_secret=s3cr3t-value");
        details.put("secretRef", "vault:kv/app/proxy#password");
        PlatformError e = PlatformError.of(SampleCode.SAMPLE_DISK_FULL, details);
        assertEquals("The folder /var/app/export has no space left.", e.cause());
        assertTrue(ReferenceId.isReference(e.reference()), e.reference());
        assertEquals("The file could not be saved. Please try again later. (reference " + e.reference() + ")",
                e.userMessage());
        assertEquals("code=SAMPLE_DISK_FULL ref=" + e.reference() + " cause=\"The folder /var/app/export has no "
                + "space left.\"", e.logLine());
        String all = e + " " + e.details() + " " + e.userMessage() + " " + e.logLine();
        for (String never : List.of("hunter2", JWT, "MIIEvQ", KEY, "s3cr3t-value")) {
            assertFalse(all.contains(never), never + " in " + all);
        }
        assertEquals("vault:kv/app/proxy#password", e.details().get("secretRef"), "a reference's name stays");
        assertEquals(SafeText.REMOVED, e.details().get("password"));
    }

    @Test
    void aPasswordInAUrlsUserPartNeverReachesAnError() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("folder", "redis://cache-user:Url-Pw-Planted-1@cache.corp.example:6379/0");
        details.put("note", "tried jdbc:postgresql://app:Url-Pw-Planted-2@db.corp.example:5432/app and "
                + "https://Url-Token-Planted-3@git.corp.example/repo.git");
        PlatformError e = PlatformError.of(SampleCode.SAMPLE_DISK_FULL, details);
        String all = e + " " + e.details() + " " + e.cause() + " " + e.logLine();
        for (String never : List.of("Url-Pw-Planted-1", "Url-Pw-Planted-2", "Url-Token-Planted-3", "cache-user")) {
            assertFalse(all.contains(never), never + " in " + all);
        }
        assertEquals("redis://" + SafeText.REMOVED + "@cache.corp.example:6379/0", e.details().get("folder"),
                "the address stays, the user part goes");
        assertEquals("postgres://" + SafeText.REMOVED + "@h/db", SafeText.scrub("postgres://u:p@ss@h/db"),
                "a password holding an @ goes whole");
        assertEquals("https://h/a?mail=a@b.example", SafeText.scrub("https://h/a?mail=a@b.example"),
                "an @ after the host is not a user part");
    }

    @Test
    void safeTextKeepsNamesAndAddressesOfThings() {
        assertTrue(SafeText.secretName("platform.outbound.proxy.password"));
        assertTrue(SafeText.secretName("spring.datasource.password"));
        assertTrue(SafeText.secretName("app.api-key"));
        assertTrue(SafeText.secretName("client.secret"));
        assertFalse(SafeText.secretName("platform.outbound.proxy.password-secret"), "a reference, not the secret");
        assertFalse(SafeText.secretName("platform.crypto.key-prefix"));
        assertFalse(SafeText.secretName("platform.outbound.proxy.host"));
        assertFalse(SafeText.secretName("monkey"));
        assertEquals("https://vault.corp.example:8200/v1/transit", SafeText.scrub("https://vault.corp.example:8200/v1/transit"));
        assertEquals("sha " + "a1".repeat(32), SafeText.scrub("sha " + "a1".repeat(32)), "a hex hash is not a key");
        assertEquals("x " + SafeText.REMOVED, SafeText.scrub("x " + KEY));
        assertNull(SafeText.scrub(null));
    }

    @Test
    void referencesNeverRepeatWithinOneTenth() {
        try {
            ReferenceId.useClock(Clock.fixed(Instant.parse("2098-01-01T10:00:00Z"), ZoneOffset.UTC));
            String before = ReferenceId.next();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < 100_000; i++) {
                String r = ReferenceId.next();
                assertTrue(ReferenceId.isReference(r), r);
                assertTrue(before.substring(0, 9).compareTo(r.substring(0, 9)) <= 0, before + " before " + r);
                assertTrue(seen.add(r), "unique");
                before = r;
            }
        } finally {
            ReferenceId.useClock(Clock.systemUTC());
        }
    }

    @Test
    void referencesAreShortTimeOrderedAndUnguessable() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) assertTrue(seen.add(ReferenceId.next()), "unique");
        try {
            ReferenceId.useClock(Clock.fixed(Instant.parse("2099-01-01T10:00:00Z"), ZoneOffset.UTC));
            String a = ReferenceId.next();
            ReferenceId.useClock(Clock.fixed(Instant.parse("2099-01-01T10:00:01Z"), ZoneOffset.UTC));
            String b = ReferenceId.next();
            assertTrue(a.compareTo(b) < 0, a + " before " + b);
            assertEquals(14, a.length());
            assertTrue(ReferenceId.isReference(a));
            assertEquals(a.substring(0, 9), ReferenceId.next().substring(0, 9).replace(b.substring(0, 9), a.substring(0, 9)));
        } finally {
            ReferenceId.useClock(Clock.systemUTC());
        }
        assertFalse(ReferenceId.isReference("R-0000000000IL"), "no I or L");
    }

    @Test
    void theFailureLogWritesOneLineAndKeepsTheLastFailures() {
        FailureLog.clear();
        List<String> lines = new java.util.ArrayList<>();
        Logger logger = Logger.getLogger(FailureLog.class.getName());
        Handler h = new Handler() {
            @Override
            public void publish(LogRecord r) {
                lines.add(r.getLevel() + " " + r.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(h);
        try {
            String ref = FailureLog.record(SampleCode.SAMPLE_DISK_FULL, Map.of("folder", "/data", "pwd", "p@ss"));
            assertTrue(ReferenceId.isReference(ref));
            FailureLog.record(SampleCode.SAMPLE_REFUSED);
            assertEquals(List.of("SEVERE code=SAMPLE_DISK_FULL ref=" + ref + " cause=\"The folder /data has no space "
                    + "left.\"", "WARNING code=SAMPLE_REFUSED ref=" + FailureLog.recent(1).get(0).reference()
                    + " cause=\"Not allowed.\""), lines);
            assertEquals(2, FailureLog.recent(10).size());
            assertEquals(SampleCode.SAMPLE_REFUSED, FailureLog.recent(10).get(0).code(), "newest first");
            assertFalse(String.join("\n", lines).contains("p@ss"));

            // error-codes-33 (P037): NEEDS_PERSON logs as a warning, INFO as information.
            lines.clear();
            FailureLog.record(SampleCode.SAMPLE_NEEDS_PERSON);
            FailureLog.record(SampleCode.SAMPLE_INFO);
            assertEquals(List.of("WARNING", "INFO"), lines.stream().map(l -> l.substring(0, l.indexOf(' '))).toList(),
                    lines.toString());

            // error-codes-35 (P037): the last 200 are kept, the oldest dropped.
            FailureLog.clear();
            String first = FailureLog.record(SampleCode.SAMPLE_REFUSED);
            for (int i = 0; i < FailureLog.KEEP; i++) FailureLog.record(SampleCode.SAMPLE_INFO);
            List<PlatformError> kept = FailureLog.recent(FailureLog.KEEP + 10);
            assertEquals(200, kept.size());
            assertTrue(kept.stream().noneMatch(e -> e.reference().equals(first)), "the oldest is dropped");
        } finally {
            logger.removeHandler(h);
        }
    }
}
