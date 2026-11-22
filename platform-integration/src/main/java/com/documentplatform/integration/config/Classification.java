package com.documentplatform.integration.config;

import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.integration.result.Outcome;

import java.util.ArrayList;
import java.util.List;

/**
 * Classifies an answer: the integration's (or profile's) rules first, in order, then the {@link #DEFAULTS}. The first
 * rule that matches decides.
 */
public final class Classification {

    /** What a rule decided. */
    public record Decision(Outcome outcome, IntegrationCode code, boolean attemptUsed) {
    }

    /**
     * Applied after the configured rules: 2xx sent; 3xx refused as a redirect (never followed); 401 and 403 sign-in;
     * 404 without a destination code is a wrong address (a set-up fault, not a refusal); 408 and 429 busy; other 4xx
     * refused; 5xx outage; anything else unclear.
     */
    public static final List<Rule> DEFAULTS = List.of(
            Rule.of("2xx", null, Outcome.SENT),
            Rule.of("3xx", null, Outcome.NOT_CONFIGURED).withPlatformCode(IntegrationCode.INT_REDIRECT_REFUSED),
            Rule.of("401", null, Outcome.SIGN_IN_FAILED),
            Rule.of("403", null, Outcome.SIGN_IN_FAILED),
            Rule.of("404", "-", Outcome.NOT_CONFIGURED).withPlatformCode(IntegrationCode.INT_ADDRESS_NOT_FOUND),
            Rule.of("408", null, Outcome.BUSY),
            Rule.of("429", null, Outcome.BUSY),
            Rule.of("4xx", null, Outcome.REFUSED),
            Rule.of("5xx", null, Outcome.OUTAGE));

    private final List<Rule> rules;

    private Classification(List<Rule> configured) {
        List<Rule> all = new ArrayList<>(configured);
        all.addAll(DEFAULTS);
        this.rules = List.copyOf(all);
    }

    public static Classification of(List<Rule> configured) {
        return new Classification(configured);
    }

    public Decision decide(int status, String destinationCode, Object json) {
        for (Rule r : rules) {
            if (r.matches(status, destinationCode, json)) {
                return new Decision(r.outcome(), r.effectiveCode(), r.effectiveAttemptUsed());
            }
        }
        return new Decision(Outcome.UNCLEAR, IntegrationCode.INT_UNEXPECTED_STATUS, true);
    }
}
