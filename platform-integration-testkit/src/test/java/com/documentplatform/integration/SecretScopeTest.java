package com.documentplatform.integration;

import com.documentplatform.integration.config.CallStep;
import com.documentplatform.integration.config.ConfigCheck;
import com.documentplatform.integration.config.ConfigProblem;
import com.documentplatform.integration.config.Endpoint;
import com.documentplatform.integration.config.HttpMethod;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.Limits;
import com.documentplatform.integration.config.SecretScope;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.engine.CallEngine;
import com.documentplatform.integration.internal.SlotIds;
import com.documentplatform.integration.result.AttemptResult;
import com.documentplatform.integration.result.HealthResult;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.integration.testkit.MockDestination;
import com.documentplatform.notifications.secret.Secret;
import com.documentplatform.notifications.secret.SecretRef;
import com.documentplatform.notifications.secret.SecretResolver;
import com.documentplatform.notifications.inmemory.InMemorySecretResolver;
import com.documentplatform.outbound.Installation;
import com.documentplatform.outbound.Outbound;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P044, SEC-10: an integration names only secrets under its own entity ({@code int/<entity>/<name>}), checked at save
 * and at use. The review's attack: an admin of entity B saves an integration that names entity A's secret and sends it
 * to a token address B controls; without the rule the platform resolves A's secret and posts it there.
 */
class SecretScopeTest {

    static final UUID A = UUID.fromString("11111111-2222-4333-8444-555555555555");
    static final UUID B = UUID.fromString("99999999-8888-4777-8666-555555555555");
    static final SecretRef A_SECRET = SecretScope.of(A, "api-client-secret");

    /** A resolver that records every reference it is asked for. */
    static final class Recording implements SecretResolver {
        final InMemorySecretResolver real = new InMemorySecretResolver().put(A_SECRET, "entity-A-client-secret");
        final List<String> asked = Collections.synchronizedList(new ArrayList<>());

        @Override
        public Optional<Secret> resolve(SecretRef ref) {
            asked.add(ref.value());
            return real.resolve(ref);
        }
    }

    static IntegrationConfig bNamingAsSecret(MockDestination mock) {
        return IntegrationConfig.apiPush(B, Fx.PRODUCT, "B's integration", new Endpoint(mock.address(), true),
                        SignIn.OAuth2ClientCredentials.secret(mock.tokenAddress(), "client-of-B", A_SECRET, null))
                .withSteps(List.of(CallStep.of("send", HttpMethod.POST, "/api/notice")
                        .withJsonBody("{\"packageId\":\"{package.id}\"}")))
                .withHealth(com.documentplatform.integration.config.HealthCheck.get(MockDestination.HEALTH_PATH, true))
                .withLimits(Limits.DEFAULT.withReadTimeout(Duration.ofSeconds(5)));
    }

    @Test
    void theNamingRule() {
        assertTrue(SecretScope.allows(A, SecretRef.of("int/" + A + "/x")));
        assertTrue(SecretScope.allows(SlotIds.entityUuid("ENT-0001"), SecretRef.of("int/ENT-0001/api-key")),
                "the slot ID names the same entity");
        assertFalse(SecretScope.allows(A, SecretRef.of("int/" + B + "/x")), "another entity's");
        assertFalse(SecretScope.allows(A, SecretRef.of("int/ENT-0001/x")), "another entity's slot");
        assertFalse(SecretScope.allows(A, SecretRef.of("int/x")), "under no entity");
        assertFalse(SecretScope.allows(A, SecretRef.of("int/" + A)), "no name");
        assertFalse(SecretScope.allows(A, SecretRef.of("other/" + A + "/x")), "outside int/");
        assertEquals("int/" + A + "/k", SecretScope.of(A, "k").value());
    }

    @Test
    void anotherEntitysSecretIsRefusedAtSave() {
        try (MockDestination mock = MockDestination.start()) {
            List<ConfigProblem> errors = ConfigCheck.check(bNamingAsSecret(mock),
                    Installation.onPremises().withInternalAddresses(true)).errors();
            assertEquals(List.of(new ConfigProblem(IntegrationCode.INT_CONFIG_SECRET_SCOPE, "signIn.credential", null)),
                    errors);
        }
    }

    @Test
    void anotherEntitysSecretIsNeverResolvedNorSentAtUse() {
        try (MockDestination mock = MockDestination.start()) {
            mock.expectOAuth2();
            Recording secrets = new Recording();
            try (CallEngine engine = new CallEngine(new Outbound(Installation.onPremises().withInternalAddresses(true)),
                    secrets, Clock.systemUTC())) {
                IntegrationConfig c = bNamingAsSecret(mock); // as if stored before the rule, or written past the save
                AttemptResult r = engine.attempt(Fx.stored(c), Fx.delivery(1));
                assertEquals(Outcome.NOT_CONFIGURED, r.outcome());
                assertEquals(IntegrationCode.INT_CONFIG_SECRET_SCOPE, r.code(), r.toString());
                HealthResult h = engine.health(Fx.stored(c));
                assertEquals(IntegrationCode.INT_CONFIG_SECRET_SCOPE, h.code());
            }
            assertEquals(List.of(), secrets.asked, "the secret store was asked for another entity's secret");
            assertEquals(List.of(), mock.tokenRequests(), "A's secret went to B's token address");
            assertEquals(List.of(), mock.received());
        }
    }
}
