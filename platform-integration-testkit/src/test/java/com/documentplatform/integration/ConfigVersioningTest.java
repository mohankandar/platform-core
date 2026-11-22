package com.documentplatform.integration;

import com.documentplatform.integration.config.CallStep;
import com.documentplatform.integration.config.ConfigProblem;
import com.documentplatform.integration.config.Endpoint;
import com.documentplatform.integration.config.HttpMethod;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.result.AttemptResult;
import com.documentplatform.integration.safety.Installation;
import com.documentplatform.integration.store.ConfigChange;
import com.documentplatform.integration.store.IntegrationAudit;
import com.documentplatform.integration.store.IntegrationConfigs;
import com.documentplatform.integration.store.IntegrationStore;
import com.documentplatform.integration.store.StoredIntegration;
import com.documentplatform.integration.testkit.InMemoryIntegrationStore;
import com.documentplatform.notifications.secret.SecretRef;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Configuration versioning and history, with the app's audit, on the in-memory store. */
class ConfigVersioningTest {

    static final class RecordingAudit implements IntegrationAudit {
        final List<ConfigChange> changes = new ArrayList<>();
        final List<String> tests = new ArrayList<>();

        @Override
        public void changed(ConfigChange change) {
            changes.add(change);
        }

        @Override
        public void tested(AttemptResult result, String by) {
            tests.add(by + ":" + result.outcome());
        }
    }

    private final RecordingAudit audit = new RecordingAudit();
    private final IntegrationConfigs configs = new IntegrationConfigs(new InMemoryIntegrationStore(), audit,
            Installation.onPremises(), Clock.systemUTC());

    private static IntegrationConfig base() {
        return Fx.twoStep(URI.create("https://api.example"), new SignIn.BearerFixed(SecretRef.of("int/t1")));
    }

    @Test
    void everyChangeIsANewVersionWithWhoWhenAndWhichFields() {
        IntegrationConfig c = base();
        StoredIntegration v1 = configs.create(c, "alice");
        assertEquals(1, v1.version());
        assertEquals("alice", v1.changedBy());

        StoredIntegration v2 = configs.update(c.withSignIn(new SignIn.BearerFixed(SecretRef.of("int/t2")))
                .withName("Renamed"), 1, "bob");
        assertEquals(2, v2.version());
        StoredIntegration v3 = configs.setEnabled(c.id(), false, 2, "carol");
        assertEquals(3, v3.version());
        assertFalse(v3.config().enabled());

        List<ConfigChange> history = configs.history(c.id());
        assertEquals(List.of(1, 2, 3), history.stream().map(ConfigChange::version).toList());
        assertEquals(List.of("alice", "bob", "carol"), history.stream().map(ConfigChange::changedBy).toList());
        assertEquals(List.of("name", "signIn.token"), history.get(1).changedFields());
        assertEquals(List.of("enabled"), history.get(2).changedFields());
        assertTrue(history.get(0).changedFields().contains("steps[1].path"));
        assertEquals(history, audit.changes, "the app's audit gets one entry per change");
        // Field names only: the audit never sees a reference's value or any other value.
        assertFalse(audit.changes.toString().contains("int/t2"));
        assertFalse(audit.changes.toString().contains("Renamed"));

        // Older versions stay readable.
        assertEquals("int/t1", ((SignIn.BearerFixed) configs.version(c.id(), 1).orElseThrow().config().signIn())
                .token().value());
        assertEquals(3, configs.current(c.id()).orElseThrow().version());
        assertEquals(1, configs.list(Fx.ENTITY, Fx.PRODUCT).size());
    }

    @Test
    void aStaleVersionIsAConflictAndNoChangeIsNoVersion() {
        IntegrationConfig c = base();
        configs.create(c, "alice");
        configs.update(c.withName("A"), 1, "bob");
        IntegrationStore.Conflict conflict = assertThrows(IntegrationStore.Conflict.class,
                () -> configs.update(c.withName("B"), 1, "carol"));
        assertEquals(IntegrationCode.INT_CONFIG_VERSION_CONFLICT, conflict.code());
        StoredIntegration same = configs.update(c.withName("A"), 2, "dave");
        assertEquals(2, same.version());
        assertEquals(2, configs.history(c.id()).size());
        assertEquals(IntegrationCode.INT_CONFIG_EXISTS,
                assertThrows(IntegrationStore.Conflict.class, () -> configs.create(c, "eve")).code());
        assertEquals(IntegrationCode.INT_CONFIG_NOT_FOUND, assertThrows(IntegrationStore.Conflict.class,
                () -> configs.update(base(), 1, "eve")).code());
    }

    @Test
    void entityProductAndKindNeverChange() {
        IntegrationConfig c = base();
        configs.create(c, "alice");
        IntegrationConfig moved = new IntegrationConfig(c.id(), java.util.UUID.randomUUID(), c.product(), c.kind(),
                c.name(), c.enabled(), c.endpoint(), c.signIn(), c.limits(), c.steps(), c.errorCodePointer(),
                c.rules(), c.health(), c.testMode(), c.sendCorrelationId(), c.profile());
        assertThrows(IntegrationConfigs.Refused.class, () -> configs.update(moved, 1, "bob"));
    }

