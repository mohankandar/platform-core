package com.documentplatform.licensing;

import com.documentplatform.licensing.json.Json;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.documentplatform.licensing.Fixtures.*;
import static com.documentplatform.licensing.LicenceRefusal.*;
import static org.junit.jupiter.api.Assertions.*;

class LicenceVerifierTest {

    /** error-codes-15 (P037): a refusal's catalogue fix is its operator action, for every refusal. */
    @Test
    void everyRefusalsFixIsItsAction() {
        for (LicenceRefusal r : LicenceRefusal.values()) {
            assertFalse(r.action().isBlank(), r.name());
            assertEquals(r.action(), r.fix(), r.name());
        }
    }

    private static final LocalDate START = LocalDate.parse("2026-12-01");
    private static final LocalDate END = LocalDate.parse("2027-11-30");
    private final LicenceVerifier verifier = new LicenceVerifier(trustKey1());

    private static LicenceRefusal refusal(Runnable r) {
        return assertThrows(LicenceException.class, r::run).code();
    }

    @Test
    void aSignedLicenceVerifiesAndReadsBackEqual() {
        Licence licence = subscription(START, END);
        assertEquals(licence, verifier.verify(signed(licence), licence.tenantId()));
        assertEquals(licence, verifier.verify(signed(licence), null)); // tenant not yet known (first setup)
    }

    @Test
    void aPerpetualLicenceVerifies() {
        Licence licence = perpetual(START, END);
        assertEquals(licence, verifier.verify(signed(licence), null));
    }

    @Test
    void signingIsDeterministic() {
        Licence licence = subscription(START, END);
        assertEquals(signed(licence), signed(licence)); // Ed25519 is deterministic; same content, same file
    }

    @Test
    void missing() {
        assertEquals(LICENCE_MISSING, refusal(() -> verifier.verify((byte[]) null, null)));
        assertEquals(LICENCE_MISSING, refusal(() -> verifier.verify(new byte[0], null)));
        assertEquals(LICENCE_MISSING, refusal(() -> verifier.verify("  \n", null)));
    }

    @Test
    void malformed() {
        assertEquals(LICENCE_MALFORMED, refusal(() -> verifier.verify("not a licence", null)));
        assertEquals(LICENCE_MALFORMED, refusal(() -> verifier.verify("[]", null)));
        assertEquals(LICENCE_MALFORMED, refusal(() -> verifier.verify(new byte[]{(byte) 0xff, (byte) 0xfe}, null)));
        assertEquals(LICENCE_MALFORMED, refusal(() -> verifier.verify("{\"format\":\"platform-licence/1\"}", null)));
    }

    @Test
    void unsupportedFormatOrAlgorithm() {
        String file = signed(subscription(START, END));
        assertEquals(LICENCE_UNSUPPORTED_FORMAT,
                refusal(() -> verifier.verify(file.replace("platform-licence/1", "platform-licence/2"), null)));
        assertEquals(LICENCE_UNSUPPORTED_FORMAT,
                refusal(() -> verifier.verify(file.replace("\"Ed25519\"", "\"RS256\""), null)));
    }

    @Test
    void unknownKeyId() {
        Licence licence = subscription(LicenceKind.PRODUCTION, START, END, KEY_ID_2);
        LicenceException e = assertThrows(LicenceException.class, () -> verifier.verify(signed(licence), null));
        assertEquals(LICENCE_UNKNOWN_KEY_ID, e.code());
        assertTrue(e.getMessage().contains(KEY_ID_2));
    }

    @Test
    void tamperedWhenTheContentChanges() {
        String file = signed(subscription(START, END));
        String edited = file.replace("\"amount\": 10000", "\"amount\": 90000");
        assertNotEquals(file, edited);
        assertEquals(LICENCE_TAMPERED, refusal(() -> verifier.verify(edited, null)));
        assertEquals(LICENCE_TAMPERED, refusal(() -> verifier.verify(file.replace("2027-11-30", "2037-11-30"), null)));
    }

    @Test
    void tamperedWhenTheKeyIdIsSwappedToAnotherTrustedKey() {
        String file = signed(subscription(START, END));
        LicenceVerifier both = new LicenceVerifier(trustBoth());
        assertEquals(LICENCE_TAMPERED, refusal(() -> both.verify(file.replace(KEY_ID_1, KEY_ID_2), null)));
    }

    @Test
    void forgedWhenSignedByAnotherKeyUnderOurKeyId() {
        KeyPair attacker = Ed25519.generate();
        String forged = LicenceSigner.sign(subscription(START, END), attacker.getPrivate());
        assertEquals(LICENCE_FORGED, refusal(() -> verifier.verify(forged, null)));
    }

