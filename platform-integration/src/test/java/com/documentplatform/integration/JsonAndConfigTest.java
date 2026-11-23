package com.documentplatform.integration;

import com.documentplatform.integration.config.CallStep;
import com.documentplatform.integration.config.internal.Classification;
import com.documentplatform.integration.config.ConfigJson;
import com.documentplatform.integration.config.Endpoint;
import com.documentplatform.integration.config.HealthCheck;
import com.documentplatform.integration.config.HttpMethod;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.Rule;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.json.internal.Json;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.notifications.secret.SecretRef;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JsonAndConfigTest {

    static IntegrationConfig sample(SignIn signIn) {
        return IntegrationConfig.apiPush(UUID.randomUUID(), "DEMO", "Sample",
                        new Endpoint(URI.create("https://api.example/base"), true), signIn)
                .withSteps(List.of(
                        CallStep.of("create", HttpMethod.POST, "/s").withJsonBody("{\"id\":\"{package.id}\"}")
                                .withHeader("X-A", "1").reading("submissionId", "/id", true),
                        CallStep.of("upload", HttpMethod.PUT, "/s/{step.create.submissionId}/d").withFileBody()
                                .eachDocument().withHeader("X-Name", "{doc.fileName}")))
                .withErrorCodePointer("/error/code")
                .withRules(List.of(Rule.of("503", "X-BUSY", Outcome.BUSY).withAttemptUsed(false),
                        Rule.of("200", null, Outcome.ALREADY_RECEIVED).whenField("/dup", "true")
                                .withPlatformCode(IntegrationCode.INT_ALREADY_RECEIVED)))
                .withHealth(HealthCheck.get("/ready", false).withRules(List.of(Rule.of("503", "-", Outcome.OUTAGE))))
                .withProfile("sample-api-v1");
    }

    @Test
    void everySignInTypeRoundTrips() {
        for (SignIn s : List.of(new SignIn.None(), new SignIn.Basic("u", SecretRef.of("a/b")),
                new SignIn.ApiKey("X-Key", SecretRef.of("a/k")), new SignIn.BearerFixed(SecretRef.of("a/t")),
                SignIn.OAuth2ClientCredentials.secret(URI.create("https://login.example/token"), "client",
                        SecretRef.of("a/c"), "a b").withAudience("api://x")
                        .withClientAuth(SignIn.OAuth2ClientCredentials.ClientAuth.BASIC),
                SignIn.OAuth2ClientCredentials.certificate(URI.create("https://login.example/token"), "client",
                        SecretRef.of("a/p"), null))) {
            IntegrationConfig c = sample(s);
            assertEquals(c, ConfigJson.read(ConfigJson.write(c)), s.type());
        }
    }

    @Test
    void storedTextHoldsReferencesOnly() {
        String text = ConfigJson.write(sample(new SignIn.Basic("u", SecretRef.of("vault/int/pw"))));
        assertTrue(text.contains("\"password\":\"vault/int/pw\""));
        assertTrue(text.contains("\"format\":\"platform-integration-config/1\""));
        assertThrows(ConfigJson.Unreadable.class, () -> ConfigJson.read("{\"format\":\"other\"}"));
        assertThrows(ConfigJson.Unreadable.class, () -> ConfigJson.read("nope"));
    }

    @Test
    void changedFieldsAreNamesOnly() {
        IntegrationConfig a = sample(new SignIn.BearerFixed(SecretRef.of("a/t1")));
        IntegrationConfig b = a.withSignIn(new SignIn.BearerFixed(SecretRef.of("a/t2")))
                .withSteps(List.of(a.steps().get(0), a.steps().get(1).withHeader("X-More", "1")));
        assertEquals(List.of("signIn.token", "steps[1].headers.X-More"), ConfigJson.changedFields(a, b));
        assertEquals(List.of(), ConfigJson.changedFields(a, a));
    }

    @Test
    void jsonIsStrict() throws Exception {
        assertEquals(Map.of("a", List.of(1L, 2.5, true)), Json.parse("{\"a\":[1,2.5,true]}"));
        for (String bad : new String[]{"{\"a\":1,\"a\":2}", "{a:1}", "[1,]", "01", "\"\\x\"", "{\"a\":1} x", ""}) {
            assertThrows(Json.Invalid.class, () -> Json.parse(bad), bad);
        }
        Json.Invalid e = assertThrows(Json.Invalid.class, () -> Json.parse("{\"token\":\"s3cret\""));
        assertFalse(e.getMessage().contains("s3cret"));
        assertEquals("x", Json.at(Json.parse("{\"a\":[{\"b/c\":\"x\"}]}"), "/a/0/b~1c").orElseThrow());
        assertTrue(Json.at(Json.parse("{\"a\":null}"), "/a").isEmpty());
        assertEquals("{\"k\":\"line\\nbreak\\u0001\"}", Json.write(Map.of("k", "line\nbreak\u0001")));
    }

    @Test
    void classificationDefaults() {
        Classification c = Classification.of(List.of());
        assertEquals(Outcome.SENT, c.decide(204, null, null).outcome());
        assertEquals(Outcome.NOT_CONFIGURED, c.decide(301, null, null).outcome());
        assertEquals(Outcome.SIGN_IN_FAILED, c.decide(403, "X", null).outcome());
        assertEquals(Outcome.NOT_CONFIGURED, c.decide(404, null, null).outcome());
        assertEquals(Outcome.REFUSED, c.decide(404, "X-NOT-FOUND", null).outcome());
        assertEquals(Outcome.BUSY, c.decide(429, null, null).outcome());
        assertFalse(c.decide(429, null, null).attemptUsed());
        assertEquals(Outcome.REFUSED, c.decide(422, null, null).outcome());
        assertEquals(Outcome.OUTAGE, c.decide(502, null, null).outcome());
        assertEquals(Outcome.UNCLEAR, c.decide(102, null, null).outcome());
    }

    @Test
    void rulesAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> Rule.of("6xx", null, Outcome.BUSY));
        assertThrows(IllegalArgumentException.class, () -> Rule.of("599-500", null, Outcome.BUSY));
        assertThrows(IllegalArgumentException.class, () -> Rule.of("503", "bad code with spaces", Outcome.BUSY));
        assertThrows(IllegalArgumentException.class, () -> Rule.of("503", null, Outcome.BUSY)
                .withPlatformCode(IntegrationCode.INT_CONFIG_INVALID));
        assertTrue(Rule.of("500-599", "A-*", Outcome.BUSY).matches(503, "A-1", null));
        assertFalse(Rule.of("500-599", "A-*", Outcome.BUSY).matches(503, null, null));
        assertTrue(Rule.of("any", "*", Outcome.BUSY).matches(200, null, null));
    }
}
