package com.documentplatform.identity.spring;

import com.documentplatform.identity.AuthenticatedIdentity;
import com.documentplatform.identity.IdentityClaimConfig;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Pins the 0.2.1 JWT normaliser. knownFinding_* tests record behaviour to be revisited in Phase 2. */
class OidcJwtIdentityNormalizerTest {

    private final OidcJwtIdentityNormalizer normalizer = new OidcJwtIdentityNormalizer("customer-oidc", new IdentityClaimConfig(null, null, null, null));

    private static Jwt.Builder token() {
        return Jwt.withTokenValue("t").header("alg", "none").issuer("https://idp.test/realms/a");
    }

    @Test
    void normalizesAVerifiedToken() {
        AuthenticatedIdentity identity = normalizer.normalize(token().subject("sub-1")
                .claim("preferred_username", "alice").claim("groups", List.of("g1", "g2")).build());
        assertEquals("customer-oidc", identity.providerId());
        assertEquals("https://idp.test/realms/a", identity.issuer());
        assertEquals("alice", identity.username());
        assertEquals(Set.of("g1", "g2"), identity.groups());
    }

    @Test
    void blankProviderIdDefaultsToOidc() {
        assertEquals("oidc", new OidcJwtIdentityNormalizer(" ", null).normalize(token().subject("s").build()).providerId());
    }

    @Test
    void knownFinding_tokenWithoutSubjectThrowsIllegalArgumentException() {
        // Callers must map this to a refusal (one product answers 403, another 401). Unmapped, it would be a 500.
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize(token().claim("preferred_username", "alice").build()));
    }

    @Test
    void tokenWithoutIssuerThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> normalizer.normalize(Jwt.withTokenValue("t").header("alg", "none").subject("s").build()));
    }
}
