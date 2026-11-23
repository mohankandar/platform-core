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
        SAMPLE_DISK_FULL, SAMPLE_REFUSED, SAMPLE_CONFIG_BAD, SAMPLE_CONFIG_WORSE, SAMPLE_NO_TEXTS
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
    void referencesAreShortTimeOrderedAndUnguessable() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) assertTrue(seen.add(ReferenceId.next()), "unique");
        try {
            ReferenceId.useClock(Clock.fixed(Instant.parse("2026-11-22T10:00:00Z"), ZoneOffset.UTC));
            String a = ReferenceId.next();
            ReferenceId.useClock(Clock.fixed(Instant.parse("2026-11-22T10:00:01Z"), ZoneOffset.UTC));
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
        } finally {
            logger.removeHandler(h);
        }
    }
}
