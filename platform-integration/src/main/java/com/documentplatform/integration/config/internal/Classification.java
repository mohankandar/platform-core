package com.documentplatform.integration.config.internal;

import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.integration.config.Rule;
import com.documentplatform.integration.result.Outcome;

import java.util.ArrayList;
import java.util.List;

/**
 * Classifies an answer: the integration's (or profile's) rules first, in order, then {@link Rule#DEFAULTS}. The first
 * rule that matches decides. Internal: used by the call and poll engines; apps write {@link Rule}s.
 */
public final class Classification {

    /** What a rule decided. */
    public record Decision(Outcome outcome, IntegrationCode code, boolean attemptUsed) {
    }

    private final List<Rule> rules;

    private Classification(List<Rule> configured) {
        List<Rule> all = new ArrayList<>(configured);
        all.addAll(Rule.DEFAULTS);
        this.rules = List.copyOf(all);
    }

    public static Classification of(List<Rule> configured) {
        return new Classification(configured);
    }

    /** Step rules first, then the integration's, then the defaults. */
    public static Classification of(List<Rule> step, List<Rule> integration) {
        List<Rule> all = new ArrayList<>(step);
        all.addAll(integration);
        return new Classification(all);
    }

    public Decision decide(int status, String destinationCode, Object json) {
        return decide(status, destinationCode, json, false);
    }

    public Decision decide(int status, String destinationCode, Object json, boolean hasRetryAfter) {
        for (Rule r : rules) {
            if (r.matches(status, destinationCode, json, hasRetryAfter)) {
                return new Decision(r.outcome(), r.effectiveCode(), r.effectiveAttemptUsed());
            }
        }
        return new Decision(Outcome.UNCLEAR, IntegrationCode.INT_UNEXPECTED_STATUS, true);
    }
}