    @Test
    void signInNoneIsWarnedAndRecordedOnPremisesAndRefusedWhenHosted() {
        IntegrationConfig none = base().withSignIn(new SignIn.None());
        IntegrationConfigs strict = new IntegrationConfigs(new InMemoryIntegrationStore(), audit,
                Installation.onPremises(), Clock.systemUTC());
        assertEquals(IntegrationCode.INT_CONFIG_SIGN_IN_NONE_OFF, codes(() -> strict.create(none, "a")).get(0));
        IntegrationConfigs hosted = new IntegrationConfigs(new InMemoryIntegrationStore(), audit,
                Installation.hostedService(), Clock.systemUTC());
        assertTrue(codes(() -> hosted.create(none, "a")).contains(IntegrationCode.INT_CONFIG_SIGN_IN_NONE_HOSTED));
        IntegrationConfigs allowed = new IntegrationConfigs(new InMemoryIntegrationStore(), audit,
                Installation.onPremises().withSignInNone(true), Clock.systemUTC());
        allowed.create(none, "a");
        assertEquals(List.of(IntegrationCode.INT_CONFIG_SIGN_IN_NONE_WARNING),
                allowed.history(none.id()).get(0).warnings());
    }

    @Test
    void templatesFromFixedBuildingBlocksOnly() {
        assertEquals(IntegrationCode.INT_CONFIG_PLACEHOLDER_FORBIDDEN, codes(() -> configs.create(step(
                CallStep.of("s", HttpMethod.POST, "/x").withJsonBody("{\"a\":\"{secret.password}\"}")), "a")).get(0));
        assertEquals(IntegrationCode.INT_CONFIG_PLACEHOLDER_FORBIDDEN, codes(() -> configs.create(step(
                CallStep.of("s", HttpMethod.POST, "/x").eachDocument().withJsonBody("{\"a\":\"{doc.content}\"}")),
                "a")).get(0));
        assertEquals(IntegrationCode.INT_CONFIG_PLACEHOLDER_IN_QUERY, codes(() -> configs.create(step(
                CallStep.of("s", HttpMethod.GET, "/x?id={package.id}")), "a")).get(0));
        assertEquals(IntegrationCode.INT_CONFIG_PLACEHOLDER_UNKNOWN, codes(() -> configs.create(step(
                CallStep.of("s", HttpMethod.GET, "/x/{package.owner}")), "a")).get(0));
        assertEquals(IntegrationCode.INT_CONFIG_HEADER_RESERVED, codes(() -> configs.create(step(
                CallStep.of("s", HttpMethod.GET, "/x").withHeader("Authorization", "Bearer {value.t}")), "a")).get(0));
        assertEquals(IntegrationCode.INT_CONFIG_PLACEHOLDER_PLACE, codes(() -> configs.create(step(
                CallStep.of("s", HttpMethod.GET, "/x/{doc.fileName}")), "a")).get(0));
        assertEquals(IntegrationCode.INT_CONFIG_STEP_VALUE_UNKNOWN, codes(() -> configs.create(step(
                CallStep.of("s", HttpMethod.GET, "/x/{step.create.id}")), "a")).get(0));
        assertEquals(IntegrationCode.INT_CONFIG_PLACEHOLDER_PLACE, codes(() -> configs.create(step(
                CallStep.of("s", HttpMethod.POST, "/x").withJsonBody("{\"a\":{value.loan}}")), "a")).get(0));
        assertEquals(IntegrationCode.INT_CONFIG_BODY_NOT_JSON, codes(() -> configs.create(step(
                CallStep.of("s", HttpMethod.POST, "/x").withJsonBody("{\"a\":\"{value.loan}\"")), "a")).get(0));
        assertEquals(IntegrationCode.INT_CONFIG_TEMPLATE_INVALID, codes(() -> configs.create(step(
                CallStep.of("s", HttpMethod.GET, "//evil.example/x")), "a")).get(0));
        assertEquals(IntegrationCode.INT_CONFIG_TEMPLATE_INVALID, codes(() -> configs.create(step(
                CallStep.of("s", HttpMethod.GET, "/x/../admin")), "a")).get(0));
        assertEquals(IntegrationCode.INT_CONFIG_STEPS_INVALID, codes(() -> configs.create(step(
                CallStep.of("s", HttpMethod.POST, "/x").withFileBody()), "a")).get(0));
        // The API key's own header cannot be set by a template either.
        IntegrationConfig apiKey = step(CallStep.of("s", HttpMethod.GET, "/x").withHeader("X-Key", "{value.k}"))
                .withSignIn(new SignIn.ApiKey("X-Key", SecretRef.of("int/k")));
        assertEquals(IntegrationCode.INT_CONFIG_HEADER_RESERVED, codes(() -> configs.create(apiKey, "a")).get(0));
        assertTrue(audit.changes.isEmpty(), "refused configurations are not saved");
    }

    private static IntegrationConfig step(CallStep s) {
        return IntegrationConfig.apiPush(Fx.ENTITY, Fx.PRODUCT, "x", new Endpoint(URI.create("https://api.example"),
                true), new SignIn.BearerFixed(SecretRef.of("int/t1"))).withSteps(List.of(s));
    }

    private static List<IntegrationCode> codes(Runnable r) {
        IntegrationConfigs.Refused e = assertThrows(IntegrationConfigs.Refused.class, r::run);
        return e.problems().stream().map(ConfigProblem::code).toList();
    }
}
