package com.documentplatform.identity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins IdentityClaims behaviour as of 0.2.1. Tests named knownFinding_* record behaviour that is considered
 * wrong and is to be fixed in Phase 2; until then the products must handle it (see the library README).
 */
class IdentityClaimsTest {

    private static final IdentityClaimConfig DEFAULTS = new IdentityClaimConfig(null, null, null, null);

    @Test
    void normalizesStandardClaims() {
        AuthenticatedIdentity identity = IdentityClaims.normalize("oidc", "https://idp.test", "sub-1",
                Map.of("preferred_username", " alice ", "email", " alice@test ", "name", " Alice A ", "groups", List.of("g1", " g2 ")),
                DEFAULTS);

        assertEquals("oidc", identity.providerId());
        assertEquals("https://idp.test", identity.issuer());
        assertEquals("sub-1", identity.subject());
        assertEquals("alice", identity.username());
        assertEquals("alice@test", identity.email());
        assertEquals("Alice A", identity.displayName());
        assertEquals(Set.of("g1", "g2"), identity.groups());
    }

    @Test
    void usernameFallsBackToSubjectWhenTheUsernameClaimIsMissingOrNotAString() {
        assertEquals("sub-1", IdentityClaims.normalize("oidc", "https://idp.test", "sub-1", Map.of(), DEFAULTS).username());
        assertEquals("sub-1", IdentityClaims.normalize("oidc", "https://idp.test", "sub-1", Map.of("preferred_username", 42), DEFAULTS).username());
    }

    @Test
    void configuredClaimNamesAreUsed() {
        IdentityClaimConfig config = new IdentityClaimConfig("upn", "mail", "display", "roles");
        AuthenticatedIdentity identity = IdentityClaims.normalize("oidc", "https://idp.test", "sub-1",
                Map.of("upn", "bob", "mail", "bob@test", "display", "Bob", "roles", List.of("r1")), config);
        assertEquals("bob", identity.username());
        assertEquals("bob@test", identity.email());
        assertEquals("Bob", identity.displayName());
        assertEquals(Set.of("r1"), identity.groups());
    }

    @Test
    void nullAndBlankGroupValuesAreDropped() {
        List<Object> raw = new ArrayList<>();
        raw.add(null);
        raw.add(" ");
        raw.add("g1");
        assertEquals(Set.of("g1"), IdentityClaims.values(raw));
        assertEquals(Set.of(), IdentityClaims.values(null));
    }

    @Test
    void groupValuesAreTrimmedButNotCaseFoldedOrSlashStripped() {
        assertEquals(Set.of("/Group-A", "group-a"), IdentityClaims.values(List.of(" /Group-A ", "group-a")));
    }

    @Test
    void knownFinding_plainStringGroupsClaimIsSplitOnSpacesAndCommas() {
        // A provider sending one string "Business Users, Auditors" yields three groups, not two.
        assertEquals(Set.of("Business", "Users", "Auditors"), IdentityClaims.values("Business Users, Auditors"));
    }

    @Test
    void knownFinding_moreThan200GroupsThrowsIllegalArgumentException() {
        List<String> groups = new ArrayList<>();
        for (int i = 0; i < 201; i++) groups.add("g" + i);
        // Callers must map this to a refusal themselves (one product answers 403, another 401). Unmapped, it would be a 500.
        assertThrows(IllegalArgumentException.class, () -> IdentityClaims.values(groups));
        groups.remove(200);
        assertEquals(200, IdentityClaims.values(groups).size());
    }

    @Test
    void knownFinding_aGroupValueLongerThan200CharactersThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> IdentityClaims.values(List.of("x".repeat(201))));
        assertEquals(1, IdentityClaims.values(List.of("x".repeat(200))).size());
    }

    @Test
    void missingSubjectIsRejectedByTheIdentityRecord() {
        assertThrows(IllegalArgumentException.class, () -> IdentityClaims.normalize("oidc", "https://idp.test", null, Map.of(), DEFAULTS));
        assertThrows(IllegalArgumentException.class, () -> IdentityClaims.normalize("oidc", "https://idp.test", " ", Map.of(), DEFAULTS));
    }

    @Test
    void nullConfigAndNullClaimsUseDefaults() {
        AuthenticatedIdentity identity = IdentityClaims.normalize("oidc", "https://idp.test", "sub-1", null, null);
        assertEquals("sub-1", identity.username());
        assertNull(identity.email());
        assertEquals(Set.of(), identity.groups());
    }
}
