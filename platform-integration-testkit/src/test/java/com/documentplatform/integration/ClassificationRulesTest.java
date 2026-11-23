package com.documentplatform.integration;

import com.documentplatform.integration.config.ConfigJson;
import com.documentplatform.integration.config.Rule;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.json.internal.Json;
import com.documentplatform.integration.result.AttemptResult;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.integration.store.StoredIntegration;
import com.documentplatform.notifications.secret.SecretRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Classification rules are data. A made-up profile in the style of owner decision 2: "503 with the busy code = busy, no
 * attempt used; 503 without it = outage; AUT-004 (a 503 too) = sign-in fault; codes starting SEN- = refused as
 * sensitivity". The profile names its own codes; nothing here is product code.
 */
class ClassificationRulesTest {

    /** As a profile would ship it: JSON. */
    static final String PROFILE_RULES = """
            [
              {"status": "503", "code": "ACME-AUT-004", "outcome": "SIGN_IN_FAILED"},
              {"status": "503", "code": "ACME-SYS-BUSY", "outcome": "BUSY"},
              {"status": "503", "code": "-", "outcome": "OUTAGE"},
              {"status": "4xx", "code": "ACME-SEN-*", "outcome": "REFUSED", "platformCode": "INT_REFUSED"},
              {"status": "200", "pointer": "/receipt", "equals": "DUPLICATE_ALREADY_STORED", "outcome": "ALREADY_RECEIVED"},
              {"status": "404", "code": "ACME-INT-004", "outcome": "REFUSED"},
              {"status": "503", "code": "ACME-STO-001", "outcome": "OUTAGE", "attemptUsed": false}
            ]
            """;

    private Fx fx;
    private StoredIntegration integration;

    @BeforeEach
    void setUp() {
        fx = new Fx();
        fx.secrets.put(SecretRef.of("int/bearer"), "tok-1");
        fx.mock.expectBearer("tok-1");
        List<Rule> rules = ConfigJson.readRules(PROFILE_RULES);
        integration = Fx.stored(Fx.twoStep(fx.mock.address(), new SignIn.BearerFixed(SecretRef.of("int/bearer")))
                .withRules(rules));
    }

    @AfterEach
    void close() {
        fx.close();
    }

    private AttemptResult send() {
        return fx.engine.attempt(integration, Fx.delivery(1));
    }

    @Test
    void busyCodeIsBusyAndUsesNoAttempt() {
        fx.mock.busy(503, "ACME-SYS-BUSY", Duration.ofSeconds(30));
        AttemptResult r = send();
        assertEquals(Outcome.BUSY, r.outcome());
        assertFalse(r.attemptUsed());
        assertEquals(Duration.ofSeconds(30), r.retryAfter(), "Retry-After passed through");
        fx.mock.busy(503, "ACME-SYS-BUSY", null);
        assertNull(send().retryAfter());
    }

    @Test
    void the503WithoutTheBusyCodeIsAnOutage() {
        fx.mock.outage();
        AttemptResult r = send();
        assertEquals(Outcome.OUTAGE, r.outcome());
        assertTrue(r.attemptUsed());
    }

    @Test
    void aut004IsASignInFaultNotBusy() {
        fx.mock.busy(503, "ACME-AUT-004", null);
        AttemptResult r = send();
        assertEquals(Outcome.SIGN_IN_FAILED, r.outcome());
        assertEquals("ACME-AUT-004", r.destinationCode());
    }

    @Test
    void prefixCodesAndFieldRules() {
        fx.mock.refuse(400, "ACME-SEN-006");
        assertEquals(Outcome.REFUSED, send().outcome());
        fx.mock.alreadyReceived("{\"id\":\"s-1\",\"receipt\":\"DUPLICATE_ALREADY_STORED\"}");
        assertEquals(Outcome.ALREADY_RECEIVED, send().outcome());
        fx.mock.alreadyReceived("{\"id\":\"s-1\",\"receipt\":\"RECEIVED\"}");
        assertEquals(Outcome.SENT, send().outcome());
    }

    @Test
    void aRuleCanSayTheAttemptIsNotUsed() {
        fx.mock.busy(503, "ACME-STO-001", null);
        AttemptResult r = send();
        assertEquals(Outcome.OUTAGE, r.outcome());
        assertFalse(r.attemptUsed());
    }

    @Test
    void defaultsWithoutRules() {
        integration = Fx.stored(integration.config().withRules(List.of()));
        fx.mock.busy(503, "ACME-SYS-BUSY", null);
        assertEquals(Outcome.OUTAGE, send().outcome(), "without the profile, a 503 is an outage");
        fx.mock.refuse(404, null);
        AttemptResult wrongAddress = send();
        assertEquals(Outcome.NOT_CONFIGURED, wrongAddress.outcome(), "a 404 without a code is a wrong address");
        assertEquals(IntegrationCode.INT_ADDRESS_NOT_FOUND, wrongAddress.code());
        fx.mock.refuse(404, "ACME-INT-004");
        assertEquals(Outcome.REFUSED, send().outcome());
        fx.mock.busy(429, null, null);
        assertEquals(Outcome.BUSY, send().outcome());
    }

    @Test
    void aDestinationCodeThatIsNotACodeIsNeverRepeated() {
        // xc-never-leak-69: only [A-Za-z0-9_.:-]{1,40} is kept as the destination's code; other answer text is dropped.
        for (String text : List.of("Account 12345 belongs to Ann Lee", "A".repeat(41), "line\nbreak", "")) {
            fx.mock.clearRoutes().route("POST", "/api/submissions", 409, "{\"code\":" + Json.quote(text) + "}");
            AttemptResult r = send();
            assertEquals(Outcome.REFUSED, r.outcome(), r.toString());
            assertNull(r.destinationCode(), text);
            if (!text.isEmpty()) assertFalse(r.toString().contains(text));
        }
        fx.mock.clearRoutes().route("POST", "/api/submissions", 409, "{\"code\":\"" + "A".repeat(40) + "\"}");
        assertEquals("A".repeat(40), send().destinationCode(), "40 characters is still a code");
        fx.mock.clearRoutes().route("POST", "/api/submissions", 409, "{\"code\":4711}");
        assertEquals("4711", send().destinationCode(), "a number is a code too");
    }

    @Test
    void redirectsAreNeverFollowed() {
        fx.mock.route("POST", "/api/submissions", 302, "{}");
        AttemptResult r = send();
        assertEquals(Outcome.NOT_CONFIGURED, r.outcome());
        assertEquals(IntegrationCode.INT_REDIRECT_REFUSED, r.code());
        assertEquals(1, fx.mock.calls().size(), "the Location was not followed");
    }

    @Test
    void storedRulesRoundTrip() {
        List<Rule> rules = ConfigJson.readRules(PROFILE_RULES);
        assertEquals(7, rules.size());
        assertEquals(rules, ConfigJson.read(ConfigJson.write(integration.config())).rules());
        assertThrows(ConfigJson.Unreadable.class, () -> ConfigJson.readRules("[{\"status\":\"9xx\",\"outcome\":\"BUSY\"}]"));
        assertThrows(ConfigJson.Unreadable.class, () -> ConfigJson.readRules("[{\"status\":\"503\",\"outcome\":\"MAYBE\"}]"));
    }
}
