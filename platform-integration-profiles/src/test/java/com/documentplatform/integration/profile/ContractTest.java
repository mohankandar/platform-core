package com.documentplatform.integration.profile;

import com.documentplatform.integration.config.CallStep;
import com.documentplatform.integration.config.Classification;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.Rule;
import com.documentplatform.integration.config.Template;
import com.documentplatform.integration.json.Json;
import com.documentplatform.integration.result.AttemptResult;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.integration.testkit.openapi.OpenApiSpec;
import com.documentplatform.integration.testkit.openapi.Schemas;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code sdv-ingestion-v1} profile against the pinned specification, in every build: the operations it calls,
 * their required headers and fields, its request bodies against the request schemas, a rule for every answer code,
 * and the mock's answers against the response schemas.
 */
class ContractTest {

    static final OpenApiSpec SPEC = IngestionApiMock.spec();
    static final Schemas SCHEMAS = new Schemas(SPEC);
    static final Profile PROFILE = Profiles.load(IngestionApiMock.PROFILE);
    static final Set<String> AUTOMATIC_HEADERS = Set.of("content-type", "accept", "x-correlation-id", "authorization");

    /** The operation a step calls: its path with each placeholder as one segment. */
    static OpenApiSpec.Operation operation(CallStep s) {
        String path = s.path().replaceAll("\\{[^}]+}", "00000000-0000-4000-8000-000000000000");
        return SPEC.find(s.method().name(), path).orElseThrow(() -> new AssertionError("step " + s.name() + ": "
                + s.method() + " " + s.path() + " is not an operation of the pinned spec"));
    }

    @Test
    void everyStepIsAnOperationWithItsRequiredHeaders() {
        List<String> ids = new ArrayList<>();
        for (CallStep s : PROFILE.steps()) {
            OpenApiSpec.Operation op = operation(s);
            ids.add(op.id());
            Set<String> sent = new TreeSet<>();
            s.headers().keySet().forEach(h -> sent.add(h.toLowerCase(Locale.ROOT)));
            Set<String> known = new TreeSet<>();
            for (OpenApiSpec.Param p : op.params("header")) {
                known.add(p.name().toLowerCase(Locale.ROOT));
                if (p.required()) assertTrue(sent.contains(p.name().toLowerCase(Locale.ROOT)),
                        op.id() + " requires header " + p.name());
            }
            for (String h : sent) {
                assertTrue(known.contains(h) || AUTOMATIC_HEADERS.contains(h), op.id() + ": header " + h
                        + " is not in the spec");
            }
            // Every path parameter is filled by a placeholder.
            Matcher m = Pattern.compile("\\{[^}]+}").matcher(s.path());
            int holes = 0;
            while (m.find()) holes++;
            assertEquals(op.params("path").size(), holes, op.id() + ": path parameters");
            // The values the step reads are in the answer's schema.
            Map<String, Object> answer = SPEC.resolve(op.answer("201").orElseThrow().schema());
            @SuppressWarnings("unchecked") Map<String, Object> props = (Map<String, Object>) answer.get("properties");
            for (String pointer : s.read().values()) {
                assertTrue(props.containsKey(pointer.substring(1).split("/")[0]), op.id() + " answer has " + pointer);
            }
        }
        assertEquals(List.of("createSubmission", "uploadDocument"), ids);
        OpenApiSpec.Operation readiness = SPEC.find("GET", PROFILE.health().path()).orElseThrow();
        assertEquals("checkReadiness", readiness.id());
        assertFalse(PROFILE.health().signIn(), "readiness needs no sign-in (security: [])");
    }

    @Test
    void theRequestBodiesFitTheRequestSchemas() throws Exception {
        CallStep create = PROFILE.steps().get(0);
        OpenApiSpec.Operation op = operation(create);
        assertTrue(op.requestBodies().containsKey("application/json"));
        Map<String, String> mapping = Map.of("contractNumber", "contract_number", "loanAmount", "loan_amount");
        IntegrationConfig completed;
        try (ProfileFx fx = new ProfileFx(false)) {
            completed = fx.config("LOOKUP", mapping, Set.of());
        }
        String body = Template.parse(completed.steps().get(0).bodyTemplate(), Template.Place.JSON).fill(SAMPLE);
        Object json = Json.parse(body);
        assertEquals(List.of(), SCHEMAS.check(json, op.requestBodies().get("application/json")));
        @SuppressWarnings("unchecked") Map<String, Object> create0 = (Map<String, Object>) json;
        assertFalse(create0.containsKey("sensitivityLevel"), "refused on the submission (x-sdv-refused-fields)");
        assertEquals(2, ((List<?>) create0.get("expectedDocuments")).size(), "a complete list, one per document");

        CallStep upload = PROFILE.steps().get(1);
        assertTrue(operation(upload).requestBodies().containsKey(upload.headers().get("Content-Type")));
        assertEquals(CallStep.Body.FILE, upload.body());
    }

    /** Two documents, fake values; field values as JSON numbers / text. */
    static final Template.Values SAMPLE = sample("pkg");

    static Template.Values sample(String id) {
        return new Template.Values() {
        @Override
        public Optional<String> text(String name) {
            return Optional.of(switch (name) {
                case "package.id" -> "pkg-1";
                case "package.documentCount" -> "2";
                default -> id;
            });
        }

        @Override
        public Optional<String> json(String name) {
            return Optional.of(name.endsWith("amount") ? "250000.00" : "\"990023450001\"");
        }

        @Override
        public List<Template.Values> documents() {
            return List.of(sample("doc-1"), sample("doc-2"));
        }
        };
    }

