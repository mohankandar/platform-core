package com.documentplatform.integration.testkit.openapi;

import com.documentplatform.integration.json.internal.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small reader of an OpenAPI 3.x document (JSON), enough for a mock and contract tests: operations by method and
 * path, their parameters (with {@code $ref}s resolved), request body schemas per media type, and answers with their
 * schemas, headers and {@code x-} extensions. Generic: nothing here knows a particular API.
 */
public final class OpenApiSpec {

    /** One parameter: where it goes ({@code header}, {@code path}, {@code query}), its name, schema, required. */
    public record Param(String in, String name, boolean required, Map<String, Object> schema) {
    }

    /** One answer of an operation. */
    public record Answer(String status, Map<String, Object> schema, Map<String, Object> headers,
                         Map<String, Object> raw) {
        /** A list-valued {@code x-} extension, for example {@code x-api-codes}. */
        @SuppressWarnings("unchecked")
        public List<String> listExtension(String name) {
            Object v = raw.get(name);
            return v instanceof List<?> l ? (List<String>) l : List.of();
        }
    }

    /** One operation. */
    public record Operation(String id, String method, String pathTemplate, List<Param> params,
                            Map<String, Map<String, Object>> requestBodies, boolean bodyRequired,
                            Map<String, Answer> answers) {
        public List<Param> params(String in) {
            return params.stream().filter(p -> p.in().equals(in)).toList();
        }

        public Optional<Answer> answer(String status) {
            return Optional.ofNullable(answers.get(status));
        }
    }

    private final Map<String, Object> root;
    private final List<Operation> operations = new ArrayList<>();

    private OpenApiSpec(Map<String, Object> root) {
        this.root = root;
        Object paths = root.get("paths");
        if (!(paths instanceof Map<?, ?> pm)) throw new IllegalArgumentException("OpenAPI document has no paths");
        for (Map.Entry<?, ?> p : pm.entrySet()) {
            for (Map.Entry<?, ?> o : ((Map<?, ?>) p.getValue()).entrySet()) {
                String method = String.valueOf(o.getKey()).toUpperCase(Locale.ROOT);
                if (!(o.getValue() instanceof Map<?, ?> op) || !op.containsKey("responses")) continue;
                operations.add(operation(String.valueOf(p.getKey()), method, map(op)));
            }
        }
    }

    public static OpenApiSpec parse(String json) {
        try {
            return new OpenApiSpec(Json.object(json));
        } catch (Json.Invalid e) {
            throw new IllegalArgumentException("OpenAPI document is not JSON");
        }
    }

    public Map<String, Object> root() {
        return root;
    }

    public List<Operation> operations() {
        return List.copyOf(operations);
    }

    public Optional<Operation> operationById(String id) {
        return operations.stream().filter(o -> o.id().equals(id)).findFirst();
    }

    /** The operation for a method and an actual path (template segments {@code {x}} match any one segment). */
    public Optional<Operation> find(String method, String path) {
        for (Operation o : operations) {
            if (o.method().equals(method) && pathParams(o.pathTemplate(), path).isPresent()) return Optional.of(o);
        }
        return Optional.empty();
    }

    /** The path parameters of {@code path} under {@code template}, or empty when it does not match. */
    public static Optional<Map<String, String>> pathParams(String template, String path) {
        String[] t = template.split("/", -1);
        String[] p = path.split("/", -1);
        if (t.length != p.length) return Optional.empty();
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < t.length; i++) {
            Matcher m = Pattern.compile("\\{([^}]+)}").matcher(t[i]);
            if (m.matches()) {
                if (p[i].isEmpty()) return Optional.empty();
                out.put(m.group(1), java.net.URLDecoder.decode(p[i], java.nio.charset.StandardCharsets.UTF_8));
            } else if (!t[i].equals(p[i])) {
                return Optional.empty();
            }
        }
        return Optional.of(out);
    }

    /** Follows a {@code $ref} ({@code #/components/...}); other values are returned as they are. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> resolve(Map<String, Object> node) {
        Map<String, Object> cur = node;
        for (int depth = 0; cur != null && cur.containsKey("$ref") && depth < 20; depth++) {
            String ref = (String) cur.get("$ref");
            if (!ref.startsWith("#/")) throw new IllegalArgumentException("Only local $ref is supported");
            Object at = Json.at(root, ref.substring(1)).orElseThrow(() -> new IllegalArgumentException(
                    "Unknown $ref " + ref));
            cur = (Map<String, Object>) at;
        }
        return cur;
    }

    private Operation operation(String path, String method, Map<String, Object> op) {
        List<Param> params = new ArrayList<>();
        Object ps = op.get("parameters");
        if (ps instanceof List<?> l) {
            for (Object o : l) {
                Map<String, Object> p = resolve(map(o));
                params.add(new Param((String) p.get("in"), (String) p.get("name"),
                        Boolean.TRUE.equals(p.get("required")), p.get("schema") == null ? Map.of() : map(p.get("schema"))));
            }
        }
        Map<String, Map<String, Object>> bodies = new LinkedHashMap<>();
        boolean bodyRequired = false;
        if (op.get("requestBody") instanceof Map<?, ?> rb) {
            Map<String, Object> body = resolve(map(rb));
            bodyRequired = Boolean.TRUE.equals(body.get("required"));
            if (body.get("content") instanceof Map<?, ?> content) {
                for (Map.Entry<?, ?> e : content.entrySet()) {
                    Object schema = ((Map<?, ?>) e.getValue()).get("schema");
                    bodies.put(String.valueOf(e.getKey()), schema == null ? Map.of() : map(schema));
                }
            }
        }
        Map<String, Answer> answers = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) op.get("responses")).entrySet()) {
            Map<String, Object> r = resolve(map(e.getValue()));
            Map<String, Object> schema = null;
            if (r.get("content") instanceof Map<?, ?> content && !content.isEmpty()) {
                Object first = content.values().iterator().next();
                Object s = ((Map<?, ?>) first).get("schema");
                schema = s == null ? null : map(s);
            }
            answers.put(String.valueOf(e.getKey()), new Answer(String.valueOf(e.getKey()), schema,
                    r.get("headers") == null ? Map.of() : map(r.get("headers")), r));
        }
        return new Operation((String) op.get("operationId"), method, path, List.copyOf(params), bodies, bodyRequired,
                answers);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        if (!(o instanceof Map)) throw new IllegalArgumentException("Expected an object in the OpenAPI document");
        return (Map<String, Object>) o;
    }
}
