package com.documentplatform.integration.profile;

import com.documentplatform.integration.config.CallStep;
import com.documentplatform.integration.config.ConfigJson;
import com.documentplatform.integration.config.HealthCheck;
import com.documentplatform.integration.config.HttpMethod;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.Kind;
import com.documentplatform.integration.config.Limits;
import com.documentplatform.integration.config.Rule;
import com.documentplatform.integration.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Loads ready-made integration profiles. A profile is data, named after the published API it talks to, kept as
 * {@code profiles/<name>/profile.json} on the classpath next to the pinned API specification it was built from. No
 * profile is code: the same loader reads every profile, and a customer extension can ship the same shape.
 */
public final class Profiles {

    public static final String FORMAT = "platform-integration-profile/1";
    /** The profiles this release ships. */
    public static final List<String> SHIPPED = List.of("sdv-ingestion-v1");

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    private Profiles() {
    }

    /** The profile of that name. */
    public static Profile load(String name) {
        return parse(resource(name, "profile.json"));
    }

    /** A file next to the profile (for example its pinned specification), as text. */
    public static String resource(String profile, String file) {
        if (profile == null || !NAME.matcher(profile).matches() || file == null || !file.matches("[A-Za-z0-9._-]{1,100}")) {
            throw new IllegalArgumentException("Profile or file name is not valid");
        }
        String path = "profiles/" + profile + "/" + file;
        try (InputStream in = Profiles.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalArgumentException("No profile file " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A profile from its JSON text. */
    @SuppressWarnings("unchecked")
    public static Profile parse(String json) {
        Map<String, Object> m;
        try {
            m = Json.object(json);
        } catch (Json.Invalid e) {
            throw new IllegalArgumentException("Profile is not JSON");
        }
        if (!FORMAT.equals(m.get("format"))) throw new IllegalArgumentException("Profile format must be " + FORMAT);
        String name = (String) m.get("name");
        if (name == null || !NAME.matcher(name).matches()) throw new IllegalArgumentException("Profile name is not valid");
        Map<String, Object> api = (Map<String, Object>) m.getOrDefault("api", Map.of());
        Map<String, Object> l = (Map<String, Object>) m.get("limits");
        Limits limits = l == null ? Limits.DEFAULT : new Limits(Duration.ofMillis((Long) l.get("connectTimeoutMs")),
                Duration.ofMillis((Long) l.get("readTimeoutMs")), (Long) l.get("maxFileBytes"),
                ((Long) l.get("maxDocuments")).intValue(), ((Long) l.get("maxResponseBytes")).intValue(),
                (Long) l.getOrDefault("maxPackageBytes", Limits.DEFAULT_PACKAGE_BYTES));
        List<CallStep> steps = ConfigJson.readSteps(Json.write(m.get("steps")));
        List<Rule> rules = ConfigJson.readRules(Json.write(m.getOrDefault("rules", List.of())));
        HealthCheck health = null;
        if (m.get("health") instanceof Map<?, ?> h) {
            health = new HealthCheck(HttpMethod.valueOf((String) h.get("method")), (String) h.get("path"),
                    Boolean.TRUE.equals(h.get("signIn")), ConfigJson.readRules(Json.write(h.get("rules"))));
        }
        String mappingStep = null;
        String mappingObject = null;
        if (m.get("fieldMapping") instanceof Map<?, ?> fm) {
            mappingStep = (String) fm.get("step");
            mappingObject = (String) fm.get("object");
        }
        Map<String, Profile.DocumentAttribute> attributes = new LinkedHashMap<>();
        if (m.get("documentAttributes") instanceof Map<?, ?> da) {
            for (Map.Entry<?, ?> e : da.entrySet()) {
                Map<String, Object> a = (Map<String, Object>) e.getValue();
                attributes.put((String) e.getKey(), new Profile.DocumentAttribute((String) e.getKey(),
                        (List<String>) a.get("values"), (String) a.get("default")));
            }
        }
        Map<String, Profile.Setting> settings = new LinkedHashMap<>();
        if (m.get("settings") instanceof Map<?, ?> st) {
            for (Map.Entry<?, ?> e : st.entrySet()) {
                Map<String, Object> s = (Map<String, Object>) e.getValue();
                Map<String, Map<String, String>> values = new LinkedHashMap<>();
                ((Map<String, Object>) s.get("values")).forEach((k, v) -> {
                    Map<String, String> means = new LinkedHashMap<>();
                    ((Map<String, Object>) v).forEach((kk, vv) -> means.put(kk, (String) vv));
                    values.put(k, means);
                });
                settings.put((String) e.getKey(), new Profile.Setting((String) e.getKey(), (String) s.get("default"),
                        values));
            }
        }
        List<String> signIn = new ArrayList<>();
        if (m.get("signIn") instanceof List<?> si) si.forEach(x -> signIn.add((String) x));
        return new Profile(name, (String) m.get("description"), (String) api.get("spec"), (String) api.get("version"),
                Kind.valueOf((String) m.getOrDefault("kind", "API_PUSH")), signIn, (String) m.get("errorCodePointer"),
                !Boolean.FALSE.equals(m.get("sendCorrelationId")),
                IntegrationConfig.TestMode.valueOf((String) m.getOrDefault("testMode", "FULL")), limits, steps, rules,
                health, mappingStep, mappingObject, attributes, settings);
    }
}
