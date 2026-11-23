package com.documentplatform.identity;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the validation of the 0.2.1 core records and the stable identity id. */
class CoreRecordsTest {

    @Test
    void stableIdentityIdIsDeterministicNameBasedAndTrimsItsInputs() {
        UUID id = StableIdentityId.fromIssuerAndSubject("https://idp.example.test/realms/acme", "user-1");
        // Golden value: actor ids are stored in audit records, so this must never change.
        assertEquals(UUID.fromString("ee47d7f1-9c8a-56ef-a2c1-bb219cdbb812"), id);
        assertEquals(id, StableIdentityId.fromIssuerAndSubject(" https://idp.example.test/realms/acme ", " user-1 "));
        assertEquals(5, id.version());
        assertEquals(2, id.variant());
    }

    @Test
    void stableIdentityIdRequiresIssuerAndSubject() {
        assertThrows(IllegalArgumentException.class, () -> StableIdentityId.fromIssuerAndSubject(null, "s"));
        assertThrows(IllegalArgumentException.class, () -> StableIdentityId.fromIssuerAndSubject("https://i", " "));
    }

    @Test
    void authenticatedIdentityRequiresCoreFieldsAndCopiesCollections() {
        Set<String> groups = new HashSet<>(Set.of("g1"));
        AuthenticatedIdentity identity = new AuthenticatedIdentity(" p ", " i ", " s ", " u ", " ", null, groups, Map.of("k", "v"));
        groups.add("g2");
        assertEquals("p", identity.providerId());
        assertEquals("u", identity.username());
        assertNull(identity.email());
        assertEquals(Set.of("g1"), identity.groups());
        assertThrows(IllegalArgumentException.class, () -> new AuthenticatedIdentity("p", "i", "s", " ", null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new AuthenticatedIdentity(null, "i", "s", "u", null, null, null, null));
    }

    @Test
    void authenticatedIdentityToStringShowsNoClaimsEmailNameOrGroups() {
        AuthenticatedIdentity identity = new AuthenticatedIdentity("provider-1", "https://idp.example.test/realms/a",
                "subject-1", "planted-username", "planted.person@corp.example", "Planted Display Name",
                Set.of("planted-group-name"), Map.of("planted_claim", "planted-claim-value", "phone", "555-0100-77"));
        String shown = identity.toString();
        for (String planted : new String[] {"planted-username", "planted.person@corp.example", "Planted Display Name",
                "planted-group-name", "planted_claim", "planted-claim-value", "555-0100-77"}) {
            assertFalse(shown.contains(planted), () -> "toString shows " + planted + ": " + shown);
        }
        assertTrue(shown.contains("provider-1"));
        assertTrue(shown.contains("https://idp.example.test/realms/a"));
        assertTrue(shown.contains("subject-1"));
        assertTrue(shown.contains("groups=1"));
    }

    @Test
    void identityClaimConfigDefaults() {
        IdentityClaimConfig config = new IdentityClaimConfig(" ", null, "", null);
        assertEquals("preferred_username", config.usernameClaim());
        assertEquals("email", config.emailClaim());
        assertEquals("name", config.displayNameClaim());
        assertEquals("groups", config.groupsClaim());
    }

    @Test
    void oidcConnectionBindingValidatesItsFields() {
        UUID c = UUID.randomUUID(), t = UUID.randomUUID();
        OidcConnectionBinding binding = new OidcConnectionBinding(c, t, " p ", URI.create("https://idp.test/realms/a"), " aud ", 1);
        assertEquals("p", binding.providerId());
        assertEquals("aud", binding.audience());
        assertThrows(IllegalArgumentException.class, () -> new OidcConnectionBinding(c, t, "p", URI.create("relative/path"), "aud", 1));
        assertThrows(IllegalArgumentException.class, () -> new OidcConnectionBinding(c, t, "p", URI.create("https://idp.test"), "aud", 0));
        assertThrows(IllegalArgumentException.class, () -> new OidcConnectionBinding(c, t, " ", URI.create("https://idp.test"), "aud", 1));
        assertThrows(NullPointerException.class, () -> new OidcConnectionBinding(null, t, "p", URI.create("https://idp.test"), "aud", 1));
    }

    @Test
    void connectionLifecycleAndProviderTypesAreStable() {
        assertEquals("DRAFT,VALIDATED,ACTIVE,DISABLED", String.join(",", java.util.Arrays.stream(IdentityConnectionStatus.values()).map(Enum::name).toList()));
        assertEquals("OIDC,SAML,LDAP_AD", String.join(",", java.util.Arrays.stream(IdentityProviderType.values()).map(Enum::name).toList()));
    }
}
