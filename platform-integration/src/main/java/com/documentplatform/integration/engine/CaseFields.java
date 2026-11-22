package com.documentplatform.integration.engine;

import com.documentplatform.fields.FieldKey;
import com.documentplatform.fields.FieldSet;
import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.FieldType;
import com.documentplatform.fields.FieldValue;
import com.documentplatform.fields.ValidValue;
import com.documentplatform.fields.ValidatedFields;
import com.documentplatform.fields.delivery.DeliveryResult;
import com.documentplatform.fields.delivery.FieldDelivery;
import com.documentplatform.integration.json.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A delivery's case field values for {@code {field:<key>}} placeholders: platform-fields' delivery format
 * ({@code platform-field-values/1}, key + type + value or values) together with the field list, which gives each
 * field's type and whether it is sensitive. Numbers, amounts, dates and UUIDs are used in their normalised form
 * (MONEY {@code 250000.00}, DATE {@code 2026-11-21}); other text as entered. Nothing here is logged, and
 * {@link #toString()} shows no value.
 */
public final class CaseFields {

    /** One field: its key, type, values (text, or a map for an address) and whether it is sensitive. */
    public record Field(String key, FieldType type, List<Object> values, boolean multiValue, boolean sensitive) {
        public Field {
            values = List.copyOf(values);
        }

        @Override
        public String toString() {
            return "Field[" + key + ", " + type + (sensitive ? ", sensitive" : "") + "]";
        }
    }

    /** The values are not a usable delivery for the field list. Carries codes only, no value. */
    public static final class Invalid extends IllegalArgumentException {
        public Invalid(String what) {
            super("Case field values are not usable (" + what + ")");
        }
    }

    private static final Set<FieldType> NUMBERS = Set.of(FieldType.NUMBER, FieldType.DECIMAL, FieldType.MONEY);
    private static final Set<FieldType> NORMALISED = Set.of(FieldType.NUMBER, FieldType.DECIMAL, FieldType.MONEY,
            FieldType.DATE, FieldType.DATE_TIME, FieldType.UUID);
    private static final Pattern JSON_NUMBER = Pattern.compile("-?(0|[1-9]\\d{0,30})(\\.\\d{1,30})?");

    public static final CaseFields NONE = new CaseFields(Map.of(), null);

    private final Map<String, Field> fields;
    private final String document;

    private CaseFields(Map<String, Field> fields, String document) {
        this.fields = Collections.unmodifiableMap(fields);
        this.document = document;
    }

    /** From the sender's field list and the case's accepted values (the delivery document is written here). */
    public static CaseFields of(FieldSet sending, ValidatedFields values) {
        String document = FieldDelivery.write(sending, values);
        return build(sending, values.values(), document);
    }

    /**
     * From a platform-field-values/1 document the app already has, checked against the field list with
     * {@link FieldDelivery#verify}.
     *
     * @throws Invalid with the delivery's problem codes when it is not accepted
     */
    public static CaseFields fromDocument(String document, FieldSet fields) {
        DeliveryResult r = FieldDelivery.verify(Objects.requireNonNull(fields, "fields"), document);
        if (!r.accepted()) throw new Invalid(r.codes().toString());
        return build(fields, r.values(), document);
    }

    private static CaseFields build(FieldSet list, Map<FieldKey, List<ValidValue>> values, String document) {
        Map<String, Field> out = new LinkedHashMap<>();
        for (Map.Entry<FieldKey, List<ValidValue>> e : values.entrySet()) {
            FieldSpec spec = list.find(e.getKey()).orElseThrow(() -> new Invalid("a value for a field not in the list"));
            List<Object> vs = new ArrayList<>();
            for (ValidValue v : e.getValue()) {
                FieldValue fv = NORMALISED.contains(spec.type()) ? v.normalised() : v.display();
                if (fv instanceof FieldValue.Text t) {
                    vs.add(t.value());
                } else {
                    FieldValue.Address a = (FieldValue.Address) fv;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("street", a.street());
                    if (a.street2() != null) m.put("street2", a.street2());
                    m.put("city", a.city());
                    m.put("state", a.state());
                    m.put("zip", a.zip());
                    m.put("country", a.country() == null ? FieldValue.Address.US : a.country());
                    vs.add(m);
                }
            }
            out.put(spec.key().value(), new Field(spec.key().value(), spec.type(), vs, spec.multiValue(),
                    spec.sensitive()));
        }
        return new CaseFields(out, document);
    }

    public Optional<Field> field(String key) {
        return Optional.ofNullable(fields.get(key));
    }

    /** The platform-field-values/1 document, or null for {@link #NONE}. */
    public String document() {
        return document;
    }

    /** The field's value as text: a single value that is not an address; empty otherwise. */
    public static Optional<String> text(Field f) {
        if (f.multiValue() || f.values().size() != 1 || !(f.values().get(0) instanceof String s)) return Optional.empty();
        return Optional.of(s);
    }

    /**
     * The field's value as a JSON literal, by its type: NUMBER, DECIMAL and MONEY as numbers (a value that is not a
     * JSON number, such as an account number with leading zeros, gives empty: put the placeholder in quotes to send it
     * as text); ADDRESS as an object of its parts; every other type as a string; a multi-value field as a list.
     */
    public static Optional<String> json(Field f) {
        List<String> items = new ArrayList<>();
        for (Object v : f.values()) {
            if (v instanceof Map<?, ?> m) {
                items.add(Json.write(m));
            } else if (NUMBERS.contains(f.type())) {
                String s = (String) v;
                if (!JSON_NUMBER.matcher(s).matches()) return Optional.empty();
                items.add(s);
            } else {
                items.add(Json.quote((String) v));
            }
        }
        if (f.multiValue()) return Optional.of("[" + String.join(",", items) + "]");
        return items.size() == 1 ? Optional.of(items.get(0)) : Optional.empty();
    }

    @Override
    public String toString() {
        return "CaseFields[" + fields.size() + " fields]";
    }
}