    /** How the spec's own words classify an answer (x-sdv-retry), with the owner's decisions on 503s. */
    static Outcome expected(String status, String code, String retry) {
        if (code.equals("SDV-SYS-018")) return Outcome.BUSY;          // busy: wait Retry-After, no attempt used
        if (code.equals("SDV-AUT-004")) return Outcome.SIGN_IN_FAILED; // a sign-in fault, not busy
        return switch (retry) {
            case "no" -> Outcome.REFUSED;
            case "new-token" -> Outcome.SIGN_IN_FAILED;
            case "after-fix" -> status.startsWith("4") ? Outcome.SIGN_IN_FAILED : Outcome.OUTAGE;
            case "yes" -> Outcome.OUTAGE;
            default -> throw new AssertionError("unknown x-sdv-retry " + retry);
        };
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyAnswerCodeHasItsOwnRule() {
        Map<String, Object> answers = (Map<String, Object>) SPEC.root().get("x-sdv-answers");
        Classification c = Classification.of(PROFILE.rules());
        Map<String, String> table = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : answers.entrySet()) {
            String[] k = e.getKey().split(" ");
            String status = k[0];
            String code = k[1];
            String retry = (String) ((Map<String, Object>) e.getValue()).get("retry");
            boolean explicit = PROFILE.rules().stream().anyMatch(r -> r.status().equals(status)
                    && code.equals(r.code()));
            assertTrue(explicit, "no rule of its own for " + e.getKey() + ": add one to profile.json");
            Classification.Decision d = c.decide(Integer.parseInt(status), code, null,
                    code.equals("SDV-SYS-018"));
            assertEquals(expected(status, code, retry), d.outcome(), e.getKey());
            if (code.equals("SDV-SYS-018")) assertFalse(d.attemptUsed(), "busy uses no attempt");
            table.put(e.getKey(), d.outcome().name());
        }
        // Every code an operation the profile calls can answer is in x-sdv-answers.
        for (CallStep s : PROFILE.steps()) {
            for (OpenApiSpec.Answer a : operation(s).answers().values()) {
                for (String code : a.listExtension("x-sdv-codes")) {
                    assertTrue(answers.containsKey(a.status() + " " + code), a.status() + " " + code
                            + " is not in x-sdv-answers");
                }
            }
        }
        // No rule for a code the spec does not have (a stale rule).
        for (Rule r : PROFILE.rules()) {
            if (r.code() != null && !r.code().equals("-")) {
                assertTrue(answers.containsKey(r.status() + " " + r.code()), "rule for unknown " + r.status() + " "
                        + r.code());
            }
        }
        assertEquals(44, table.size(), "the full table, as in the report");
        // The answers without a code: 429 from a gateway is busy; another 503 is an outage; a replay is received.
        assertEquals(Outcome.BUSY, c.decide(429, null, null).outcome());
        assertEquals(Outcome.OUTAGE, c.decide(503, null, null).outcome());
        assertEquals(Outcome.OUTAGE, c.decide(503, "SDV-SYS-999", null).outcome(), "an unknown 503 code: outage");
        assertEquals(Outcome.REFUSED, c.decide(400, "SDV-INT-999", null).outcome(), "an unknown 4xx code: refused");
    }

    @Test
    void theMockFollowsTheSpecAndItsAnswersFitTheResponseSchemas() {
        try (ProfileFx fx = new ProfileFx(false)) {
            AttemptResult r = fx.engine.attempt(ProfileFx.stored(fx.config("LOOKUP")), fx.delivery(2));
            assertEquals(Outcome.SENT, r.outcome(), r + " " + fx.api.mock.specProblems());
            assertEquals(List.of(), fx.api.mock.specProblems(), "every request fit the spec, every answer its schema");
            assertEquals(List.of("createSubmission", "uploadDocument", "uploadDocument"),
                    fx.api.mock.calls().stream().map(c -> c.operation()).toList());
            // Directly: the answers the mock builds fit the response schemas.
            for (String id : List.of("createSubmission", "uploadDocument")) {
                OpenApiSpec.Answer a = SPEC.operationById(id).orElseThrow().answer("201").orElseThrow();
                assertEquals(List.of(), SCHEMAS.check(SCHEMAS.sample(a.schema()), a.schema()), id);
            }
        }
    }

    @Test
    void theMockRefusesWhatTheSpecRefuses() {
        try (ProfileFx fx = new ProfileFx(false)) {
            IntegrationConfig c = fx.config("LOOKUP");
            CallStep upload = c.steps().get(1);
            Map<String, String> headers = new LinkedHashMap<>(upload.headers());
            headers.remove("X-SDV-Filename");
            CallStep without = new CallStep(upload.name(), upload.method(), upload.path(), headers, upload.body(),
                    upload.bodyTemplate(), upload.repeat(), upload.read(), upload.required(), upload.rules(),
                    upload.multipart(), upload.integrity());
            AttemptResult r = fx.engine.attempt(ProfileFx.stored(c.withSteps(List.of(c.steps().get(0), without))),
                    fx.delivery(1));
            assertEquals(Outcome.REFUSED, r.outcome());
            assertEquals("SDV-SYS-015", r.destinationCode());
            assertTrue(fx.api.mock.specProblems().stream().anyMatch(p -> p.contains("header X-SDV-Filename: missing")),
                    fx.api.mock.specProblems().toString());
        }
    }
}
