package com.documentplatform.fields;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A case's values once checked: the valid values per field (in display order) and every problem found. The case's
 * values are accepted only when there is no problem.
 */
public record ValidatedFields(Map<FieldKey, List<ValidValue>> values, List<ValueProblem> problems) {

    public ValidatedFields {
        Map<FieldKey, List<ValidValue>> copy = new LinkedHashMap<>();
        values.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        values = Collections.unmodifiableMap(copy);
        problems = List.copyOf(problems);
    }

    public boolean accepted() {
        return problems.isEmpty();
    }

    @Override
    public String toString() {
        return "ValidatedFields[" + values.size() + " fields with values, problems " + problems + "]";
    }
}