    @Test
    void forgedWhenTheSignatureValueIsChanged() {
        String file = signed(subscription(START, END));
        @SuppressWarnings("unchecked")
        Map<String, Object> sig = (Map<String, Object>) Json.parseObject(file).get("signature");
        String value = (String) sig.get("value");
        byte[] raw = Base64.getDecoder().decode(value);
        raw[10] ^= 0x01;
        String changed = file.replace(value, Base64.getEncoder().encodeToString(raw));
        assertEquals(LICENCE_FORGED, refusal(() -> verifier.verify(changed, null)));
    }

    @Test
    void wrongCustomer() {
        Licence licence = subscription(START, END);
        LicenceException e = assertThrows(LicenceException.class,
                () -> verifier.verify(signed(licence), "TEN-9999-9999-9999-9999"));
        assertEquals(LICENCE_WRONG_CUSTOMER, e.code());
        assertTrue(e.getMessage().contains("TEN-9999-9999-9999-9999"));
    }

    /** Any one byte changed anywhere in the file: the licence is refused, never accepted with other content. */
    @Test
    void everySingleByteChangeIsRefused() {
        Licence licence = subscription(START, END);
        byte[] file = signed(licence).getBytes(StandardCharsets.UTF_8);
        List<String> accepted = new ArrayList<>();
        for (int i = 0; i < file.length; i++) {
            byte[] copy = file.clone();
            copy[i] ^= 0x01;
            try {
                Licence read = verifier.verify(copy, licence.tenantId());
                if (!read.equals(licence)) {
                    fail("byte " + i + " changed the content and was accepted");
                }
                accepted.add(Integer.toString(i));
            } catch (LicenceException expected) {
                // refused: good
            }
        }
        assertEquals(List.of(), accepted, "byte changes accepted");
    }

    @Test
    void keyRotationOldLicencesKeepVerifying() {
        LicenceVerifier afterRotation = new LicenceVerifier(trustBoth());
        Licence old = subscription(LicenceKind.PRODUCTION, START, END, KEY_ID_1);
        Licence renewed = subscription(LicenceKind.PRODUCTION, START, END.plusYears(1), KEY_ID_2);
        assertEquals(old, afterRotation.verify(signed(old), null));
        assertEquals(renewed, afterRotation.verify(signed(renewed), null));
    }

    @Test
    void trustedKeysFileRoundTrips() {
        TrustedKeys keys = trustBoth();
        TrustedKeys read = TrustedKeys.parse(keys.toJson());
        assertEquals(keys.keyIds(), read.keyIds());
        assertEquals(KEY_1.getPublic(), read.key(KEY_ID_1).orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> TrustedKeys.parse("{\"format\":\"x\",\"keys\":[]}"));
    }

    @Test
    void refusalMessagesNeverContainTheSignatureOrKeys() {
        String file = signed(subscription(START, END));
        @SuppressWarnings("unchecked")
        String sig = (String) ((Map<String, Object>) Json.parseObject(file).get("signature")).get("value");
        String privateKey = Ed25519.encodePrivate(KEY_1.getPrivate());
        List<String> attempts = List.of(file.replace("10000", "10001"), file.replace(KEY_ID_1, "other"),
                file.replace("platform-licence/1", "x"), file.substring(0, file.length() / 2));
        for (String attempt : attempts) {
            LicenceException e = assertThrows(LicenceException.class, () -> verifier.verify(attempt, null));
            assertFalse(e.getMessage().contains(sig));
            assertFalse(e.getMessage().contains(sig.substring(0, 16)));
            assertFalse(e.getMessage().contains(privateKey));
            assertTrue(e.getMessage().startsWith(e.code().name() + ": "));
        }
    }

    @Test
    void theSignedContentMustBeAValidLicence() {
        // A payload that is signed correctly but breaks the model (a perpetual licence with an expiry) is malformed.
        Map<String, Object> payload = new java.util.LinkedHashMap<>(LicenceFormat.payload(perpetual(START, END)));
        payload.put("expiresOn", "2030-01-01");
        byte[] message = LicenceFormat.signedMessage(payload);
        byte[] sig;
        try {
            sig = Ed25519.sign(KEY_1.getPrivate(), message);
        } catch (java.security.InvalidKeyException e) {
            throw new AssertionError(e);
        }
        String file = Json.pretty(Map.of("format", LicenceFormat.FORMAT, "licence", payload, "signature",
                Map.of("algorithm", "Ed25519", "payloadSha256", Ed25519.sha256Hex(message),
                        "value", Base64.getEncoder().encodeToString(sig))));
        assertEquals(LICENCE_MALFORMED, refusal(() -> verifier.verify(file, null)));
    }
}
