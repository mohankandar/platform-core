package com.documentplatform.fields;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The field list of one entity and product, for checking a case's values together: unknown keys, switched-off
 * fields and missing required fields, then each field's own rules ({@link FieldValidator}).
 */
public final class FieldSet {

    private final List<FieldSpec> fields;
    private final Map<FieldKey, FieldSpec> byKey = new HashMap<>();

    private FieldSet(Collection<FieldSpec> specs) {
        Map<String, FieldKey> folded = new HashMap<>();
        for (FieldSpec s : specs) {
            FieldKey before = folded.putIfAbsent(s.key().folded(), s.key());
            if (before != null) {
                throw new FieldDefinitionException(FieldDefinitionException.Code.FIELD_KEY_TAKEN,
                        "Two fields with the key " + s.key() + " (keys are compared ignoring capitals)");
            }
            byKey.put(s.key(), s);
        }
        List<FieldSpec> sorted = new ArrayList<>(specs);
        sorted.sort(Comparator.comparingInt(FieldSpec::displayOrder).thenComparing(FieldSpec::key));
        this.fields = List.copyOf(sorted);
    }

    public static FieldSet of(Collection<FieldSpec> specs) {
        return new FieldSet(specs);
    }

    public static FieldSet ofDefinitions(Collection<FieldDefinition> definitions) {
        return new FieldSet(definitions.stream().map(FieldDefinition::spec).toList());
    }

    /** All fields, switched off ones included, in display order. */
    public List<FieldSpec> all() {
        return fields;
    }

    /** The fields that are switched on, in display order. */
    public List<FieldSpec> active() {
        return fields.stream().filter(FieldSpec::active).toList();
    }

    /** The field with exactly this key. */
    public Optional<FieldSpec> find(FieldKey key) {
        return Optional.ofNullable(byKey.get(key));
    }

    public Optional<FieldSpec> find(String key) {
        return FieldKey.isValid(key) ? find(FieldKey.of(key)) : Optional.empty();
    }

    /**
     * Checks a case's values. A blank value (or an address with every part blank) counts as no value. Values for
     * unknown keys and for switched-off fields are refused; every required active field needs a value.
     *
     * @param values per key, the values given (one, or several for a multi-value field)
     */
    public ValidatedFields validate(Map<String, ? extends List<? extends FieldValue>> values) {
        Map<FieldKey, List<ValidValue>> valid = new LinkedHashMap<>();
        List<ValueProblem> problems = new ArrayList<>();
        Map<FieldKey, List<FieldValue>> given = new HashMap<>();
        for (Map.Entry<String, ? extends List<? extends FieldValue>> e : values.entrySet()) {
            if (!FieldKey.isValid(e.getKey())) {
                problems.add(new ValueProblem(null, null, ValueError.FIELD_UNKNOWN,
                        "A value was given under a key that is not a valid field key"));
                continue;
            }
            FieldKey key = FieldKey.of(e.getKey());
            FieldSpec spec = byKey.get(key);
            if (spec == null) {
                problems.add(new ValueProblem(key, null, ValueError.FIELD_UNKNOWN, "No field has the key " + key));
                continue;
            }
            List<FieldValue> present = new ArrayList<>();
            for (FieldValue v : e.getValue() == null ? List.<FieldValue>of() : e.getValue()) {
                if (v != null && !blank(v)) {
                    present.add(v);
                }
            }
            if (present.isEmpty()) {
                continue;
            }
            if (!spec.active()) {
                problems.add(new ValueProblem(key, null, ValueError.FIELD_INACTIVE,
                        spec.label() + " is switched off and takes no new values"));
                continue;
            }
            given.put(key, present);
        }
        for (FieldSpec spec : active()) {
            FieldValidator.Result r = FieldValidator.checkAll(spec, given.getOrDefault(spec.key(), List.of()));
            problems.addAll(r.problems());
            if (r.ok() && !r.values().isEmpty()) {
                valid.put(spec.key(), r.values());
            }
        }
        return new ValidatedFields(valid, problems);
    }

    private static boolean blank(FieldValue v) {
        if (v instanceof FieldValue.Text t) {
            return t.value().isBlank();
        }
        FieldValue.Address a = (FieldValue.Address) v;
        for (String p : new String[]{a.street(), a.street2(), a.city(), a.state(), a.zip()}) {
            if (p != null && !p.isBlank()) {
                return false;
            }
        }
        return true;
    }
}
