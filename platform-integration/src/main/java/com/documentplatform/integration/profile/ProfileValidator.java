package com.documentplatform.integration.profile;

import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.integration.config.CallStep;
import com.documentplatform.integration.config.ConfigCheck;
import com.documentplatform.integration.config.ConfigJson;
import com.documentplatform.integration.config.ConfigProblem;
import com.documentplatform.integration.config.Direction;
import com.documentplatform.integration.config.Endpoint;
import com.documentplatform.integration.config.HealthCheck;
import com.documentplatform.integration.config.HttpMethod;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.Kind;
import com.documentplatform.integration.config.Limits;
import com.documentplatform.integration.config.Packaging;
import com.documentplatform.integration.config.PollSource;
import com.documentplatform.integration.config.Rule;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.config.Template;
import com.documentplatform.integration.json.internal.Json;
import com.documentplatform.integration.json.JsonSchema;
import com.documentplatform.outbound.Installation;
import com.documentplatform.notifications.secret.SecretRef;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks a profile ({@code platform-integration-profile/1}) and builds it. In order: JSON, the format (a format this
 * version does not know is refused), the format's JSON Schema
 * ({@value #SCHEMA_RESOURCE}), steps or a packaging preset, rules, no installation data (no address, nothing that
 * looks like a secret, no case field key), declared values and attributes, settings, and then the same checks a
 * configuration gets when it is saved ({@link ConfigCheck}: placeholders, reserved headers, step values, bodies).
 * Every problem has an {@code INT_PROFILE_*} code and a JSON pointer, never a value.
 */
public final class ProfileValidator {

    public static final String FORMAT = "platform-integration-profile/1";
    public static final String SCHEMA_RESOURCE = "schema/platform-integration-profile-1.schema.json";

    /** The engine settings a profile setting may stand for. */
    public static final Set<String> ENGINE_SETTINGS = Set.of(IntegrationConfig.REQUIRED_TOKEN_CLAIMS);

    /** The result: the profile when there are no problems, else the problems. */
    public record Result(IntegrationProfile profile, List<ProfileProblem> problems) {
        public Result {
            problems = List.copyOf(problems);
        }

        public boolean valid() {
            return problems.isEmpty();
        }
    }

    private static final Pattern ADDRESS = Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]*://");
    private static final Pattern SECRET = Pattern.compile("(?i)eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.|-----BEGIN"
            + "|\\b(password|passwd|secret|api[_-]?key|bearer)\\s*[:=]");
    private static final Map<String, Object> SCHEMA = loadSchema();

    private ProfileValidator() {
    }

    private static Map<String, Object> loadSchema() {
        try (InputStream in = ProfileValidator.class.getClassLoader().getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) throw new IllegalStateException("Profile schema missing from the classpath");
            return Json.object(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (Json.Invalid e) {
            throw new IllegalStateException("Profile schema is not JSON");
        }
    }

    /** The format's JSON Schema, as text (for API owners' own tools). */
    public static String schema() {
        return Json.write(SCHEMA);
    }

    public static Result validate(String text) {
        List<ProfileProblem> problems = new ArrayList<>();
        Map<String, Object> m;
        try {
            m = Json.object(text);
        } catch (Json.Invalid e) {
            return new Result(null, List.of(new ProfileProblem(IntegrationCode.INT_PROFILE_NOT_JSON, "", null)));
        }
        if (!FORMAT.equals(m.get("format"))) {
            return new Result(null, List.of(new ProfileProblem(IntegrationCode.INT_PROFILE_FORMAT_UNKNOWN, "/format",
                    "only " + FORMAT)));
        }
        for (JsonSchema.Problem p : new JsonSchema(SCHEMA).check(m, SCHEMA)) {
            problems.add(new ProfileProblem(IntegrationCode.INT_PROFILE_SCHEMA, p.pointer(), p.what()));
        }
        if (!problems.isEmpty()) return new Result(null, problems);

        installationData(m, "", problems);
        boolean polling = "API_POLL".equals(m.get("kind"));
        PollSource poll = null;
        List<CallStep> steps = List.of();
        if (polling) {
            if (m.containsKey("steps") || m.containsKey("packaging") || m.containsKey("fields")
                    || m.containsKey("documentAttributes")) {
                problems.add(new ProfileProblem(IntegrationCode.INT_PROFILE_STEPS, "",
                        "API_POLL has poll, not steps, packaging, fields or document attributes"));
            }
            if (!"INBOUND".equals(m.get("direction"))) {
                problems.add(new ProfileProblem(IntegrationCode.INT_PROFILE_STEPS, "/direction", "API_POLL is INBOUND"));
            }
            poll = poll(m, problems);
        } else {
            if (m.containsKey("poll")) {
                problems.add(new ProfileProblem(IntegrationCode.INT_PROFILE_STEPS, "/poll", "only for API_POLL"));
            }
            steps = steps(m, problems);
        }
        List<Rule> rules = rules(m.get("rules"), "/rules", problems);
        HealthCheck health = health(m, problems);
        List<String> signIn = strings(map(m.get("signIn")).get("types"));
        String apiKeyHeader = (String) map(m.get("signIn")).get("apiKeyHeader");
        if (signIn.contains("API_KEY") && apiKeyHeader == null) {
            problems.add(new ProfileProblem(IntegrationCode.INT_PROFILE_SIGN_IN, "/signIn/apiKeyHeader", "required for API_KEY"));
        }
        if (!problems.isEmpty()) return new Result(null, problems);

        IntegrationProfile profile = build(m, steps, poll, rules, health, signIn, apiKeyHeader);
        declared(profile, problems);
        if (!problems.isEmpty()) return new Result(null, problems);

        // The checks a configuration gets when it is saved, on the profile completed with placeholder installation data.
        Map<String, String> mapping = new LinkedHashMap<>();
        if (profile.fields() != null) {
            profile.fields().expected().forEach(f -> mapping.put(f.name(), "profile_check_field"));
        }
        UUID checkEntity = UUID.randomUUID();
        IntegrationConfig check = profile.complete(new IntegrationProfile.Completion(checkEntity, "PROFILE_CHECK",
                "profile check", new Endpoint(URI.create("https://installation.invalid"), true),
                placeholderSignIn(checkEntity, signIn.get(0), apiKeyHeader), mapping, Set.of(), Map.of()));
        for (ConfigProblem p : ConfigCheck.check(check, Installation.onPremises().withSignInNone(true)).errors()) {
            problems.add(new ProfileProblem(map(p.code()), pointer(p.where(), m.containsKey("packaging")), p.code().name()));
        }
        return problems.isEmpty() ? new Result(profile, List.of()) : new Result(null, problems);
    }

    private static List<CallStep> steps(Map<String, Object> m, List<ProfileProblem> problems) {
        boolean hasSteps = m.containsKey("steps");
        boolean hasPreset = m.containsKey("packaging");
        if (hasSteps == hasPreset) {
            problems.add(new ProfileProblem(IntegrationCode.INT_PROFILE_STEPS, "", "steps or packaging, exactly one"));
            return List.of();
        }
        if (hasSteps) {
            List<CallStep> out = new ArrayList<>();
            List<Object> raw = list(m.get("steps"));
            for (int i = 0; i < raw.size(); i++) {
                Map<String, Object> s = new LinkedHashMap<>(map(raw.get(i)));
                s.putIfAbsent("headers", Map.of());
                s.putIfAbsent("read", Map.of());
                s.putIfAbsent("required", List.of());
                if (s.get("rules") instanceof List<?> sr) rules(sr, "/steps/" + i + "/rules", problems);
                try {
                    out.addAll(ConfigJson.readSteps(Json.write(List.of(s))));
                } catch (ConfigJson.Unreadable e) {
                    problems.add(new ProfileProblem(IntegrationCode.INT_PROFILE_STEPS, "/steps/" + i, "not a usable step"));
                }
            }
            return out;
        }
        Map<String, Object> p = map(m.get("packaging"));
        String preset = (String) p.get("preset");
        List<String> need = switch (preset) {
            case "TWO_STEP" -> List.of("createPath", "createBody", "idValue", "idPointer", "uploadPath");
            case "MULTIPART" -> List.of("path", "jsonTemplate", "jsonPart", "filePart");
            default -> List.of("path");
        };
        for (String n : need) {
            if (!p.containsKey(n)) problems.add(new ProfileProblem(IntegrationCode.INT_PROFILE_STEPS, "/packaging/" + n,
                    "required for " + preset));
        }
        if (!problems.isEmpty()) return List.of();
        Map<String, String> headers = new LinkedHashMap<>();
        if (p.get("headers") instanceof Map<?, ?> h) h.forEach((k, v) -> headers.put((String) k, (String) v));
        try {
            return switch (preset) {
                case "TWO_STEP" -> Packaging.twoStep((String) p.get("createPath"), (String) p.get("createBody"),
                        (String) p.get("idValue"), (String) p.get("idPointer"), (String) p.get("uploadPath"), headers);
                case "MULTIPART" -> Packaging.multipart((String) p.get("path"), (String) p.get("jsonTemplate"),
                        (String) p.get("jsonPart"), (String) p.get("filePart"));
                default -> Packaging.filePerCall((String) p.get("path"), headers);
            };
        } catch (IllegalArgumentException e) {
            problems.add(new ProfileProblem(IntegrationCode.INT_PROFILE_STEPS, "/packaging", "not a usable preset"));
            return List.of();
        }
    }

    /** The poll section, as a poll source (its calls named list, download and acknowledge). */
    private static PollSource poll(Map<String, Object> m, List<ProfileProblem> problems) {
        if (!(m.get("poll") instanceof Map<?, ?> raw)) {
            problems.add(new ProfileProblem(IntegrationCode.INT_PROFILE_STEPS, "/poll", "required for API_POLL"));
            return null;
        }
        Map<String, Object> p = map(raw);
        Map<String, Object> g = map(p.get("paging"));
        PollSource.Paging paging = new PollSource.Paging(PollSource.Paging.Mode.valueOf((String) g.get("mode")),
                (String) g.get("pointer"), (String) g.get("param"), (String) g.get("sizeParam"),
                (int) num(g, "pageSize", 100), (int) num(g, "firstPage", 0),
                (int) num(g, "maxPagesPerPass", "NONE".equals(g.get("mode")) ? 1 : 10),
                g.get("resume") == null ? PollSource.Paging.Resume.CONTINUE
                        : PollSource.Paging.Resume.valueOf((String) g.get("resume")));
        Map<String, String> values = new LinkedHashMap<>();
        if (p.get("values") instanceof Map<?, ?> v) v.forEach((k, x) -> values.put((String) k, (String) x));
        return new PollSource(pollStep(PollSource.LIST, p.get("list"), problems), (String) p.get("itemsPointer"),
                (String) p.get("idPointer"), values, (String) p.get("fileNameValue"), paging,
                pollStep(PollSource.DOWNLOAD, p.get("download"), problems),
                p.get("acknowledge") == null ? null : pollStep(PollSource.ACKNOWLEDGE, p.get("acknowledge"), problems));
    }

    private static CallStep pollStep(String name, Object raw, List<ProfileProblem> problems) {
        Map<String, Object> s = map(raw);
        Map<String, String> headers = new LinkedHashMap<>();
        if (s.get("headers") instanceof Map<?, ?> h) h.forEach((k, v) -> headers.put((String) k, (String) v));
        String body = (String) s.get("bodyTemplate");
        return new CallStep(name, HttpMethod.valueOf((String) s.get("method")), (String) s.get("path"), headers,
                body == null ? CallStep.Body.NONE : CallStep.Body.JSON, body, CallStep.Repeat.ONCE, Map.of(), Set.of(),
                rules(s.get("rules"), "/poll/" + name + "/rules", problems), null, null, null);
    }

    private static List<Rule> rules(Object raw, String at, List<ProfileProblem> problems) {
        List<Rule> out = new ArrayList<>();
        if (!(raw instanceof List<?> l)) return out;
        for (int i = 0; i < l.size(); i++) {
            try {
                out.addAll(ConfigJson.readRules(Json.write(List.of(l.get(i)))));
            } catch (ConfigJson.Unreadable e) {
                problems.add(new ProfileProblem(IntegrationCode.INT_PROFILE_RULE, at + "/" + i, "not a usable rule"));
            }
        }
        return out;
    }

    private static HealthCheck health(Map<String, Object> m, List<ProfileProblem> problems) {
        if (!(m.get("health") instanceof Map<?, ?> h)) return null;
        return new HealthCheck(HttpMethod.valueOf((String) h.get("method")), (String) h.get("path"),
                Boolean.TRUE.equals(h.get("signIn")), rules(h.get("rules"), "/health/rules", problems));
    }

    /** No address, nothing that looks like a secret, anywhere in the profile. */
    private static void installationData(Object v, String at, List<ProfileProblem> problems) {
        if (v instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                installationData(e.getValue(), at + "/" + String.valueOf(e.getKey()).replace("~", "~0").replace("/", "~1"),
                        problems);
            }
        } else if (v instanceof List<?> l) {
            for (int i = 0; i < l.size(); i++) installationData(l.get(i), at + "/" + i, problems);
        } else if (v instanceof String s) {
            if (ADDRESS.matcher(s).find()) {
                problems.add(new ProfileProblem(IntegrationCode.INT_PROFILE_INSTALLATION_DATA, at, "an address"));
            } else if (SECRET.matcher(s).find()) {
                problems.add(new ProfileProblem(IntegrationCode.INT_PROFILE_INSTALLATION_DATA, at, "looks like a secret"));
            }
        }
    }

    private static IntegrationProfile build(Map<String, Object> m, List<CallStep> steps, PollSource poll,
                                            List<Rule> rules, HealthCheck health, List<String> signIn,
                                            String apiKeyHeader) {
        Map<String, Object> api = map(m.get("api"));
        Limits limits = Limits.DEFAULT;
        if (m.get("limits") instanceof Map<?, ?> l) {
            limits = new Limits(Duration.ofMillis(num(l, "connectTimeoutMs", limits.connectTimeout().toMillis())),
                    Duration.ofMillis(num(l, "readTimeoutMs", limits.readTimeout().toMillis())),
                    num(l, "maxFileBytes", limits.maxFileBytes()), (int) num(l, "maxDocuments", limits.maxDocuments()),
                    (int) num(l, "maxResponseBytes", limits.maxResponseBytes()),
                    num(l, "maxPackageBytes", limits.maxPackageBytes()));
        }
        List<IntegrationProfile.Value> values = new ArrayList<>();
        if (m.get("values") instanceof List<?> vl) {
            for (Object o : vl) {
                Map<String, Object> v = map(o);
                values.add(new IntegrationProfile.Value((String) v.get("name"), Boolean.TRUE.equals(v.get("required")),
                        (String) v.get("description")));
            }
        }
        IntegrationProfile.Fields fields = null;
        if (m.get("fields") instanceof Map<?, ?> f) {
            List<IntegrationProfile.ExpectedField> expected = new ArrayList<>();
            if (f.get("expected") instanceof List<?> el) {
                for (Object o : el) {
                    Map<String, Object> e = map(o);
                    expected.add(new IntegrationProfile.ExpectedField((String) e.get("name"),
                            Boolean.TRUE.equals(e.get("required")), (String) e.get("description")));
                }
            }
            fields = new IntegrationProfile.Fields((String) f.get("step"), (String) f.get("object"), expected,
                    Boolean.TRUE.equals(f.get("others")));
        }
        List<IntegrationProfile.DocumentAttribute> attributes = new ArrayList<>();
        if (m.get("documentAttributes") instanceof List<?> al) {
            for (Object o : al) {
                Map<String, Object> a = map(o);
                attributes.add(new IntegrationProfile.DocumentAttribute((String) a.get("name"), strings(a.get("values")),
                        (String) a.get("default")));
            }
        }
        Map<String, IntegrationProfile.Setting> settings = new LinkedHashMap<>();
        if (m.get("settings") instanceof Map<?, ?> st) {
            for (Map.Entry<?, ?> e : st.entrySet()) {
                Map<String, Object> s = map(e.getValue());
                Map<String, Map<String, String>> vals = new LinkedHashMap<>();
                map(s.get("values")).forEach((k, v) -> {
                    Map<String, String> means = new LinkedHashMap<>();
                    map(v).forEach((kk, vv) -> means.put(kk, (String) vv));
                    vals.put(k, means);
                });
                settings.put((String) e.getKey(), new IntegrationProfile.Setting((String) e.getKey(),
                        (String) s.get("default"), vals));
            }
        }
        List<String> supplies = map(m.get("signIn")).get("installationSupplies") == null ? List.of()
                : strings(map(m.get("signIn")).get("installationSupplies"));
        return new IntegrationProfile((String) m.get("name"), (String) m.get("version"), (String) api.get("title"),
                (String) api.get("version"), (String) m.get("description"), Direction.valueOf((String) m.get("direction")),
                Kind.valueOf((String) m.get("kind")), steps, poll, signIn, supplies, apiKeyHeader, health, rules,
                (String) m.get("errorCodePointer"), !Boolean.FALSE.equals(m.get("sendCorrelationId")),
                IntegrationConfig.TestMode.valueOf((String) m.getOrDefault("testMode", "FULL")), limits, values, fields,
                attributes, settings, m.get("notes") == null ? List.of() : strings(m.get("notes")));
    }

    /** Values, attributes, fields, settings: declared and consistent. */
    private static void declared(IntegrationProfile p, List<ProfileProblem> problems) {
        Set<String> values = new java.util.HashSet<>();
        p.values().forEach(v -> values.add(v.name()));
        Set<String> attrs = new java.util.HashSet<>();
        for (int i = 0; i < p.documentAttributes().size(); i++) {
            IntegrationProfile.DocumentAttribute a = p.documentAttributes().get(i);
            attrs.add(a.name());
            if (!a.values().contains(a.defaultValue())) {
                problems.add(new ProfileProblem(IntegrationCode.INT_PROFILE_ATTRIBUTE, "/documentAttributes/" + i
                        + "/default", "not one of its values"));
            }
        }
        for (int n = 0; n < p.steps().size(); n++) {
            CallStep s = p.steps().get(n);
            List<String> templates = new ArrayList<>(List.of(s.path()));
            templates.addAll(s.headers().values());
            if (s.bodyTemplate() != null) templates.add(s.bodyTemplate());
            if (s.multipart() != null) templates.add(s.multipart().fileNameTemplate());
            for (String t : templates) {
                Matcher mm = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_.:-]{0,120})}").matcher(t);
                while (mm.find()) {
                    String name = mm.group(1);
                    String at = "/steps/" + n;
                    if (name.startsWith("value.") && !values.contains(name.substring(6))) {
                        problems.add(new ProfileProblem(IntegrationCode.INT_PROFILE_VALUE_UNDECLARED, at, name));
                    } else if (name.startsWith(Template.ATTR_PREFIX)
                            && !attrs.contains(name.substring(Template.ATTR_PREFIX.length()))) {
                        problems.add(new ProfileProblem(IntegrationCode.INT_PROFILE_ATTRIBUTE, at, name));
                    } else if (name.startsWith(Template.FIELD_PREFIX)) {
                        problems.add(new ProfileProblem(IntegrationCode.INT_PROFILE_INSTALLATION_DATA, at,
                                "a case field key; map fields through \"fields\""));
                    }
                }
            }
        }
        if (p.fields() != null) {
            CallStep target = p.steps().stream().filter(s -> s.name().equals(p.fields().step())).findFirst().orElse(null);
            if (target == null) {
                problems.add(new ProfileProblem(IntegrationCode.INT_PROFILE_FIELDS, "/fields/step", "no such step"));
            } else if (target.bodyTemplate() == null
                    || !target.bodyTemplate().contains(IntegrationProfile.marker(p.fields().object()))) {
                problems.add(new ProfileProblem(IntegrationCode.INT_PROFILE_FIELDS, "/fields/object",
                        "the step's body has no \"" + p.fields().object() + "\":{}"));
            }
        }
        for (IntegrationProfile.Setting s : p.settings().values()) {
            String at = "/settings/" + s.name();
            if (!s.values().containsKey(s.defaultValue())) {
                problems.add(new ProfileProblem(IntegrationCode.INT_PROFILE_SETTING, at + "/default", "not one of its values"));
            }
            s.values().forEach((value, means) -> means.keySet().forEach(k -> {
                if (!ENGINE_SETTINGS.contains(k)) {
                    problems.add(new ProfileProblem(IntegrationCode.INT_PROFILE_SETTING, at + "/values/" + value + "/" + k,
                            "not an engine setting"));
                }
            }));
        }
    }

    private static SignIn placeholderSignIn(UUID entity, String type, String apiKeyHeader) {
        SecretRef ref = com.documentplatform.integration.config.SecretScope.of(entity, "profile-check");
        return switch (type) {
            case "NONE" -> new SignIn.None();
            case "BASIC" -> new SignIn.Basic("profile-check", ref);
            case "API_KEY" -> new SignIn.ApiKey(apiKeyHeader, ref);
            case "BEARER_FIXED" -> new SignIn.BearerFixed(ref);
            default -> SignIn.OAuth2ClientCredentials.secret(URI.create("https://login.installation.invalid/token"),
                    "profile-check", ref, null);
        };
    }

    private static IntegrationCode map(IntegrationCode c) {
        return switch (c) {
            case INT_CONFIG_PLACEHOLDER_UNKNOWN, INT_CONFIG_PLACEHOLDER_FORBIDDEN, INT_CONFIG_PLACEHOLDER_IN_QUERY,
                 INT_CONFIG_PLACEHOLDER_PLACE, INT_CONFIG_STEP_VALUE_UNKNOWN, INT_CONFIG_TEMPLATE_INVALID,
                 INT_CONFIG_BODY_NOT_JSON, INT_CONFIG_OMIT_PLACE -> IntegrationCode.INT_PROFILE_TEMPLATE;
            case INT_CONFIG_HEADER_RESERVED, INT_CONFIG_HEADER_INVALID -> IntegrationCode.INT_PROFILE_HEADER;
            case INT_CONFIG_RULE_INVALID -> IntegrationCode.INT_PROFILE_RULE;
            case INT_CONFIG_STEPS_INVALID, INT_CONFIG_BODY_INVALID, INT_CONFIG_POLL_INVALID ->
                    IntegrationCode.INT_PROFILE_STEPS;
            case INT_CONFIG_SETTING_INVALID -> IntegrationCode.INT_PROFILE_SETTING;
            case INT_CONFIG_SIGN_IN_NONE_HOSTED, INT_CONFIG_SIGN_IN_NONE_OFF -> IntegrationCode.INT_PROFILE_SIGN_IN;
            default -> IntegrationCode.INT_PROFILE_SCHEMA;
        };
    }

    /** {@code steps[1].headers.X-Name} → {@code /steps/1/headers/X-Name}; a preset's steps point at /packaging. */
    static String pointer(String where, boolean preset) {
        if (preset && where.startsWith("steps")) return "/packaging";
        String p = "/" + where.replaceAll("\\[(\\d+)]", ".$1");
        int headers = p.indexOf(".headers.");
        String tail = "";
        if (headers >= 0) {
            tail = "/" + p.substring(headers + 9).replace("~", "~0").replace("/", "~1");
            p = p.substring(0, headers + 8);
        }
        return p.replace('.', '/') + tail;
    }

    private static long num(Map<?, ?> m, String k, long dflt) {
        return m.get(k) instanceof Long n ? n : dflt;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return (List<Object>) o;
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        for (Object x : (List<?>) o) out.add((String) x);
        return out;
    }
}
