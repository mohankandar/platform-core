package com.documentplatform.identity.admission;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The library owns the reasons, the products own the words. Every string in the admission package must be free of
 * product words, so each product can map a {@link IdentityAdmissionException.Reason} to its own message.
 */
class NeutralMessagesTest {

    private static final Pattern STRING = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern PRODUCT_WORDS = Pattern.compile("(?i)\\btenant|platform operations|" + "lexa" + "vault|\\bs" + "dv\\b");

    @Test
    void admissionStringsUseNoProductWords() throws IOException {
        Path dir = Path.of("src/main/java/com/documentplatform/identity/admission");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher literal = STRING.matcher(lines.get(i));
                    while (literal.find()) {
                        if (PRODUCT_WORDS.matcher(literal.group(1)).find()) {
                            offenders.add(file.getFileName() + ":" + (i + 1) + " " + literal.group());
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), offenders);
    }

    @Test
    void eachRefusalCarriesItsOwnReason() {
        var roles = new TestRoleCatalog();
        var groups = new EntityGroupAuthorization(roles);
        var a = EntityId.of(java.util.UUID.randomUUID());
        assertReason(IdentityAdmissionException.Reason.GROUPS_NOT_A_COLLECTION, () -> groups.hasMatchingGroup("g", java.util.Map.of("g", java.util.Set.of("X"))));
        assertReason(IdentityAdmissionException.Reason.NO_ADMISSION_POLICY, () -> groups.hasMatchingGroup(java.util.Set.of("g"), java.util.Map.of()));
        assertReason(IdentityAdmissionException.Reason.UNKNOWN_ROLE, () -> groups.authorizedRoles(a, java.util.Set.of("g"), java.util.Map.of(a, java.util.Map.of("g", java.util.Set.of("BOGUS")))));
        assertReason(IdentityAdmissionException.Reason.PLATFORM_ROLE_IN_ENTITY_GRANT, () -> groups.authorizedRoles(a, java.util.Set.of("g"), java.util.Map.of(a, java.util.Map.of("g", java.util.Set.of(TestRoleCatalog.PLATFORM)))));
        assertReason(IdentityAdmissionException.Reason.NO_ROLE_FOR_ENTITY, () -> groups.authorizedRoles(a, java.util.Set.of("x"), java.util.Map.of(a, java.util.Map.of("g", java.util.Set.of("DOCUMENT_PROCESSOR")))));
        var preflight = new SharedIssuerPreflight(roles);
        assertReason(IdentityAdmissionException.Reason.NOT_ENABLED, () -> preflight.requireReady(true, false, java.util.Set.of(a), java.util.Map.of(a, java.util.Set.of("R"))));
        assertReason(IdentityAdmissionException.Reason.NO_VERIFIED_MEMBERSHIP, () -> preflight.requireReady(true, true, java.util.Set.of(a), java.util.Map.of()));
        assertReason(IdentityAdmissionException.Reason.MEMBERSHIP_NOT_BACKED, () -> preflight.requireReady(true, true, java.util.Set.of(a), java.util.Map.of(EntityId.of(java.util.UUID.randomUUID()), java.util.Set.of("R"))));
        assertReason(IdentityAdmissionException.Reason.INVALID_ROLE_GRANT, () -> preflight.requireReady(true, true, java.util.Set.of(a), java.util.Map.of(a, java.util.Set.of())));
        assertReason(IdentityAdmissionException.Reason.AMBIGUOUS_SELECTION, () -> new EntitySelection().select(null, java.util.Map.of()));
        assertReason(IdentityAdmissionException.Reason.INVALID_SELECTION, () -> new EntitySelection().select("x", java.util.Map.of()));
        assertReason(IdentityAdmissionException.Reason.NOT_AUTHORIZED_FOR_ENTITY, () -> new TrustedEntityAdmission().requireAuthorized(a, java.util.Set.of()));
        assertReason(IdentityAdmissionException.Reason.MACHINE_BINDING_NOT_UNIQUE, () -> new MachineEntityAdmission().requireUnique(java.util.Set.of()));
        var settings = new AdmissionSettings(true, true, "p", " ");
        assertReason(IdentityAdmissionException.Reason.CONTEXT_UNAVAILABLE, () -> new EntityMembershipResolver(k -> java.util.Map.of(), k -> java.util.Set.of(), roles, settings).authorizedEntities(java.net.URI.create("https://i"), java.util.Set.of()));
        var ok = new AdmissionSettings(true, true, "p", "aud");
        assertReason(IdentityAdmissionException.Reason.POLICY_UNAVAILABLE, () -> new EntityMembershipResolver(k -> null, k -> java.util.Set.of(), roles, ok).authorizedEntities(java.net.URI.create("https://i"), java.util.Set.of()));
    }

    private static void assertReason(IdentityAdmissionException.Reason expected, Runnable call) {
        IdentityAdmissionException refused = org.junit.jupiter.api.Assertions.assertThrows(IdentityAdmissionException.class, call::run);
        assertEquals(expected, refused.reason());
    }
}
