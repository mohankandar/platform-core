package com.documentplatform.monitoring;

import com.documentplatform.errors.FailureLog;
import com.documentplatform.errors.PlatformError;
import com.documentplatform.errors.SafeText;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** P032 B4: the diagnostic bundle holds what support needs and never a secret, token, content or field value. */
class DiagnosticBundleTest {

    static final String JWT = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJwZXJzb24ifQ.c2lnbmF0dXJlLXZhbHVl";
    static final String PEM = "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\n-----END PRIVATE KEY-----";
    static final String KEY = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789AbCdEfGh";
    static final String CONTENT = "%PDF-1.7 Loan agreement of Jane Example, account 12345678";
    static final String FIELD_VALUE = "SSN 123-45-6789";

    static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) out.put(e.getName(), in.readAllBytes());
        }
        return out;
    }

    @Test
    void secretsAndContentNeverReachTheBundle() throws Exception {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("spring.datasource.url", "jdbc:postgresql://db.corp.example:5432/app");
        config.put("spring.datasource.password", "Db-Password-Planted-1");
        config.put("platform.outbound.proxy.password-secret", "vault:kv/app/proxy#password");
        config.put("platform.notifications.mail.oauth.client-secret", "Client-Secret-Planted-2");
        config.put("app.api-key", KEY);
        config.put("app.token", JWT);
        config.put("app.welcome-text", "token=" + JWT + " and " + PEM);
        config.put("app.ocr.url", "http://ocr.internal:8080");
        config.put("app.note", "Bearer " + KEY);

        CheckResult r = new CheckResult("ocr", "services", false, CheckState.DOWN, MonitoringCode.MON_HTTP_UNREACHABLE,
                "The service ocr at http://ocr.internal:8080/health cannot be reached. password=Planted-3 " + JWT,
                "Check the service.", Instant.now(), Duration.ofMillis(12));
        PlatformError failure = FailureLog.recordError(MonitoringCode.MON_CHECK_FAILED, Map.of("check", "ocr",
                "error", "client_secret=Planted-4", "password", "Planted-5"));
        // Content in an exception's text and in a log line: the bundle takes neither (only codes and causes).
        CheckResult thrown;
        try (CheckRunner runner = new CheckRunner()) {
            thrown = runner.run(new com.documentplatform.monitoring.testkit.SwitchableCheck("parser", false)
                    .throwing(new IllegalStateException("could not parse " + CONTENT + " " + FIELD_VALUE)));
        }
        System.getLogger("app").log(System.Logger.Level.ERROR, "document text: " + CONTENT + " " + FIELD_VALUE);

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        DiagnosticBundle.Written w = DiagnosticBundle.build(sink, new DiagnosticBundle.Inputs(
                Map.of("app", "1.2.3", "app.build", "abc123"), config, List.of(r, thrown), List.of(failure),
                Map.of("identity", "4", "licensing", "1")));
        assertEquals(List.of("versions.txt", "system.txt", "configuration.properties", "checks.txt", "failures.txt",
                "schemas.txt", "manifest.txt"), w.entries());
        assertFalse(w.truncated());

        Map<String, byte[]> files = unzip(sink.toByteArray());
        String all = new String(sink.toByteArray(), StandardCharsets.ISO_8859_1);
        StringBuilder text = new StringBuilder();
        files.values().forEach(b -> text.append(new String(b, StandardCharsets.UTF_8)).append('\n'));
        for (String never : List.of("Db-Password-Planted-1", "Client-Secret-Planted-2", KEY, JWT, "MIIEvQ",
                "Planted-3", "Planted-4", "Planted-5", CONTENT, FIELD_VALUE, "Jane Example")) {
            assertFalse(text.toString().contains(never), never + " in the bundle");
            assertFalse(all.contains(never), never + " in the zip bytes");
        }
        String conf = new String(files.get("configuration.properties"), StandardCharsets.UTF_8);
        assertTrue(conf.contains("spring.datasource.url=jdbc:postgresql://db.corp.example:5432/app"), conf);
        assertTrue(conf.contains("spring.datasource.password=" + SafeText.REMOVED), conf);
        assertTrue(conf.contains("platform.outbound.proxy.password-secret=vault:kv/app/proxy#password"),
                "a secret reference (a name) stays: " + conf);
        assertTrue(conf.contains("app.ocr.url=http://ocr.internal:8080"), "addresses stay");
        assertTrue(new String(files.get("checks.txt"), StandardCharsets.UTF_8).contains("services/ocr DOWN "
                + "MON_HTTP_UNREACHABLE"));
        assertTrue(new String(files.get("failures.txt"), StandardCharsets.UTF_8).contains("ref=" + failure.reference()));
        assertTrue(new String(files.get("schemas.txt"), StandardCharsets.UTF_8).contains("identity=4"));
        assertTrue(new String(files.get("system.txt"), StandardCharsets.UTF_8).contains("java.version="));

        // The manifest holds the SHA-256 of each entry.
        String manifest = new String(files.get("manifest.txt"), StandardCharsets.UTF_8);
        for (String name : w.entries()) {
            if (name.equals("manifest.txt")) continue;
            String sum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(files.get(name)));
            assertTrue(manifest.contains("sha256 " + sum + "  " + name), manifest);
        }
    }

    @Test
    void aBundleHasASizeLimit() throws Exception {
        Map<String, String> big = new LinkedHashMap<>();
        for (int i = 0; i < 20_000; i++) big.put("app.setting." + i, "value-" + i);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        DiagnosticBundle.Written w = DiagnosticBundle.build(sink, new DiagnosticBundle.Inputs(Map.of(), big,
                List.of(), List.of(), Map.of()), 64 * 1024, Instant.now());
        assertTrue(w.truncated());
        assertFalse(w.entries().contains("configuration.properties"));
        String manifest = new String(unzip(sink.toByteArray()).get("manifest.txt"), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("MON_BUNDLE_TOO_LARGE"), manifest);
        assertTrue(sink.size() < 64 * 1024, "size " + sink.size());
    }
}
