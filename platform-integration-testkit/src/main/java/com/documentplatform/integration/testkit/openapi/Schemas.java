package com.documentplatform.integration.testkit.openapi;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * JSON Schema, as OpenAPI 3.1 uses it, enough for contract tests: checking a value against a schema, and building a
 * valid sample value from one. Supported: {@code $ref}, {@code allOf}, {@code oneOf}/{@code anyOf}, {@code type} (also
 * a list of types), {@code enum}, {@code required}, {@code properties}, {@code additionalProperties}, {@code items},
 * {@code uniqueItems}, {@code minLength}/{@code maxLength}, {@code pattern}, {@code minimum}/{@code maximum},
 * {@code format} uuid and date-time. Problems name the place ({@code /metadata/loanId}), never a value.
 */
public final class Schemas {

    private static final Pattern UUID_FORMAT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final OpenApiSpec spec;

    public Schemas(OpenApiSpec spec) {
        this.spec = spec;
    }

    // ---- Checking ----

    /** The problems of {@code value} against {@code schema}; empty when it fits. */
    public List<String> check(Object value, Map<String, Object> schema) {
        List<String> out = new ArrayList<>();
        check(value, schema, "", out, 0);
        return out;
    }

    @SuppressWarnings("unchecked")
    private void check(Object v, Map<String, Object> schemaIn, String at, List<String> out, int depth) {
        if (depth > 40 || schemaIn == null) return;
        Map<String, Object> s = spec.resolve(schemaIn);
        String where = at.isEmpty() ? "/" : at;
        if (s.get("allOf") instanceof List<?> all) {
            for (Object sub : all) check(v, OpenApiSpec.map(sub), at, out, depth + 1);
        }
        for (String key : List.of("oneOf", "anyOf")) {
            if (s.get(key) instanceof List<?> any) {
                boolean fits = false;
                for (Object sub : any) {
                    if (check(v, OpenApiSpec.map(sub)).isEmpty()) {
                        fits = true;
                        break;
                    }
                }
                if (!fits) out.add(where + ": fits none of " + key);
            }
        }
        if (s.get("enum") instanceof List<?> en && !en.contains(v)) out.add(where + ": not one of the listed values");
        Object type = s.get("type");
        if (type != null) {
            List<String> types = type instanceof List<?> l ? (List<String>) l : List.of((String) type);
            if (types.stream().noneMatch(t -> is(v, t))) {
                out.add(where + ": not of type " + types);
                return;
            }
        }
        if (v instanceof String str) {
            if (s.get("minLength") instanceof Long min && str.length() < min) out.add(where + ": too short");
            if (s.get("maxLength") instanceof Long max && str.length() > max) out.add(where + ": too long");
            if (s.get("pattern") instanceof String p && !Pattern.compile(p).matcher(str).find()) {
                out.add(where + ": does not match its pattern");
            }
            if ("uuid".equals(s.get("format")) && !UUID_FORMAT.matcher(str).matches()) out.add(where + ": not a UUID");
            if ("date-time".equals(s.get("format"))) {
                try {
                    java.time.OffsetDateTime.parse(str);
                } catch (RuntimeException e) {
                    out.add(where + ": not a date-time");
                }
            }
        }
        if (v instanceof Number n) {
            if (s.get("minimum") instanceof Number min && n.doubleValue() < min.doubleValue()) out.add(where + ": below its minimum");
            if (s.get("maximum") instanceof Number max && n.doubleValue() > max.doubleValue()) out.add(where + ": above its maximum");
        }
        if (v instanceof Map<?, ?> m) {
            if (s.get("required") instanceof List<?> req) {
                for (Object r : req) {
                    if (!m.containsKey(r)) out.add(where + ": " + r + " is required");
                }
            }
            Map<String, Object> props = s.get("properties") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                String child = at + "/" + k;
                if (props.containsKey(k)) {
                    check(e.getValue(), OpenApiSpec.map(props.get(k)), child, out, depth + 1);
                } else if (s.get("additionalProperties") instanceof Map<?, ?> ap) {
                    check(e.getValue(), (Map<String, Object>) ap, child, out, depth + 1);
                } else if (Boolean.FALSE.equals(s.get("additionalProperties"))) {
                    out.add(child + ": not allowed here");
                }
            }
        }
        if (v instanceof List<?> l) {
            if (s.get("items") instanceof Map<?, ?> items) {
                for (int i = 0; i < l.size(); i++) check(l.get(i), (Map<String, Object>) items, at + "/" + i, out, depth + 1);
            }
            if (Boolean.TRUE.equals(s.get("uniqueItems")) && new HashSet<>(l).size() != l.size()) {
                out.add(where + ": items are not unique");
            }
        }
    }

    private static boolean is(Object v, String type) {
        return switch (type) {
            case "string" -> v instanceof String;
            case "integer" -> v instanceof Long || v instanceof Integer;
            case "number" -> v instanceof Number;
            case "boolean" -> v instanceof Boolean;
            case "object" -> v instanceof Map;
            case "array" -> v instanceof List;
            case "null" -> v == null;
            default -> false;
        };
    }

    // ---- Samples ----

    /** A value that fits {@code schema}: required properties only, the first enum value or example, empty lists. */
    public Object sample(Map<String, Object> schema) {
        return sample(schema, 0);
    }

    @SuppressWarnings("unchecked")
    private Object sample(Map<String, Object> schemaIn, int depth) {
        if (depth > 30) return null;
        Map<String, Object> s = spec.resolve(schemaIn);
        if (s.get("allOf") instanceof List<?> all && !all.isEmpty()) {
            return sample(OpenApiSpec.map(all.get(0)), depth + 1);
        }
        for (String key : List.of("oneOf", "anyOf")) {
            if (s.get(key) instanceof List<?> any && !any.isEmpty()) return sample(OpenApiSpec.map(any.get(0)), depth + 1);
        }
        if (s.get("enum") instanceof List<?> en && !en.isEmpty()) return en.get(0);
        if (s.get("examples") instanceof List<?> ex && !ex.isEmpty()) return ex.get(0);
        Object type = s.get("type");
        String t;
        if (type instanceof List<?> l) {
            t = "null";
            for (Object x : l) {
                if (!"null".equals(x)) {
                    t = (String) x;
                    break;
                }
            }
        } else {
            t = (String) type;
        }
        if (t == null) t = s.containsKey("properties") ? "object" : "string";
        switch (t) {
            case "object" -> {
                Map<String, Object> out = new LinkedHashMap<>();
                Map<String, Object> props = s.get("properties") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
                Set<Object> req = s.get("required") instanceof List<?> r ? new HashSet<>(r) : Set.of();
                for (Map.Entry<String, Object> e : props.entrySet()) {
                    if (req.contains(e.getKey())) out.put(e.getKey(), sample(OpenApiSpec.map(e.getValue()), depth + 1));
                }
                return out;
            }
            case "array" -> {
                return new ArrayList<>();
            }
            case "integer", "number" -> {
                return s.get("minimum") instanceof Number n ? n.longValue() : 0L;
            }
            case "boolean" -> {
                return Boolean.FALSE;
            }
            case "null" -> {
                return null;
            }
            default -> {
                if ("uuid".equals(s.get("format"))) return UUID.randomUUID().toString();
                if ("date-time".equals(s.get("format"))) return Instant.now().toString();
                if ("^[0-9a-f]{64}$".equals(s.get("pattern"))) return "0".repeat(64);
                long min = s.get("minLength") instanceof Long n ? n : 1;
                return "x".repeat((int) Math.max(1, min));
            }
        }
    }
}
