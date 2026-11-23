package com.documentplatform.integration.testkit;

import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.integration.config.CallStep;
import com.documentplatform.integration.config.Endpoint;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.Rule;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.config.Template;
import com.documentplatform.integration.engine.CallEngine;
import com.documentplatform.integration.engine.TestAction;
import com.documentplatform.integration.http.Outbound;
import com.documentplatform.integration.json.Json;
import com.documentplatform.integration.json.JsonSchema;
import com.documentplatform.integration.profile.IntegrationProfile;
import com.documentplatform.integration.result.AttemptResult;
import com.documentplatform.integration.result.HealthResult;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.integration.safety.Installation;
import com.documentplatform.integration.store.IntegrationAudit;
import com.documentplatform.integration.store.StoredIntegration;
import com.documentplatform.integration.testkit.openapi.OpenApiSpec;
import com.documentplatform.notifications.secret.SecretResolver;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The contract-test kit for an API's owner, to run in its own build: does the published profile still fit the
 * published OpenAPI file ({@link #checkAgainstSpec}), and does a running server answer as the profile says
 * ({@link #checkAgainstServer})? Both report every problem they find (where and what, never a value), and neither
 * knows any API.
 *
 * <p>The spec lists each answer's error codes in the extension {@value #CODES_EXTENSION} on the response (a list of
 * strings); every code listed must be covered by one of the profile's rules naming that code (exactly, or by a prefix
 * such as {@code DMS-*}), so a new code without a rule fails the check.
 */
public final class ProfileContract {

    public static final String CODES_EXTENSION = "x-error-codes";
    private static final Set<String> AUTOMATIC_HEADERS = Set.of("content-type", "accept", "x-correlation-id",
            "authorization");

    /** One problem: where (an operation or step) and what. */
    public record Problem(String where, String what) {
        @Override
        public String toString() {
            return where + ": " + what;
        }
    }

    private ProfileContract() {
    }

    /**
     * Every operation the profile uses exists with the same method and path, its required headers are sent and the
     * profile sends no header the spec does not know, the request bodies validate against the request schemas, the
     * values the profile reads are in the answers, and every error code the spec lists maps to a rule.
     */
    public static List<Problem> checkAgainstSpec(IntegrationProfile profile, OpenApiSpec spec) {
        List<Problem> out = new ArrayList<>();
        JsonSchema schemas = new JsonSchema(spec.root());
        for (CallStep s : profile.steps()) {
            String where = "step " + s.name();
            String path = s.path().replaceAll("\\{[^}]+}", "00000000-0000-4000-8000-000000000000");
            Optional<OpenApiSpec.Operation> found = spec.find(s.method().name(), path);
            if (found.isEmpty()) {
                out.add(new Problem(where, s.method() + " " + s.path() + " is not an operation of the spec"));
                continue;
            }
            OpenApiSpec.Operation op = found.get();
            where = where + " (" + op.id() + ")";
            headers(profile, s, op, where, out);
            body(s, op, schemas, where, out);
            reads(s, op, spec, where, out);
            codes(op, s.rules(), profile.rules(), where, out);
            if (s.accepted() != null) accepted(profile, s, op, spec, where, out);
        }
        if (profile.health() != null) {
            Optional<OpenApiSpec.Operation> h = spec.find(profile.health().method().name(), profile.health().path());
            if (h.isEmpty()) {
                out.add(new Problem("health", profile.health().method() + " " + profile.health().path()
                        + " is not an operation of the spec"));
            } else {
                codes(h.get(), profile.health().rules(), profile.rules(), "health (" + h.get().id() + ")", out);
            }
        }
        return out;
    }

    private static void headers(IntegrationProfile profile, CallStep s, OpenApiSpec.Operation op, String where,
                                List<Problem> out) {
        Set<String> sent = new java.util.TreeSet<>();
        s.headers().keySet().forEach(h -> sent.add(h.toLowerCase(Locale.ROOT)));
        String apiKey = profile.apiKeyHeader() == null ? null : profile.apiKeyHeader().toLowerCase(Locale.ROOT);
        Set<String> known = new java.util.TreeSet<>();
        for (OpenApiSpec.Param p : op.params("header")) {
            String name = p.name().toLowerCase(Locale.ROOT);
            known.add(name);
            if (p.required() && !sent.contains(name) && !name.equals(apiKey) && !AUTOMATIC_HEADERS.contains(name)) {
                out.add(new Problem(where, "the spec requires header " + p.name()));
            }
        }
        for (String h : sent) {
            if (!known.contains(h) && !AUTOMATIC_HEADERS.contains(h)) {
                out.add(new Problem(where, "header " + h + " is not in the spec"));
            }
        }
        for (OpenApiSpec.Param p : op.params("path")) {
            if (!s.path().contains("{")) out.add(new Problem(where, "path parameter " + p.name() + " is not filled"));
        }
    }

    private static void body(CallStep s, OpenApiSpec.Operation op, JsonSchema schemas, String where, List<Problem> out) {
        if (op.requestBodies().isEmpty()) {
            if (s.body() != CallStep.Body.NONE) out.add(new Problem(where, "the spec takes no request body"));
            return;
        }
        switch (s.body()) {
            case NONE -> {
                if (op.bodyRequired()) out.add(new Problem(where, "the spec requires a request body"));
            }
            case FILE -> {
                String type = s.headers().entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("Content-Type"))
                        .map(Map.Entry::getValue).findFirst().orElse("application/pdf");
                if (!op.requestBodies().containsKey(type)) {
                    out.add(new Problem(where, "the spec does not take " + type + " (it takes "
                            + op.requestBodies().keySet() + ")"));
                }
            }
            case JSON -> {
                Map<String, Object> schema = op.requestBodies().get("application/json");
                if (schema == null) {
                    out.add(new Problem(where, "the spec does not take application/json"));
                    return;
                }
                validate(s.bodyTemplate(), schema, schemas, where, out);
            }
            case MULTIPART -> {
                Map<String, Object> schema = op.requestBodies().get("multipart/form-data");
                if (schema == null) {
                    out.add(new Problem(where, "the spec does not take multipart/form-data"));
                    return;
                }
                Object part = schemas.resolve(schema).get("properties") instanceof Map<?, ?> props
                        ? props.get(s.multipart().jsonPart()) : null;
                if (part == null) {
                    out.add(new Problem(where, "the spec has no part " + s.multipart().jsonPart()));
                } else {
                    @SuppressWarnings("unchecked") Map<String, Object> ps = (Map<String, Object>) part;
                    validate(s.bodyTemplate(), ps, schemas, where, out);
                }
                Object files = schemas.resolve(schema).get("properties") instanceof Map<?, ?> props
                        ? props.get(s.multipart().filePart()) : null;
                if (files == null) out.add(new Problem(where, "the spec has no part " + s.multipart().filePart()));
            }
        }
    }

    private static void validate(String template, Map<String, Object> schema, JsonSchema schemas, String where,
                                 List<Problem> out) {
        try {
            Object json = Json.parse(Template.parse(template, Template.Place.JSON).fill(sample("pkg-1")));
            for (JsonSchema.Problem p : schemas.check(json, schema)) out.add(new Problem(where, "request body " + p));
        } catch (Template.Problem | Json.Invalid e) {
            out.add(new Problem(where, "the request body cannot be built"));
        }
    }

    /** Sample values: two documents, plain text, numbers where numbers go, mapped fields as text. */
    static Template.Values sample(String id) {
        return new Template.Values() {
            @Override
            public Optional<String> text(String name) {
                return Optional.of(switch (name) {
                    case "package.documentCount" -> "2";
                    case "doc.size" -> "100";
                    case "doc.sha256" -> "0".repeat(64);
                    case "doc.fileName" -> id + ".pdf";
                    case "doc.contentType" -> "application/pdf";
                    default -> id;
                });
            }

            @Override
            public Optional<String> json(String name) {
                return Optional.of("\"" + id + "\"");
            }

            @Override
            public List<Template.Values> documents() {
                return List.of(sample("doc-1"), sample("doc-2"));
            }
        };
    }

    private static void reads(CallStep s, OpenApiSpec.Operation op, OpenApiSpec spec, String where, List<Problem> out) {
        Optional<OpenApiSpec.Answer> ok = op.answers().entrySet().stream().filter(e -> e.getKey().startsWith("2"))
                .map(Map.Entry::getValue).filter(a -> a.schema() != null).findFirst();
        if (s.read().isEmpty()) return;
        if (ok.isEmpty()) {
            out.add(new Problem(where, "the step reads values but the spec's answer has no schema"));
            return;
        }
        Map<String, Object> schema = spec.resolve(ok.get().schema());
        Object props = schema.get("properties");
        for (Map.Entry<String, String> r : s.read().entrySet()) {
            String first = r.getValue().substring(1).split("/")[0];
            if (!(props instanceof Map<?, ?> p) || !p.containsKey(first)) {
                out.add(new Problem(where, "the value " + r.getKey() + " (" + r.getValue() + ") is not in the answer"));
            }
        }
    }

    /**
     * "Accepted, check later": the operation answers 202 with the status reference (the property the pointer names,
     * or a Location header); the status call is an operation of the spec whose 2xx answer holds the state, and
     * every value read from it; its codes are named by rules.
     */
    private static void accepted(IntegrationProfile profile, CallStep s, OpenApiSpec.Operation op, OpenApiSpec spec,
                                 String where, List<Problem> out) {
        CallStep.Accepted a = s.accepted();
        Optional<OpenApiSpec.Answer> accepted = op.answer("202");
        if (accepted.isEmpty()) {
            out.add(new Problem(where, "the step accepts 202 but the spec has no 202 answer"));
        } else if (a.referencePointer() != null) {
            if (!hasProperty(spec, accepted.get().schema(), a.referencePointer())) {
                out.add(new Problem(where, "the status reference (" + a.referencePointer()
                        + ") is not in the 202 answer"));
            }
        } else if (accepted.get().headers() == null || accepted.get().headers().keySet().stream()
                .noneMatch(h -> h.equalsIgnoreCase("Location"))) {
            out.add(new Problem(where, "the status reference is the Location header, which the 202 answer lacks"));
        }
        if (a.statusPath() == null) return; // a Location: the spec's own operation behind it is not named
        String path = a.statusPath().replaceAll("\\{[^}]+}", "00000000-0000-4000-8000-000000000000");
        Optional<OpenApiSpec.Operation> status = spec.find(a.statusMethod().name(), path);
        String sw = where + " status";
        if (status.isEmpty()) {
            out.add(new Problem(sw, a.statusMethod() + " " + a.statusPath() + " is not an operation of the spec"));
            return;
        }
        Optional<OpenApiSpec.Answer> ok = status.get().answers().entrySet().stream()
                .filter(e -> e.getKey().startsWith("2")).map(Map.Entry::getValue).filter(x -> x.schema() != null)
                .findFirst();
        if (ok.isEmpty() || !hasProperty(spec, ok.get().schema(), a.statePointer())) {
            out.add(new Problem(sw, "the state (" + a.statePointer() + ") is not in the status answer"));
        }
        for (Map.Entry<String, String> r : a.read().entrySet()) {
            if (ok.isEmpty() || !hasProperty(spec, ok.get().schema(), r.getValue())) {
                out.add(new Problem(sw, "the value " + r.getKey() + " (" + r.getValue() + ") is not in the status "
                        + "answer"));
            }
        }
        codes(status.get(), s.rules(), profile.rules(), sw + " (" + status.get().id() + ")", out);
    }

    private static boolean hasProperty(OpenApiSpec spec, Map<String, Object> schema, String pointer) {
        if (schema == null) return false;
        String first = pointer.substring(1).split("/")[0];
        return spec.resolve(schema).get("properties") instanceof Map<?, ?> p && p.containsKey(first);
    }

    /** Every listed code of every answer is named by a rule (exactly or by prefix) for that status. */
    private static void codes(OpenApiSpec.Operation op, List<Rule> stepRules, List<Rule> rules, String where,
                              List<Problem> out) {
        List<Rule> all = new ArrayList<>(stepRules);
        all.addAll(rules);
        for (OpenApiSpec.Answer a : op.answers().values()) {
            int status;
            try {
                status = Integer.parseInt(a.status());
            } catch (NumberFormatException e) {
                continue;
            }
            for (String code : a.listExtension(CODES_EXTENSION)) {
                boolean named = all.stream().anyMatch(r -> r.code() != null && !r.code().equals("-")
                        && (r.matches(status, code, null, false) || r.matches(status, code, null, true)));
                if (!named) out.add(new Problem(where, a.status() + " " + code + " has no rule"));
            }
        }
    }

    /**
     * A live check against a running server, with no real data: the health check is healthy or busy, the sign-in works
     * (secrets found, token fetched), and the first step sent with an empty body is refused with a code a rule names.
     *
     * @param signIn       the installation's sign-in for the server (a test client)
     * @param secrets      where its secrets are
     * @param installation the installation rules for the calls (address guard, proxy, private CA)
     */
    public static List<Problem> checkAgainstServer(IntegrationProfile profile, URI baseUrl, SignIn signIn,
                                                   SecretResolver secrets, Installation installation) {
        List<Problem> out = new ArrayList<>();
        // Required fields mapped to a placeholder key: no step that uses them is sent (the refusal call's body is empty).
        Map<String, String> mapping = new java.util.LinkedHashMap<>();
        if (profile.fields() != null) {
            profile.fields().expected().stream().filter(IntegrationProfile.ExpectedField::required)
                    .forEach(f -> mapping.put(f.name(), "contract_check_field"));
        }
        IntegrationConfig c = profile.complete(new IntegrationProfile.Completion(UUID.randomUUID(), "CONTRACT_CHECK",
                "contract check", new Endpoint(baseUrl, true), signIn, mapping, Set.of(), Map.of()));
        try (CallEngine engine = new CallEngine(new Outbound(installation), secrets, Clock.systemUTC())) {
            StoredIntegration stored = new StoredIntegration(c, 1, "contract-check", Instant.now());
            if (c.health() != null) {
                HealthResult h = engine.health(stored);
                if (!h.healthy() && !h.busy()) {
                    out.add(new Problem("health", h.outcome() + " " + h.code()
                            + (h.destinationCode() == null ? "" : " (" + h.destinationCode() + ")")));
                }
            }
            AttemptResult signInCheck = new TestAction(engine, IntegrationAudit.NONE).run(new StoredIntegration(
                    c.withTestMode(IntegrationConfig.TestMode.CHECK_ONLY), 1, "contract-check", Instant.now()),
                    "contract-check");
            if (signInCheck.code() != IntegrationCode.INT_TEST_CHECKED) {
                out.add(new Problem("sign-in", signInCheck.outcome() + " " + signInCheck.code()));
            }
            CallStep first = c.steps().get(0);
            CallStep empty = switch (first.body()) {
                case JSON -> first.withJsonBody("{}");
                case MULTIPART -> first.withMultipartBody("{}", first.multipart());
                default -> first;
            };
            AttemptResult refusal = engine.attempt(new StoredIntegration(c.withSteps(List.of(empty)), 1,
                    "contract-check", Instant.now()), TestAction.fakeDelivery(c.withSteps(List.of(empty))));
            if (refusal.outcome() != Outcome.REFUSED || refusal.destinationCode() == null) {
                out.add(new Problem("refusal", "an empty request to step " + first.name() + " gave "
                        + refusal.outcome() + " " + refusal.code() + ", not a refusal with a code"));
            } else {
                String code = refusal.destinationCode();
                int st = refusal.httpStatus();
                List<Rule> rules = new ArrayList<>(first.rules());
                rules.addAll(c.rules());
                if (rules.stream().noneMatch(r -> r.code() != null && !r.code().equals("-") && r.matches(st, code, null))) {
                    out.add(new Problem("refusal", st + " " + code + " has no rule"));
                }
            }
        }
        return out;
    }
}
