package com.documentplatform.fields.delivery;

import com.documentplatform.fields.FieldKey;
import com.documentplatform.fields.FieldSet;
import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.FieldType;
import com.documentplatform.fields.FieldValidator;
import com.documentplatform.fields.FieldValue;
import com.documentplatform.fields.ValidValue;
import com.documentplatform.fields.ValidatedFields;
import com.documentplatform.fields.ValueProblem;
import com.documentplatform.fields.json.Json;
import com.documentplatform.fields.json.JsonException;
import com.documentplatform.fields.json.JsonFields;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The case fields of a delivery from one product to the other, format {@code platform-field-values/1}. Each field
 * travels as its key, its type (with the currency for MONEY) and its value, or its list of values for a
 * multi-value field, so the receiver knows exactly what it got:
 *
 * <pre>{@code
 * {
 *   "fields": [
 *     { "key": "loan_number", "type": "TEXT", "value": "LN-2026-0042" },
 *     { "key": "loan_amount", "type": "MONEY", "currency": "USD", "value": "250000.00" },
 *     { "key": "borrower_name", "type": "TEXT", "values": ["Ann Lee", "Bo Lee"] },
 *     { "key": "property_address", "type": "ADDRESS",
 *       "value": { "street": "1 Main St", "city": "Springfield", "state": "IL", "zip": "62701", "country": "US" } }
 *   ],
 *   "format": "platform-field-values/1"
 * }
 * }</pre>
 *
 * <p>The receiver checks the delivery against its own field list with {@link #verify}: an unknown key, another type
 * or currency, a list for a single-value field (or the reverse), a switched-off field, a missing required field or a
 * value that breaks the receiver's rules are each refused with their code. The document carries values, sensitive
 * ones included, so it is never logged; no message here contains a value. The delivery's integrity check comes with
 * the delivery contract.
 */
public final class FieldDelivery {

    public static final String FORMAT = "platform-field-values/1";
    public static final int MAX_ENTRIES = 1000;
    public static final int MAX_CHARACTERS = 2_000_000;

    private static final Set<String> TOP = Set.of("format", "fields");
    private static final Set<String> ENTRY = Set.of("key", "type", "currency", "value", "values");
    private static final Set<String> ADDRESS = Set.of("street", "street2", "city", "state", "zip", "country");

    private FieldDelivery() {
    }

    /**
     * The delivery document for values the sender accepted.
     *
     * @param sending the sender's field list
     * @param values  the case's values, checked by {@link FieldSet#validate} and accepted
     */
    public static String write(FieldSet sending, ValidatedFields values) {
        Objects.requireNonNull(sending, "sending");
        if (!values.accepted()) {
            throw new IllegalArgumentException("Only accepted values are delivered");
        }
        List<Object> entries = new ArrayList<>();
        for (Map.Entry<FieldKey, List<ValidValue>> e : values.values().entrySet()) {
            FieldSpec spec = sending.find(e.getKey()).orElseThrow(() -> new IllegalArgumentException(
                    "The values are not for this field list: no field " + e.getKey()));
            Map<String, Object> entry = new TreeMap<>();
            entry.put("key", spec.key().value());
            entry.put("type", spec.type().name());
            if (spec.currency() != null) {
                entry.put("currency", spec.currency());
            }
            List<Object> vs = e.getValue().stream().map(v -> json(v.display())).toList();
            if (spec.multiValue()) {
                entry.put("values", vs);
            } else {
                entry.put("value", vs.get(0));
            }
            entries.add(entry);
        }
        Map<String, Object> top = new TreeMap<>();
        top.put("format", FORMAT);
        top.put("fields", entries);
        return Json.canonical(top);
    }

    /**
     * Checks a delivery against the receiver's field list.
     *
     * @return the checked values and every problem; the delivery's fields are accepted only without problems
     */
    public static DeliveryResult verify(FieldSet receiving, String document) {
        Objects.requireNonNull(receiving, "receiving");
        List<DeliveryProblem> problems = new ArrayList<>();
        if (document == null || document.length() > MAX_CHARACTERS) {
            return refused(DeliveryProblem.Code.DELIVERY_TOO_LARGE, "The delivery is empty or larger than 2 MB");
        }
        Object parsed;
        try {
            parsed = Json.parse(document);
        } catch (JsonException e) {
            return refused(DeliveryProblem.Code.DELIVERY_UNREADABLE, "The delivery is not readable JSON: "
                    + e.getMessage());
        }
        List<Object> entries;
        try {
            JsonFields top = JsonFields.of(parsed, "delivery");
            if (!top.has("format") || !(top.raw("format") instanceof String f) || !f.equals(FORMAT)) {
                return refused(DeliveryProblem.Code.DELIVERY_FORMAT_UNKNOWN,
                        "Not a field value delivery: \"format\" must be \"" + FORMAT + "\"");
            }
            only(top, TOP, "delivery");
            entries = top.list("fields");
        } catch (JsonException e) {
            return refused(DeliveryProblem.Code.DELIVERY_SHAPE_INVALID, e.getMessage());
        }
        if (entries.size() > MAX_ENTRIES) {
            return refused(DeliveryProblem.Code.DELIVERY_TOO_LARGE,
                    "The delivery has more than " + MAX_ENTRIES + " fields");
        }
        Map<FieldKey, List<ValidValue>> valid = new LinkedHashMap<>();
        Set<FieldKey> delivered = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            entry(receiving, entries.get(i), i + 1, delivered, valid, problems);
        }
        for (FieldSpec spec : receiving.active()) {
            if (spec.required() && !delivered.contains(spec.key())) {
                problems.add(new DeliveryProblem(DeliveryProblem.Code.DELIVERY_REQUIRED_MISSING, null, spec.key(),
                        null, spec.label() + " (" + spec.key() + ") is required here and was not delivered"));
            }
        }
        Map<FieldKey, List<ValidValue>> ordered = new LinkedHashMap<>();
        for (FieldSpec spec : receiving.all()) {
            if (valid.containsKey(spec.key())) {
                ordered.put(spec.key(), valid.get(spec.key()));
            }
        }
        return new DeliveryResult(problems.isEmpty() ? ordered : Map.of(), problems);
    }

    private static void entry(FieldSet receiving, Object raw, int n, Set<FieldKey> delivered,
                              Map<FieldKey, List<ValidValue>> valid, List<DeliveryProblem> problems) {
        String where = "fields[" + n + "]";
        JsonFields f;
        String keyText;
        String typeText;
        try {
            f = only(JsonFields.of(raw, where), ENTRY, where);
            keyText = f.string("key");
            typeText = f.string("type");
            if (f.has("value") == f.has("values")) {
                throw new JsonException(where + " has either \"value\" or \"values\"");
            }
        } catch (JsonException e) {
            problems.add(new DeliveryProblem(DeliveryProblem.Code.DELIVERY_SHAPE_INVALID, n, null, null,
                    e.getMessage()));
            return;
        }
        if (!FieldKey.isValid(keyText)) {
            problems.add(new DeliveryProblem(DeliveryProblem.Code.DELIVERY_KEY_INVALID, n, null, null,
                    "Entry " + n + " has a key that is not a valid field key"));
            return;
        }
        FieldKey key = FieldKey.of(keyText);
        if (!delivered.add(key)) {
            problems.add(new DeliveryProblem(DeliveryProblem.Code.DELIVERY_KEY_REPEATED, n, key, null,
                    "Entry " + n + " repeats the key " + key));
            return;
        }
        FieldSpec spec = receiving.find(key).orElse(null);
        if (spec == null) {
            problems.add(new DeliveryProblem(DeliveryProblem.Code.DELIVERY_KEY_UNKNOWN, n, key, null,
                    "Entry " + n + ": no field has the key " + key + " here"));
            return;
        }
        if (!spec.active()) {
            problems.add(new DeliveryProblem(DeliveryProblem.Code.DELIVERY_FIELD_INACTIVE, n, key, null,
                    "Entry " + n + ": " + spec.label() + " (" + key + ") is switched off here"));
            return;
        }
        if (!spec.type().name().equals(typeText)) {
            problems.add(new DeliveryProblem(DeliveryProblem.Code.DELIVERY_TYPE_MISMATCH, n, key, null,
                    "Entry " + n + ": " + key + " is " + spec.type() + " here, and the delivery says "
                            + (known(typeText) ? typeText : "a type that does not exist")));
            return;
        }
        try {
            if (spec.type() == FieldType.MONEY) {
                String currency = f.has("currency") ? f.string("currency") : "";
                if (!spec.currency().equals(currency)) {
                    problems.add(new DeliveryProblem(DeliveryProblem.Code.DELIVERY_CURRENCY_MISMATCH, n, key, null,
                            "Entry " + n + ": " + key + " is in " + spec.currency() + " here, and the delivery "
                                    + (currency.isEmpty() ? "names no currency" : "says " + safeCurrency(currency))));
                    return;
                }
            } else if (f.has("currency")) {
                throw new JsonException(where + " has a currency, which only MONEY fields have");
            }
            if (spec.multiValue() != f.has("values")) {
                problems.add(new DeliveryProblem(DeliveryProblem.Code.DELIVERY_VALUE_COUNT_MISMATCH, n, key, null,
                        "Entry " + n + ": " + key + (spec.multiValue()
                                ? " takes a list of values here (\"values\")" : " takes one value here (\"value\")")));
                return;
            }
            List<FieldValue> values = new ArrayList<>();
            if (spec.multiValue()) {
                for (Object v : f.list("values")) {
                    values.add(value(spec, v, where));
                }
            } else {
                values.add(value(spec, f.raw("value"), where));
            }
            FieldValidator.Result r = FieldValidator.checkAll(spec, values);
            for (ValueProblem p : r.problems()) {
                problems.add(new DeliveryProblem(DeliveryProblem.Code.DELIVERY_VALUE_INVALID, n, key, p.code(),
                        "Entry " + n + ": " + p.message()));
            }
            if (r.ok()) {
                valid.put(key, r.values());
            }
        } catch (JsonException e) {
            problems.add(new DeliveryProblem(DeliveryProblem.Code.DELIVERY_SHAPE_INVALID, n, key, null,
                    e.getMessage()));
        }
    }

    private static FieldValue value(FieldSpec spec, Object v, String where) {
        if (spec.type() == FieldType.ADDRESS) {
            JsonFields a = only(JsonFields.of(v, where + ".value"), ADDRESS, where + ".value");
            return new FieldValue.Address(part(a, "street"), part(a, "street2"), part(a, "city"), part(a, "state"), part(a, "zip"),
                    a.has("country") ? a.string("country") : null);
        }
        if (!(v instanceof String s)) {
            throw new JsonException(where + ": a " + spec.type() + " value is a string");
        }
        return new FieldValue.Text(s);
    }

    /** Refuses members outside {@code allowed} without naming them: a name could be anything the sender wrote. */
    private static JsonFields only(JsonFields f, Set<String> allowed, String where) {
        long unknown = f.map().keySet().stream().filter(k -> !allowed.contains(k)).count();
        if (unknown > 0) {
            throw new JsonException(where + " has " + unknown + " unknown member(s); allowed are "
                    + new TreeSet<>(allowed));
        }
        return f;
    }

    private static String part(JsonFields a, String name) {
        return a.has(name) ? a.string(name) : null;
    }

    private static Object json(FieldValue v) {
        if (v instanceof FieldValue.Text t) {
            return t.value();
        }
        FieldValue.Address a = (FieldValue.Address) v;
        Map<String, Object> m = new TreeMap<>();
        m.put("street", a.street());
        if (a.street2() != null) {
            m.put("street2", a.street2());
        }
        m.put("city", a.city());
        m.put("state", a.state());
        m.put("zip", a.zip());
        m.put("country", a.country() == null ? FieldValue.Address.US : a.country());
        return m;
    }

    private static boolean known(String type) {
        for (FieldType t : FieldType.values()) {
            if (t.name().equals(type)) {
                return true;
            }
        }
        return false;
    }

    private static String safeCurrency(String c) {
        return c.matches("[A-Z]{3}") ? c : "a currency that is not a code";
    }

    private static DeliveryResult refused(DeliveryProblem.Code code, String message) {
        return new DeliveryResult(Map.of(), List.of(new DeliveryProblem(code, null, null, null, message)));
    }
}
