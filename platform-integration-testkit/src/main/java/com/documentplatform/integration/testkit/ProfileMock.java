package com.documentplatform.integration.testkit;

import com.documentplatform.integration.config.CallStep;
import com.documentplatform.integration.config.Rule;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.json.internal.Json;
import com.documentplatform.integration.profile.IntegrationProfile;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.integration.testkit.openapi.OpenApiSpec;
import com.documentplatform.notifications.secret.SecretRef;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A {@link MockDestination} driven by any integration profile, and optionally by the API's OpenAPI file:
 * <ul>
 *   <li>without the OpenAPI file, one route per step (its method and path), answering with the values the step reads
 *       (the checksum and size received for the profile's integrity check, IDs otherwise);</li>
 *   <li>with it, spec mode: the spec's operations only, requests checked against it and refused with the profile's
 *       own 400 code, answers built from the response schemas with the values the profile reads.</li>
 * </ul>
 * It expects the sign-in the profile takes, puts error codes where the profile reads them, answers the profile's
 * health path, and offers switches derived from the profile's rules: {@link #busy()}, {@link #down()},
 * {@link #refuse()}, {@link #alreadyReceived()}, {@link #signInRefused()}, {@link #healthBusy()},
 * {@link #healthDown()}. Generic: it knows no API.
 */
public final class ProfileMock implements AutoCloseable, MockDestination.SpecBehaviour {

    public static final String CLIENT_ID = "profile-mock-client";
    public static final String SECRET = "profile-mock-secret-value";
    /** The secret reference of {@link #signIn()}: under {@link ProfileContract#ENTITY} (P044, SEC-10). */
    public static final SecretRef SECRET_REF = com.documentplatform.integration.config.SecretScope.of(
            ProfileContract.ENTITY, "profile-mock-credential");
    public static final Duration RETRY_AFTER = Duration.ofSeconds(5);

    private final IntegrationProfile profile;
    private final OpenApiSpec spec;
    private final MockDestination mock;
    private volatile String wrongSha256;

    private ProfileMock(IntegrationProfile profile, OpenApiSpec spec) {
        this.profile = profile;
        this.spec = spec;
        this.mock = MockDestination.start().errorCodePointer(profile.errorCodePointer());
        if (profile.health() != null) mock.healthPath(profile.health().path());
        String type = profile.signInTypes().get(0);
        switch (type) {
            case "NONE" -> mock.expectNone();
            case "BASIC" -> mock.expectBasic(CLIENT_ID, SECRET);
            case "API_KEY" -> mock.expectApiKey(profile.apiKeyHeader(), SECRET);
            case "BEARER_FIXED" -> mock.expectBearer(SECRET);
            default -> mock.expectOAuth2().registerClient(CLIENT_ID, SECRET);
        }
        // Tokens carry the claims the profile's default settings require (a login server set up as the profile asks).
        Map<String, String> claims = new LinkedHashMap<>();
        for (IntegrationProfile.Setting s : profile.settings().values()) {
            String required = s.values().getOrDefault(s.defaultValue(), Map.of())
                    .get(com.documentplatform.integration.config.IntegrationConfig.REQUIRED_TOKEN_CLAIMS);
            if (required != null) {
                for (String c : required.split(",")) {
                    if (!c.isBlank()) claims.put(c.trim(), java.util.UUID.randomUUID().toString());
                }
            }
        }
        if (!claims.isEmpty()) mock.tokenClaims(claims);
        if (spec != null) {
            mock.specMode(spec, this);
        } else {
            routes();
        }
    }

    /** A mock for the profile; {@code openApi} may be null. */
    public static ProfileMock start(IntegrationProfile profile, OpenApiSpec openApi) {
        return new ProfileMock(profile, openApi);
    }

    public MockDestination mock() {
        return mock;
    }

    public URI address() {
        return mock.address();
    }

    /** The sign-in that this mock accepts, of the profile's first sign-in type (its secret: {@link #SECRET}). */
    public SignIn signIn() {
        return switch (profile.signInTypes().get(0)) {
            case "NONE" -> new SignIn.None();
            case "BASIC" -> new SignIn.Basic(CLIENT_ID, SECRET_REF);
            case "API_KEY" -> new SignIn.ApiKey(profile.apiKeyHeader(), SECRET_REF);
            case "BEARER_FIXED" -> new SignIn.BearerFixed(SECRET_REF);
            default -> SignIn.OAuth2ClientCredentials.secret(mock.tokenAddress(), CLIENT_ID, SECRET_REF, null);
        };
    }

    // ---- Switches derived from the profile's rules ----

    public ProfileMock working() {
        mock.clearRecords();
        mock.working().healthOk().up();
        wrongSha256 = null;
        routes();
        return this;
    }

    /** The profile's first BUSY rule: its status and code, with Retry-After. */
    public ProfileMock busy() {
        Rule r = first(profile.rules(), Outcome.BUSY).orElse(Rule.of("429", null, Outcome.BUSY));
        mock.busy(status(r.status()), exact(r.code()), RETRY_AFTER);
        return this;
    }

    /** Down: the profile's first OUTAGE rule with a status (and its code), else a bare 503. */
    public ProfileMock down() {
        Optional<Rule> r = first(profile.rules(), Outcome.OUTAGE);
        if (r.isPresent() && exact(r.get().code()) != null) mock.busy(status(r.get().status()), exact(r.get().code()), null);
        else mock.outage();
        return this;
    }

    /** Unreachable: not listening. */
    public ProfileMock unreachable() {
        mock.down();
        return this;
    }

    /** The profile's first REFUSED rule with a code. */
    public ProfileMock refuse() {
        Rule r = profile.rules().stream().filter(x -> x.outcome() == Outcome.REFUSED && x.code() != null
                && !x.code().equals("-")).findFirst().orElseThrow(() -> new IllegalStateException("no refusal rule"));
        mock.refuse(status(r.status()), exact(r.code()));
        return this;
    }

    /** The profile's ALREADY_RECEIVED rule: a 200 with its field, or its status and code. */
    public ProfileMock alreadyReceived() {
        Rule r = first(profile.rules(), Outcome.ALREADY_RECEIVED).orElseThrow(() ->
                new IllegalStateException("no already-received rule"));
        if (r.pointer() != null) {
            if (spec != null) {
                mock.alreadyReceived(null);
            } else {
                Map<String, Object> body = new LinkedHashMap<>();
                for (CallStep s : profile.steps()) body.putAll(answerValues(s, true));
                put(body, r.pointer(), literal(r.equals()));
                mock.alreadyReceived(Json.write(body).replace("\"${size}\"", "${size}"));
            }
        } else {
            mock.refuse(status(r.status()), exact(r.code()));
        }
        return this;
    }

    /** The profile's first SIGN_IN_FAILED rule with a code (else 401). */
    public ProfileMock signInRefused() {
        Optional<Rule> r = profile.rules().stream().filter(x -> x.outcome() == Outcome.SIGN_IN_FAILED && x.code() != null
                && !x.code().equals("-")).findFirst();
        if (r.isPresent()) mock.refuse(status(r.get().status()), exact(r.get().code()));
        else mock.signInRefused(null);
        return this;
    }

    /** Health busy: the health rules' BUSY code (or Retry-After alone), with Retry-After. */
    public ProfileMock healthBusy() {
        Optional<Rule> r = profile.health() == null ? Optional.empty() : first(profile.health().rules(), Outcome.BUSY);
        mock.healthBusy(r.map(x -> exact(x.code())).orElse(null), RETRY_AFTER);
        return this;
    }

    /** Health down: a bare 503. */
    public ProfileMock healthDown() {
        mock.healthFailing(true);
        return this;
    }

    /** The checksum the answers name instead of the one received (integrity tests). */
    public ProfileMock wrongChecksum(String sha256) {
        wrongSha256 = sha256;
        routes();
        return this;
    }

    private void routes() {
        if (spec != null) return;
        mock.clearRoutes();
        for (CallStep s : profile.steps()) mock.route(s.method().name(), pathRegex(s.path()), 201, answerTemplate(s));
    }

    // ---- Spec mode behaviour ----

    @Override
    public String badRequestCode() {
        return profile.rules().stream().filter(r -> r.status().equals("400") && exact(r.code()) != null)
                .map(r -> exact(r.code())).findFirst()
                .orElse(profile.rules().stream().filter(r -> r.outcome() == Outcome.REFUSED && exact(r.code()) != null)
                        .map(r -> exact(r.code())).findFirst().orElse("BAD-REQUEST"));
    }

    @Override
    public Map<String, Object> error(String code, String reference) {
        return MockDestination.errorBody(profile.errorCodePointer(), code);
    }

    @Override
    public Map<String, Object> answer(MockDestination.SpecRequest r) {
        CallStep s = stepFor(r.method(), r.path());
        Map<String, Object> out = new LinkedHashMap<>();
        if (s == null) return out;
        for (Map.Entry<String, String> e : s.read().entrySet()) {
            String pointer = e.getValue();
            if (pointer.substring(1).contains("/")) continue; // nested: the schema sample stays
            if (s.integrity() != null && e.getKey().equals(s.integrity().sha256Value())) {
                out.put(pointer.substring(1), wrongSha256 != null ? wrongSha256 : r.sha256());
            } else if (s.integrity() != null && e.getKey().equals(s.integrity().sizeValue())) {
                out.put(pointer.substring(1), r.size());
            }
        }
        return out;
    }

    @Override
    public Map<String, Object> replay(MockDestination.SpecRequest r) {
        Map<String, Object> out = new LinkedHashMap<>(answer(r));
        first(profile.rules(), Outcome.ALREADY_RECEIVED).filter(x -> x.pointer() != null)
                .ifPresent(x -> put(out, x.pointer(), literal(x.equals())));
        return out;
    }

    // ---- Helpers ----

    private CallStep stepFor(String method, String path) {
        for (CallStep s : profile.steps()) {
            if (s.method().name().equals(method) && Pattern.matches(pathRegex(s.path()), path)) return s;
        }
        return null;
    }

    private String answerTemplate(CallStep s) {
        return Json.write(answerValues(s, false)).replace("\"${size}\"", "${size}");
    }

    /** The answer's values: integrity checksum and size from the request, IDs otherwise. */
    private Map<String, Object> answerValues(CallStep s, boolean replay) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : s.read().entrySet()) {
            Object v = "${id}";
            if (s.integrity() != null && e.getKey().equals(s.integrity().sha256Value())) {
                v = wrongSha256 != null ? wrongSha256 : "${sha256}";
            } else if (s.integrity() != null && e.getKey().equals(s.integrity().sizeValue())) {
                v = "${size}";
            }
            put(body, e.getValue(), v);
        }
        return body;
    }

    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> into, String pointer, Object value) {
        String[] parts = pointer.substring(1).split("/");
        Map<String, Object> cur = into;
        for (int i = 0; i < parts.length - 1; i++) {
            cur = (Map<String, Object>) cur.computeIfAbsent(parts[i], k -> new LinkedHashMap<>());
        }
        cur.put(parts[parts.length - 1], value);
    }

    private static Object literal(String s) {
        if (s.equals("true") || s.equals("false")) return Boolean.parseBoolean(s);
        return s;
    }

    static String pathRegex(String template) {
        StringBuilder b = new StringBuilder();
        for (String part : template.split("\\{[^}]+}", -1)) {
            if (!b.isEmpty()) b.append("[^/]+");
            b.append(Pattern.quote(part));
        }
        return b.toString();
    }

    private static Optional<Rule> first(List<Rule> rules, Outcome o) {
        return rules.stream().filter(r -> r.outcome() == o).findFirst();
    }

    /** A concrete status for a rule's status pattern. */
    static int status(String pattern) {
        if (pattern.equals("any")) return 400;
        if (pattern.endsWith("xx")) return (pattern.charAt(0) - '0') * 100;
        if (pattern.contains("-")) return Integer.parseInt(pattern.split("-")[0]);
        return Integer.parseInt(pattern);
    }

    /** A concrete code for a rule's code pattern: a prefix gets a suffix; none or "-" gives null. */
    static String exact(String code) {
        if (code == null || code.equals("-")) return null;
        return code.endsWith("*") ? code.substring(0, code.length() - 1) + "TEST" : code;
    }

    @Override
    public void close() {
        mock.close();
    }
}
