package com.documentplatform.integration;

import com.documentplatform.integration.config.internal.Classification;
import com.documentplatform.integration.config.ConfigJson;
import com.documentplatform.integration.config.Rule;
import com.documentplatform.integration.result.Outcome;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** "Decisions on the P023 report", item 4: "503 with Retry-After means busy" as a ready-made rule. */
class ReadyMadeRuleTest {

    @Test
    void busyOnlyWithRetryAfterOtherwiseTheDefaultOutage() {
        Classification c = Classification.of(List.of(Rule.busyWhen503WithRetryAfter()));
        Classification.Decision busy = c.decide(503, null, null, true);
        assertEquals(Outcome.BUSY, busy.outcome());
        assertFalse(busy.attemptUsed());
        assertEquals(Outcome.OUTAGE, c.decide(503, null, null, false).outcome(), "without Retry-After: the default");
        assertEquals(Outcome.OUTAGE, Classification.of(List.of()).decide(503, null, null, true).outcome(),
                "without the rule a 503 stays an outage");
        Classification h = Classification.of(List.of(Rule.healthBusyWhen503WithRetryAfter()));
        assertEquals(IntegrationCode.INT_DESTINATION_BUSY, h.decide(503, null, null, true).code());
    }

    @Test
    void itIsOrdinaryRuleDataThatIsStored() {
        String json = "[{\"status\":\"503\",\"outcome\":\"BUSY\",\"retryAfter\":true}]";
        assertEquals(List.of(Rule.busyWhen503WithRetryAfter()), ConfigJson.readRules(json));
    }
}
