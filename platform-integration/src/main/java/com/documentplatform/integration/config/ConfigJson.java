package com.documentplatform.integration.config;

import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.integration.json.Json;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.notifications.secret.SecretRef;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The stored form of a configuration ({@code platform-integration-config/1}): JSON, secrets only as references. Also
 * gives the flat field list used to record which fields a new version changed.
 */
public final class ConfigJson {

    public static final String FORMAT = "platform-integration-config/1";

    /** The stored text cannot be read as a configuration. Says where, never a value. */
    public static final class Unreadable extends RuntimeException {
        public Unreadable(String where) {
            super("Stored integration configuration is not readable (" + where + ")", null, false, false);
        }
    }

    private ConfigJson() {
    }

    // ---- Writing ----

    public static String write(IntegrationConfig c) {
        return Json.write(toMap(c));
    }

    static Map<String, Object> toMap(IntegrationConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", FORMAT);
        m.put("id", c.id().toString());
        m.put("entity", c.entity().toString());
        m.put("product", c.product());
        m.put("kind", c.kind().name());
        m.put("name", c.name());
        m.put("enabled", c.enabled());
        if (c.endpoint() != null) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("baseAddress", c.endpoint().baseAddress().toString());
            e.put("useProxy", c.endpoint().useProxy());
            m.put("endpoint", e);
        }
        m.put("signIn", signIn(c.signIn()));
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("connectTimeoutMs", c.limits().connectTimeout().toMillis());
        l.put("readTimeoutMs", c.limits().readTimeout().toMillis());
        l.put("maxFileBytes", c.limits().maxFileBytes());
        l.put("maxDocuments", (long) c.limits().maxDocuments());
        l.put("maxResponseBytes", (long) c.limits().maxResponseBytes());
        m.put("limits", l);
        List<Object> steps = new ArrayList<>();
        for (CallStep s : c.steps()) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("name", s.name());
            sm.put("method", s.method().name());
            sm.put("path", s.path());
            sm.put("headers", new LinkedHashMap<>(s.headers()));
            sm.put("body", s.body().name());
            if (s.bodyTemplate() != null) sm.put("bodyTemplate", s.bodyTemplate());
            sm.put("repeat", s.repeat().name());
            sm.put("read", new LinkedHashMap<>(s.read()));
            sm.put("required", new ArrayList<>(new java.util.TreeSet<>(s.required())));
            steps.add(sm);
        }
        m.put("steps", steps);
        if (c.errorCodePointer() != null) m.put("errorCodePointer", c.errorCodePointer());
        m.put("rules", rules(c.rules()));
        if (c.health() != null) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("method", c.health().method().name());
            h.put("path", c.health().path());
            h.put("signIn", c.health().signIn());
            h.put("rules", rules(c.health().rules()));
            m.put("health", h);
        }
        m.put("testMode", c.testMode().name());
        m.put("sendCorrelationId", c.sendCorrelationId());
        if (c.profile() != null) m.put("profile", c.profile());
        return m;
    }

    private static Map<String, Object> signIn(SignIn s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", s.type());
        switch (s) {
            case SignIn.None n -> {
            }
            case SignIn.Basic b -> {
                m.put("username", b.username());
                m.put("password", b.password().value());
            }
            case SignIn.ApiKey k -> {
                m.put("header", k.header());
                m.put("key", k.key().value());
            }
            case SignIn.BearerFixed t -> m.put("token", t.token().value());
            case SignIn.OAuth2ClientCredentials o -> {
                m.put("tokenAddress", o.tokenAddress().toString());
                m.put("clientId", o.clientId());
                m.put("credentialType", o.credentialType().name());
                m.put("credential", o.credential().value());
                if (o.scope() != null) m.put("scope", o.scope());
                if (o.audience() != null) m.put("audience", o.audience());
                m.put("clientAuth", o.clientAuth().name());
            }
        }
        return m;
    }

    private static List<Object> rules(List<Rule> rules) {
        List<Object> out = new ArrayList<>();
        for (Rule r : rules) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", r.status());
            if (r.code() != null) m.put("code", r.code());
            if (r.pointer() != null) {
                m.put("pointer", r.pointer());
                m.put("equals", r.equals());
            }
            m.put("outcome", r.outcome().name());
            if (r.platformCode() != null) m.put("platformCode", r.platformCode().name());
            if (r.attemptUsed() != null) m.put("attemptUsed", r.attemptUsed());
            out.add(m);
        }
        return out;
    }

    // ---- Reading ----

    public static IntegrationConfig read(String text) {
        Map<String, Object> m;
        try {
            m = Json.object(text);
        } catch (Json.Invalid e) {
            throw new Unreadable("not JSON");
        }
        try {
            if (!FORMAT.equals(m.get("format"))) throw new Unreadable("format");
            Map<String, Object> e = optMap(m, "endpoint");
            Endpoint endpoint = e == null ? null
                    : new Endpoint(URI.create(str(e, "baseAddress")), bool(e, "useProxy"));
            Map<String, Object> l = map(m, "limits");
            Limits limits = new Limits(Duration.ofMillis(num(l, "connectTimeoutMs")),
                    Duration.ofMillis(num(l, "readTimeoutMs")), num(l, "maxFileBytes"),
                    (int) num(l, "maxDocuments"), (int) num(l, "maxResponseBytes"));
            List<CallStep> steps = new ArrayList<>();
            for (Object o : list(m, "steps")) {
                @SuppressWarnings("unchecked") Map<String, Object> s = (Map<String, Object>) o;
                Set<String> required = new HashSet<>();
                for (Object r : list(s, "required")) required.add((String) r);
                steps.add(new CallStep(str(s, "name"), HttpMethod.valueOf(str(s, "method")), str(s, "path"),
                        strings(map(s, "headers")), CallStep.Body.valueOf(str(s, "body")),
                        (String) s.get("bodyTemplate"), CallStep.Repeat.valueOf(str(s, "repeat")),
                        strings(map(s, "read")), required));
            }
            Map<String, Object> h = optMap(m, "health");
            HealthCheck health = h == null ? null : new HealthCheck(HttpMethod.valueOf(str(h, "method")),
                    str(h, "path"), bool(h, "signIn"), readRuleList(list(h, "rules")));
            return new IntegrationConfig(UUID.fromString(str(m, "id")), UUID.fromString(str(m, "entity")),
                    str(m, "product"), Kind.valueOf(str(m, "kind")), str(m, "name"), bool(m, "enabled"), endpoint,
                    signIn(map(m, "signIn")), limits, steps, (String) m.get("errorCodePointer"),
                    readRuleList(list(m, "rules")), health, IntegrationConfig.TestMode.valueOf(str(m, "testMode")),
                    bool(m, "sendCorrelationId"), (String) m.get("profile"));
        } catch (Unreadable u) {
            throw u;
        } catch (RuntimeException bad) {
            throw new Unreadable("a field is missing or not valid");
        }
    }

    private static SignIn signIn(Map<String, Object> m) {
        return switch (str(m, "type")) {
            case "NONE" -> new SignIn.None();
            case "BASIC" -> new SignIn.Basic(str(m, "username"), SecretRef.of(str(m, "password")));
            case "API_KEY" -> new SignIn.ApiKey(str(m, "header"), SecretRef.of(str(m, "key")));
            case "BEARER_FIXED" -> new SignIn.BearerFixed(SecretRef.of(str(m, "token")));
            case "OAUTH2_CLIENT_CREDENTIALS" -> new SignIn.OAuth2ClientCredentials(URI.create(str(m, "tokenAddress")),
                    str(m, "clientId"),
                    SignIn.OAuth2ClientCredentials.CredentialType.valueOf(str(m, "credentialType")),
                    SecretRef.of(str(m, "credential")), (String) m.get("scope"), (String) m.get("audience"),
                    SignIn.OAuth2ClientCredentials.ClientAuth.valueOf(str(m, "clientAuth")));
            default -> throw new Unreadable("signIn.type");
        };
    }

    private static List<Rule> readRuleList(List<Object> raw) {
        List<Rule> out = new ArrayList<>();
        for (Object o : raw) {
            @SuppressWarnings("unchecked") Map<String, Object> r = (Map<String, Object>) o;
            Object pc = r.get("platformCode");
            out.add(new Rule(str(r, "status"), (String) r.get("code"), (String) r.get("pointer"),
                    (String) r.get("equals"), Outcome.valueOf(str(r, "outcome")),
                    pc == null ? null : IntegrationCode.valueOf((String) pc), (Boolean) r.get("attemptUsed")));
        }
        return out;
    }

    /** Rules as data, from a JSON array (the same shape as in a stored configuration), for profiles. */
    public static List<Rule> readRules(String jsonArray) {
        try {
            Object v = Json.parse(jsonArray);
            if (!(v instanceof List<?> l)) throw new Unreadable("rules");
            @SuppressWarnings("unchecked") List<Object> raw = (List<Object>) l;
            return readRuleList(raw);
        } catch (Json.Invalid e) {
            throw new Unreadable("rules not JSON");
        } catch (Unreadable u) {
            throw u;
        } catch (RuntimeException bad) {
            throw new Unreadable("a rule is not valid");
        }
    }

    // ---- Changed fields ----

    /**
     * The names of the fields that differ between two versions ({@code signIn.credential},
     * {@code steps[0].headers.X-Name}), sorted. Names only: the audit never gets a value.
     */
    public static List<String> changedFields(IntegrationConfig before, IntegrationConfig after) {
        Map<String, Object> a = new TreeMap<>();
        Map<String, Object> b = new TreeMap<>();
        flatten("", toMap(before), a);
        flatten("", toMap(after), b);
        Set<String> names = new java.util.TreeSet<>(a.keySet());
        names.addAll(b.keySet());
        List<String> out = new ArrayList<>();
        for (String n : names) {
            if (!java.util.Objects.equals(a.get(n), b.get(n))) out.add(n);
        }
        return out;
    }

    /** All field names of one version (for the first version's history entry). */
    public static List<String> fields(IntegrationConfig c) {
        Map<String, Object> a = new TreeMap<>();
        flatten("", toMap(c), a);
        a.remove("format");
        return new ArrayList<>(a.keySet());
    }

    private static void flatten(String prefix, Object v, Map<String, Object> out) {
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) out.put(prefix, "{}");
            for (Map.Entry<?, ?> e : m.entrySet()) {
                flatten(prefix.isEmpty() ? String.valueOf(e.getKey()) : prefix + "." + e.getKey(), e.getValue(), out);
            }
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) out.put(prefix, "[]");
            for (int k = 0; k < l.size(); k++) flatten(prefix + "[" + k + "]", l.get(k), out);
        } else {
            out.put(prefix, v);
        }
    }

    // ---- Helpers ----

    private static String str(Map<String, Object> m, String k) {
        if (!(m.get(k) instanceof String s)) throw new Unreadable(k);
        return s;
    }

    private static boolean bool(Map<String, Object> m, String k) {
        if (!(m.get(k) instanceof Boolean b)) throw new Unreadable(k);
        return b;
    }

    private static long num(Map<String, Object> m, String k) {
        if (!(m.get(k) instanceof Long n)) throw new Unreadable(k);
        return n;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> m, String k) {
        if (!(m.get(k) instanceof Map<?, ?> v)) throw new Unreadable(k);
        return (Map<String, Object>) v;
    }

    private static Map<String, Object> optMap(Map<String, Object> m, String k) {
        return m.get(k) == null ? null : map(m, k);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Map<String, Object> m, String k) {
        if (!(m.get(k) instanceof List<?> v)) throw new Unreadable(k);
        return (List<Object>) v;
    }

    private static Map<String, String> strings(Map<String, Object> m) {
        Map<String, String> out = new LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (!(v instanceof String s)) throw new Unreadable(k);
            out.put(k, s);
        });
        return out;
    }
}
