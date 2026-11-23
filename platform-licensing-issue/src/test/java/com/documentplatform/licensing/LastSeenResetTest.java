package com.documentplatform.licensing;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static com.documentplatform.licensing.Fixtures.*;
import static com.documentplatform.licensing.ResetRefusal.*;
import static org.junit.jupiter.api.Assertions.*;

class LastSeenResetTest {

    private final Instant t0 = Instant.parse("2026-11-21T10:00:00Z");
    private final LastSeenReset reset = new LastSeenReset("RST-AAAA-BBBB", "TEN-0000-0000-0000-0001", t0,
            t0.plus(Duration.ofDays(7)), t0, KEY_ID_1);

    private ResetRefusal refusal(String file, TrustedKeys keys, String tenant) {
        return assertThrows(ResetException.class, () -> LastSeenReset.verify(file, keys, tenant)).code();
    }

    @Test
    void aSignedResetVerifies() {
        String file = com.documentplatform.licensing.issue.ResetSigner.sign(reset, KEY_1.getPrivate());
        assertTrue(file.contains("\"format\": \"platform-last-seen-reset/1\""));
        assertEquals(reset, LastSeenReset.verify(file, trustKey1(), "TEN-0000-0000-0000-0001"));
    }

    @Test
    void refusals() {
        String file = com.documentplatform.licensing.issue.ResetSigner.sign(reset, KEY_1.getPrivate());
        assertEquals(RESET_MISSING, refusal("", trustKey1(), null));
        assertEquals(RESET_MALFORMED, refusal("{", trustKey1(), null));
        assertEquals(RESET_UNSUPPORTED_FORMAT, refusal(file.replace("platform-last-seen-reset/1", "x/1"), trustKey1(), null));
        assertEquals(RESET_UNKNOWN_KEY_ID, refusal(com.documentplatform.licensing.issue.ResetSigner.sign(new LastSeenReset("RST-AAAA-BBBB", "TEN-0000-0000-0000-0001", t0,
                t0.plusSeconds(60), t0, KEY_ID_2),KEY_2.getPrivate()), trustKey1(), null));
        assertEquals(RESET_TAMPERED, refusal(file.replace("2026-11-21T10:00:00Z\",\n    \"tenantId", "2020-01-01T00:00:00Z\",\n    \"tenantId"), trustKey1(), null));
        assertEquals(RESET_TAMPERED, refusal(file.replace("2026-11-28", "2036-11-28"), trustKey1(), null));
        assertEquals(RESET_FORGED, refusal(com.documentplatform.licensing.issue.ResetSigner.sign(reset, com.documentplatform.licensing.issue.SigningKeys.generate().getPrivate()), trustKey1(), null));
        assertEquals(RESET_WRONG_CUSTOMER, refusal(file, trustKey1(), "TEN-9999"));
    }

    @Test
    void aLicenceFileIsNotAReset() {
        String licence = signed(subscription(java.time.LocalDate.parse("2026-01-01"), java.time.LocalDate.parse("2026-12-31")));
        assertEquals(RESET_UNSUPPORTED_FORMAT, refusal(licence, trustKey1(), null));
    }

    @Test
    void everySingleByteChangeIsRefused() {
        byte[] file = com.documentplatform.licensing.issue.ResetSigner.sign(reset, KEY_1.getPrivate()).getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < file.length; i++) {
            byte[] copy = file.clone();
            copy[i] ^= 0x01;
            int at = i;
            assertThrows(ResetException.class, () -> LastSeenReset.verify(copy, trustKey1(), "TEN-0000-0000-0000-0001"),
                    "byte " + at);
        }
    }
}
