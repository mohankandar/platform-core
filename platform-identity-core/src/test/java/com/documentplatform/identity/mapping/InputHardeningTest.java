package com.documentplatform.identity.mapping;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P045: client secrets in the shapes the old check missed (SEC-18), and text-direction characters in group names
 * (SEC-45). The secrets here are made up and match no system.
 */
class InputHardeningTest {

    @Test
    void theCommonShapesOfClientSecretsAreSeen() {
        for (String secret : List.of(
                "Abc8Q~dEfGhIjKlMnOpQrStUvWxYz0123456.7_8", // a cloud directory's client secret
                "xY27Q~abcdefghijklmnopqrstuvwxyz.0123_456",
                "GOCSPX-aBcDeFgHiJkLmNoPqRsTuVwXyZ01",
                "a~longer~value~than~thirty~characters~with~tildes")) {
            assertTrue(ClientBindingsFile.looksSecret(secret), secret);
        }
        for (String id : List.of("0oa1b2c3d4e5f6g7h8i9", "loan-system", "app-1",
                "3f2504e0-4f89-11d3-9a0c-0305e82c3301", "aBcDeFgHiJkLmNoPqRsTuVwXyZ012345")) {
            assertFalse(ClientBindingsFile.looksSecret(id), id + " is a client ID, not a secret");
        }
    }

    @Test
    void textDirectionCharactersInAGroupNameAreRefused() {
        for (String c : List.of("‪", "‮", "⁦", "⁩", "‎", "‏", "؜")) {
            assertTrue(RoleMappingFile.invisibleProblem("Readers" + c).orElse("").contains("text-direction"),
                    Integer.toHexString(c.charAt(0)));
        }
        assertEquals(java.util.Optional.empty(), RoleMappingFile.invisibleProblem("Finance Readers"));
        assertEquals(java.util.Optional.empty(), RoleMappingFile.invisibleProblem("مجموعة المالية"),
                "Arabic letters themselves are fine");
    }
}
