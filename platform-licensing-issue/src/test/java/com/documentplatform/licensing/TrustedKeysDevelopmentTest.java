package com.documentplatform.licensing;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The optional {@code "development": true} marking of a trusted key (P031 B2). */
class TrustedKeysDevelopmentTest {

    @Test
    void aKeyMarkedDevelopmentIsADevelopmentKeyAndTheMarkSurvivesTheFile() {
        KeyPair dev = com.documentplatform.licensing.issue.SigningKeys.generate();
        KeyPair prod = com.documentplatform.licensing.issue.SigningKeys.generate();
        TrustedKeys keys = TrustedKeys.of(Map.of("dev-1", dev.getPublic(), "lic-1", prod.getPublic()), Set.of("dev-1"));
        assertTrue(keys.isDevelopmentKey("dev-1"));
        assertFalse(keys.isDevelopmentKey("lic-1"));
        assertFalse(keys.isDevelopmentKey("unknown"));

        TrustedKeys again = TrustedKeys.parse(keys.toJson());
        assertTrue(again.isDevelopmentKey("dev-1"));
        assertFalse(again.isDevelopmentKey("lic-1"));
        assertTrue(keys.toJson().contains("\"development\": true"));
    }

    @Test
    void olderFilesWithoutTheFieldLoadAndHaveNoDevelopmentKey() {
        KeyPair k = com.documentplatform.licensing.issue.SigningKeys.generate();
        String older = "{\"format\":\"platform-licence-keys/1\",\"keys\":[{\"algorithm\":\"Ed25519\",\"keyId\":\"lic-1\","
                + "\"publicKey\":\"" + Ed25519.encodePublic(k.getPublic()) + "\"}]}";
        TrustedKeys keys = TrustedKeys.parse(older);
        assertEquals(Set.of("lic-1"), keys.keyIds());
        assertFalse(keys.isDevelopmentKey("lic-1"));
        assertFalse(TrustedKeys.of(Map.of("lic-1", k.getPublic())).toJson().contains("development"));
    }

    @Test
    void developmentMustBeTrueOrFalseAndNameAListedKey() {
        KeyPair k = com.documentplatform.licensing.issue.SigningKeys.generate();
        String bad = "{\"format\":\"platform-licence-keys/1\",\"keys\":[{\"algorithm\":\"Ed25519\",\"keyId\":\"lic-1\","
                + "\"publicKey\":\"" + Ed25519.encodePublic(k.getPublic()) + "\",\"development\":\"yes\"}]}";
        assertThrows(IllegalArgumentException.class, () -> TrustedKeys.parse(bad));
        assertThrows(IllegalArgumentException.class,
                () -> TrustedKeys.of(Map.of("lic-1", k.getPublic()), Set.of("other")));
    }
}
