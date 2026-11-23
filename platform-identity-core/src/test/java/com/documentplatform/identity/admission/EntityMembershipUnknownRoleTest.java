package com.documentplatform.identity.admission;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A grant with a role the product does not know (a typo, or a role removed from the product) refuses only its own
 * entity. Before 0.4.0 it refused every sign-in through the login system.
 */
class EntityMembershipUnknownRoleTest {
    private static final EntityId A = EntityId.of(UUID.fromString("00000000-0000-0000-0000-00000000000a"));
    private static final EntityId B = EntityId.of(UUID.fromString("00000000-0000-0000-0000-00000000000b"));
    private static final URI ISSUER = URI.create("https://identity.example.test/realms/shared");
    private static final ConnectionKey KEY = new ConnectionKey("customer-oidc", ISSUER, "product-api");
    private static final AdmissionSettings SETTINGS = new AdmissionSettings(true, true, "customer-oidc", "product-api");

    private static EntityMembershipResolver resolver(Map<EntityId, Map<String, Set<String>>> grants) {
        return new EntityMembershipResolver(key -> KEY.equals(key) ? grants : Map.of(),
                key -> KEY.equals(key) ? Set.of(A, B) : Set.of(), new TestRoleCatalog(), SETTINGS);
    }

    private static final Map<EntityId, Map<String, Set<String>>> A_BROKEN = Map.of(
            A, Map.of("a-staff", Set.of("DOCUMENT_PROCESOR"), "a-admins", Set.of("BUSINESS_ADMIN")),
            B, Map.of("b-staff", Set.of("DOCUMENT_PROCESSOR")));

    @Test
    void theBrokenEntityIsRefusedAndThePersonsOtherEntitiesAreAdmitted() {
        var admitted = resolver(A_BROKEN).authorizedEntities(ISSUER, Set.of("a-staff", "b-staff"));
        assertEquals(Map.of(B, Set.of("DOCUMENT_PROCESSOR")), admitted);
    }

    @Test
    void theErrorLineNamesTheEntityButNoGroupRoleOrClaim() {
        var grants = Map.of(
                A, Map.of("PLANTED-GROUP-NAME", Set.of("PLANTED_UNKNOWN_ROLE")),
                B, Map.of("b-staff", Set.of("DOCUMENT_PROCESSOR")));
        List<String> lines = new ArrayList<>();
        Logger logger = Logger.getLogger(EntityMembershipResolver.class.getName());
        Level before = logger.getLevel();
        Handler capture = new Handler() {
            private final SimpleFormatter format = new SimpleFormatter();

            @Override
            public void publish(LogRecord r) {
                lines.add(format.format(r));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        capture.setLevel(Level.ALL);
        logger.addHandler(capture);
        logger.setLevel(Level.ALL);
        try {
            var admitted = resolver(grants).authorizedEntities(ISSUER, Set.of("PLANTED-GROUP-NAME", "b-staff"));
            assertEquals(Map.of(B, Set.of("DOCUMENT_PROCESSOR")), admitted);
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(before);
        }
        String all = String.join("\n", lines);
        assertEquals(1, lines.size(), all);
        assertTrue(all.contains(A.toString()), all);
        for (String never : List.of("PLANTED-GROUP-NAME", "PLANTED_UNKNOWN_ROLE", "b-staff")) {
            assertFalse(all.contains(never), never + " in: " + all);
        }
    }

    @Test
    void choosingTheBrokenEntityIsRefused() {
        var resolver = resolver(A_BROKEN);
        var admitted = resolver.authorizedEntities(ISSUER, Set.of("a-staff", "b-staff"));
        var refused = assertThrows(IdentityAdmissionException.class, () -> resolver.select(A, admitted));
        assertEquals(IdentityAdmissionException.Reason.NOT_AUTHORIZED_FOR_ENTITY, refused.reason());
    }

    @Test
    void otherPeopleInTheSameEntityAreNotAffected() {
        var admitted = resolver(A_BROKEN).authorizedEntities(ISSUER, Set.of("a-admins"));
        assertEquals(Map.of(A, Set.of("BUSINESS_ADMIN")), admitted);
    }

    @Test
    void aPersonOnlyInTheBrokenGrantHasNoEntity() {
        assertEquals(Map.of(), resolver(A_BROKEN).authorizedEntities(ISSUER, Set.of("a-staff")));
    }

    @Test
    void thePlatformRoleInAnEntityGrantStillRefusesTheWholeSignIn() {
        var grants = Map.of(
                A, Map.of("a-staff", Set.of(TestRoleCatalog.PLATFORM)),
                B, Map.of("b-staff", Set.of("DOCUMENT_PROCESSOR")));
        var refused = assertThrows(IdentityAdmissionException.class,
                () -> resolver(grants).authorizedEntities(ISSUER, Set.of("a-staff", "b-staff")));
        assertEquals(IdentityAdmissionException.Reason.PLATFORM_ROLE_IN_ENTITY_GRANT, refused.reason());
    }
}
