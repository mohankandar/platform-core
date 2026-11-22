package com.documentplatform.integration.profile;

import com.documentplatform.integration.json.Json;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** The pinned copy is the file PINNED.md describes, and has what the profile relies on. */
class PinnedSpecTest {

    @Test
    void theCopyIsTheOnePinned() throws Exception {
        byte[] bytes;
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("profiles/sdv-ingestion-v1/sdv-ingestion-v1.openapi.json")) {
            bytes = in.readAllBytes();
        }
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        String pinned = Profiles.resource("sdv-ingestion-v1", "PINNED.md");
        Matcher m = Pattern.compile("SHA-256 of the file \\| `([0-9a-f]{64})`").matcher(pinned);
        assertTrue(m.find(), "PINNED.md names the SHA-256");
        assertEquals(m.group(1), sha, "the pinned copy was changed without updating PINNED.md");
        assertTrue(pinned.contains("76307d61e8c8f4029f5fdcf3161425a826c6d5d1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSpecHasTheBusyAndStorageAnswers() throws Exception {
        Map<String, Object> spec = Json.object(Profiles.resource("sdv-ingestion-v1", "sdv-ingestion-v1.openapi.json"));
        Map<String, Object> info = (Map<String, Object>) spec.get("info");
        assertEquals("1.0.0", info.get("version"));
        assertEquals("1.0.0", Profiles.load("sdv-ingestion-v1").apiVersion());
        Map<String, Object> answers = (Map<String, Object>) spec.get("x-sdv-answers");
        Map<String, Object> busy = (Map<String, Object>) answers.get("503 SDV-SYS-018");
        assertEquals(Boolean.TRUE, busy.get("retryAfter"), "the busy answer carries Retry-After");
        assertNotNull(answers.get("503 SDV-STO-004"));
    }
}
