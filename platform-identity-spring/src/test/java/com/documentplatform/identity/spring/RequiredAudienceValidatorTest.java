package com.documentplatform.identity.spring;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequiredAudienceValidatorTest {

    private static final String API = "product-a-api";
    private final RequiredAudienceValidator validator = new RequiredAudienceValidator(API);

    private static Jwt.Builder token() {
        return Jwt.withTokenValue("test-token").header("alg", "none").subject("user-1");
    }

    private static void assertMissingAudienceFailure(OAuth2TokenValidatorResult result) {
        assertTrue(result.hasErrors());
        assertEquals(1, result.getErrors().size());
        var error = result.getErrors().iterator().next();
        assertEquals("invalid_token", error.getErrorCode());
        assertEquals("Required API audience is missing.", error.getDescription());
    }

    @Test
    void tokenWithoutAudClaimFailsWithoutThrowing() {
        Jwt jwt = token().build();
        assertNull(jwt.getAudience(), "precondition: a token without aud yields a null audience list");

        OAuth2TokenValidatorResult result = assertDoesNotThrow(() -> validator.validate(jwt));

        assertMissingAudienceFailure(result);
    }

    @Test
    void tokenWithEmptyAudFails() {
        Jwt jwt = token().audience(List.of()).build();

        assertMissingAudienceFailure(validator.validate(jwt));
    }

    @Test
    void tokenWithOnlyAnotherAudienceFails() {
        Jwt jwt = token().audience(List.of("account")).build();

        assertMissingAudienceFailure(validator.validate(jwt));
    }

    @Test
    void tokenWithTheRequiredAudienceSucceeds() {
        Jwt jwt = token().audience(List.of(API)).build();

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertFalse(result.hasErrors());
    }

    @Test
    void tokenWithSeveralAudiencesIncludingTheRequiredOneSucceeds() {
        Jwt jwt = token().audience(List.of("account", API, "other-api")).build();

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertFalse(result.hasErrors());
    }
}
