package com.documentplatform.fields.delivery;

import com.documentplatform.fields.FieldKey;
import com.documentplatform.fields.ValidValue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the receiver found in a delivery's case fields.
 *
 * @param values   per field (in the receiver's display order), the checked values; empty when refused
 * @param problems every problem; none means accepted
 */
public record DeliveryResult(Map<FieldKey, List<ValidValue>> values, List<DeliveryProblem> problems) {

    public DeliveryResult {
        Map<FieldKey, List<ValidValue>> copy = new LinkedHashMap<>();
        values.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        values = Collections.unmodifiableMap(copy);
        problems = List.copyOf(problems);
    }

    public boolean accepted() {
        return problems.isEmpty();
    }

    /** The distinct codes found, for the reply to the sender. */
    public List<DeliveryProblem.Code> codes() {
        return problems.stream().map(DeliveryProblem::code).distinct().toList();
    }

    @Override
    public String toString() {
        return "DeliveryResult[" + values.size() + " fields, problems " + problems + "]";
    }
}
