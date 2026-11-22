package com.documentplatform.integration.json;

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
 * A small JSON Schema checker (the subset that profile files and OpenAPI 3.1 documents use) and a sample builder.
 * Supported: {@code $ref} (local, {@code #/...}, resolved in the given root document), {@code allOf},
 * {@code oneOf}/{@code anyOf}, {@code type} (also a list of types), {@code const}, {@code enum}, {@code required},
 * {@code properties}, {@code additionalProperties} (a schema or false), {@code items}, {@code minItems},
 * {@code maxItems}, {@code uniqueItems}, {@code minLength}/{@code maxLength}, {@code pattern},
 * {@code minimum}/{@code maximum}, {@code format} uuid and date-time. Each problem names the place as a JSON pointer
 * ({@code /steps/0/path}) and the rule broken, never the value.
 */
public final class JsonSchema {

    /** One problem: where (a JSON pointer, {@code ""} for the whole document) and what. */
    public record Problem(String pointer, String what) {
        @Override
        public String toString() {
            return (pointer.isEmpty() ? "/" : pointer) + ": " + what;
        }
    }

    private static final Pattern UUID_FORMAT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final Map<String, Object> root;

    /** @param root the document {@code $ref}s point into (the schema file, or an OpenAPI document) */
    public JsonSchema(Map<String, Object> root) {
        this.root = root;
    }

    /** Follows local {@code $ref}s. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> resolve(Map<String, Object> node) {
        Map<String, Object> cur = node;
        for (int depth = 0; cur != null && cur.containsKey("$ref") && depth < 20; depth++) {
            String ref = (String) cur.get("$ref");
            if (!ref.startsWith("#")) throw new IllegalArgumentException("Only local $ref is supported");
            Object at = Json.at(root, ref.substring(1)).orElseThrow(() -> new IllegalArgumentException(
                    "Unknown $ref " + ref));
            cur = (Map<String, Object>) at;
        }
        return cur;
    }

    /** The problems of {@code value} against {@code schema}; empty when it fits. */
    public List<Problem> check(Object value, Map<String, Object> schema) {
        List<Problem> out = new ArrayList<>();
        check(value, schema, "", out, 0);
        return out;
    }

    @SuppressWarnings("unchecked")
    private void check(Object v, Map<String, Object> schemaIn, String at, List<Problem> out, int depth) {
        if (depth > 60 || schemaIn == null) return;
        Map<String, Object> s = resolve(schemaIn);
        if (s.get("allOf") instanceof List<?> all) {
            for (Object sub : all) check(v, map(sub), at, out, depth + 1);
        }
        for (String key : List.of("oneOf", "anyOf")) {
            if (s.get(key) instanceof List<?> any) {
                boolean fits = false;
                for (Object sub : any) {
                    if (check(v, map(sub)).isEmpty()) {
                        fits = true;
                        break;
                    }
                }
                if (!fits) out.add(new Problem(at, "fits none of " + key));
            }
        }
        if (s.containsKey("const") && !java.util.Objects.equals(s.get("const"), v)) {
            out.add(new Problem(at, "not the required value"));
        }
        if (s.get("enum") instanceof List<?> en && !en.contains(v)) out.add(new Problem(at, "not one of the listed values"));
        Object type = s.get("type");
        if (type != null) {
            List<String> types = type instanceof List<?> l ? (List<String>) l : List.of((String) type);
            if (types.stream().noneMatch(t -> is(v, t))) {
                out.add(new Problem(at, "not of type " + String.join(" or ", types)));
                return;
            }
        }
        if (v instanceof String str) {
            if (s.get("minLength") instanceof Long min && str.length() < min) out.add(new Problem(at, "too short"));
            if (s.get("maxLength") instanceof Long max && str.length() > max) out.add(new Problem(at, "too long"));
            if (s.get("pattern") instanceof String p && !Pattern.compile(p).matcher(str).find()) {
                out.add(new Problem(at, "does not match its pattern"));
            }
            if ("uuid".equals(s.get("format")) && !UUID_FORMAT.matcher(str).matches()) out.add(new Problem(at, "not a UUID"));
            if ("date-time".equals(s.get("format"))) {
                try {
                    java.time.OffsetDateTime.parse(str);
                } catch (RuntimeException e) {
                    out.add(new Problem(at, "not a date-time"));
                }
            }
        }
        if (v instanceof Number n) {
            if (s.get("minimum") instanceof Number min && n.doubleValue() < min.doubleValue()) out.add(new Problem(at, "below its minimum"));
            if (s.get("maximum") instanceof Number max && n.doubleValue() > max.doubleValue()) out.add(new Problem(at, "above its maximum"));
        }
        if (v instanceof Map<?, ?> m) {
            if (s.get("required") instanceof List<?> req) {
                for (Object r : req) {
                    if (!m.containsKey(r)) out.add(new Problem(at + "/" + escape(String.valueOf(r)), "required"));
                }
            }
            Map<String, Object> props = s.get("properties") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                String child = at + "/" + escape(k);
                if (props.containsKey(k)) {
                    check(e.getValue(), map(props.get(k)), child, out, depth + 1);
                } else if (s.get("additionalProperties") instanceof Map<?, ?> ap) {
                    check(e.getValue(), (Map<String, Object>) ap, child, out, depth + 1);
                } else if (Boolean.FALSE.equals(s.get("additionalProperties"))) {
                    out.add(new Problem(child, "not allowed here"));
                }
            }
        }
        if (v instanceof List<?> l) {
            if (s.get("minItems") instanceof Long min && l.size() < min) out.add(new Problem(at, "too few items"));
            if (s.get("maxItems") instanceof Long max && l.size() > max) out.add(new Problem(at, "too many items"));
            if (s.get("items") instanceof Map<?, ?> items) {
                for (int i = 0; i < l.size(); i++) check(l.get(i), (Map<String, Object>) items, at + "/" + i, out, depth + 1);
            }
            if (Boolean.TRUE.equals(s.get("uniqueItems")) && new HashSet<>(l).size() != l.size()) {
                out.add(new Problem(at, "items are not unique"));
            }
        }
    }

    private static String escape(String key) {
        return key.replace("~", "~0").replace("/", "~1");
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

    /** A value that fits {@code schema}: required properties only, the first enum value or example, empty lists. */
    public Object sample(Map<String, Object> schema) {
        return sample(schema, 0);
    }

    @SuppressWarnings("unchecked")
    private Object sample(Map<String, Object> schemaIn, int depth) {
        if (depth > 30) return null;
        Map<String, Object> s = resolve(schemaIn);
        if (s.get("allOf") instanceof List<?> all && !all.isEmpty()) return sample(map(all.get(0)), depth + 1);
        for (String key : List.of("oneOf", "anyOf")) {
            if (s.get(key) instanceof List<?> any && !any.isEmpty()) return sample(map(any.get(0)), depth + 1);
        }
        if (s.containsKey("const")) return s.get("const");
        if (s.get("enum") instanceof List<?> en && !en.isEmpty()) return en.get(0);
        if (s.get("examples") instanceof List<?> ex && !ex.isEmpty()) return ex.get(0);
        Object type = s.get("type");
        String t = null;
        if (type instanceof List<?> l) {
            for (Object x : l) {
                if (!"null".equals(x)) {
                    t = (String) x;
                    break;
                }
            }
            if (t == null) t = "null";
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
                    if (req.contains(e.getKey())) out.put(e.getKey(), sample(map(e.getValue()), depth + 1));
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        if (!(o instanceof Map)) throw new IllegalArgumentException("Expected a schema object");
        return (Map<String, Object>) o;
    }
}
