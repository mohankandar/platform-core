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
        l.put("maxPackageBytes", c.limits().maxPackageBytes());
        m.put("limits", l);
        List<Object> steps = new ArrayList<>();
        for (CallStep s : c.steps()) steps.add(stepMap(s));
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
        if (!c.allowedSensitiveFields().isEmpty()) m.put("allowedSensitiveFields", new ArrayList<>(c.allowedSensitiveFields()));
        if (!c.settings().isEmpty()) m.put("settings", new LinkedHashMap<>(c.settings()));
        if (c.folder() != null) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("folder", c.folder().folder());
            f.put("folderName", c.folder().folderNameTemplate());
            if (c.folder().fileNameTemplate() != null) f.put("fileName", c.folder().fileNameTemplate());
            m.put("folder", f);
        }
        if (c.callback() != null) {
            Map<String, Object> cb = new LinkedHashMap<>();
            cb.put("signingSecret", c.callback().signingSecret().value());
            m.put("callback", cb);
        }
        if (c.poll() != null) m.put("poll", poll(c.poll()));
        if (c.watch() != null) m.put("watch", watch(c.watch()));
        return m;
    }

    private static Map<String, Object> stepMap(CallStep s) {
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
        if (!s.rules().isEmpty()) sm.put("rules", rules(s.rules()));
        if (s.multipart() != null) {
            Map<String, Object> mp = new LinkedHashMap<>();
            mp.put("jsonPart", s.multipart().jsonPart());
            mp.put("filePart", s.multipart().filePart());
            mp.put("fileName", s.multipart().fileNameTemplate());
            sm.put("multipart", mp);
        }
        if (s.integrity() != null) {
            Map<String, Object> in = new LinkedHashMap<>();
            if (s.integrity().sha256Value() != null) in.put("sha256Value", s.integrity().sha256Value());
            if (s.integrity().sizeValue() != null) in.put("sizeValue", s.integrity().sizeValue());
            sm.put("integrity", in);
        }
        if (s.accepted() != null) sm.put("accepted", accepted(s.accepted()));
        return sm;
    }

    private static Map<String, Object> poll(PollSource p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("list", stepMap(p.list()));
        m.put("itemsPointer", p.itemsPointer());
        m.put("idPointer", p.idPointer());
        if (!p.values().isEmpty()) m.put("values", new LinkedHashMap<>(p.values()));
        if (p.fileNameValue() != null) m.put("fileNameValue", p.fileNameValue());
        PollSource.Paging g = p.paging();
        Map<String, Object> pg = new LinkedHashMap<>();
        pg.put("mode", g.mode().name());
        if (g.pointer() != null) pg.put("pointer", g.pointer());
        if (g.param() != null) pg.put("param", g.param());
        if (g.sizeParam() != null) pg.put("sizeParam", g.sizeParam());
        pg.put("pageSize", (long) g.pageSize());
        pg.put("firstPage", (long) g.firstPage());
        pg.put("maxPagesPerPass", (long) g.maxPagesPerPass());
        pg.put("resume", g.resume().name());
        m.put("paging", pg);
        m.put("download", stepMap(p.download()));
        if (p.acknowledge() != null) m.put("acknowledge", stepMap(p.acknowledge()));
        return m;
    }

    private static Map<String, Object> watch(WatchSource w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("root", w.root());
        m.put("layout", w.layout().name());
        m.put("trigger", w.triggerPattern());
        m.put("triggerType", w.triggerType().name());
        m.put("stableForSeconds", w.stableFor().toSeconds());
        m.put("incompleteAfterSeconds", w.incompleteAfter().toSeconds());
        m.put("claimTimeoutSeconds", w.claimTimeout().toSeconds());
        m.put("maxDropsPerPass", (long) w.maxDropsPerPass());
        m.put("maxTriggerBytes", w.maxTriggerBytes());
        if (!w.mapping().isEmpty()) m.put("mapping", new LinkedHashMap<>(w.mapping()));
        if (w.fileListValue() != null) m.put("fileListValue", w.fileListValue());
        return m;
    }

    private static PollSource poll(Map<String, Object> m) {
        if (m == null) return null;
        Map<String, Object> pg = map(m, "paging");
        PollSource.Paging paging = new PollSource.Paging(PollSource.Paging.Mode.valueOf(str(pg, "mode")),
                (String) pg.get("pointer"), (String) pg.get("param"), (String) pg.get("sizeParam"),
                (int) num(pg, "pageSize"), (int) num(pg, "firstPage"), (int) num(pg, "maxPagesPerPass"),
                PollSource.Paging.Resume.valueOf(str(pg, "resume")));
        return new PollSource(readStep(map(m, "list")), str(m, "itemsPointer"), str(m, "idPointer"),
                m.containsKey("values") ? strings(map(m, "values")) : Map.of(), (String) m.get("fileNameValue"),
                paging, readStep(map(m, "download")),
                m.containsKey("acknowledge") ? readStep(map(m, "acknowledge")) : null);
    }

    private static WatchSource watch(Map<String, Object> m) {
        if (m == null) return null;
        return new WatchSource(str(m, "root"), WatchSource.Layout.valueOf(str(m, "layout")), str(m, "trigger"),
                WatchSource.TriggerType.valueOf(str(m, "triggerType")),
                Duration.ofSeconds(num(m, "stableForSeconds")), Duration.ofSeconds(num(m, "incompleteAfterSeconds")),
                Duration.ofSeconds(num(m, "claimTimeoutSeconds")), (int) num(m, "maxDropsPerPass"),
                num(m, "maxTriggerBytes"), m.containsKey("mapping") ? strings(map(m, "mapping")) : Map.of(),
                (String) m.get("fileListValue"));
    }

    private static Map<String, Object> accepted(CallStep.Accepted a) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (a.referencePointer() != null) m.put("referencePointer", a.referencePointer());
        m.put("statusMethod", a.statusMethod().name());
        if (a.statusPath() != null) m.put("statusPath", a.statusPath());
        m.put("statePointer", a.statePointer());
        m.put("done", new ArrayList<>(new java.util.TreeSet<>(a.doneValues())));
        m.put("failed", new ArrayList<>(new java.util.TreeSet<>(a.failedValues())));
        m.put("working", new ArrayList<>(new java.util.TreeSet<>(a.workingValues())));
        m.put("defaultWaitSeconds", a.defaultWait().toSeconds());
        if (!a.read().isEmpty()) m.put("read", new LinkedHashMap<>(a.read()));
        return m;
    }

    private static CallStep.Accepted accepted(Map<String, Object> s) {
        Map<String, Object> a = optMap(s, "accepted");
        if (a == null) return null;
        return new CallStep.Accepted((String) a.get("referencePointer"),
                a.containsKey("statusMethod") ? HttpMethod.valueOf(str(a, "statusMethod")) : HttpMethod.GET,
                (String) a.get("statusPath"), str(a, "statePointer"), new HashSet<>(stringList(list(a, "done"))),
                new HashSet<>(stringList(list(a, "failed"))),
                a.containsKey("working") ? new HashSet<>(stringList(list(a, "working"))) : Set.of(),
                a.containsKey("defaultWaitSeconds") ? Duration.ofSeconds(num(a, "defaultWaitSeconds"))
                        : CallStep.Accepted.DEFAULT_WAIT,
                a.containsKey("read") ? strings(map(a, "read")) : Map.of());
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
            case SignIn.MutualTls t -> {
                if (t.with() != null) m.put("with", signIn(t.with()));
                m.put("certificateForToken", t.certificateForToken());
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
            if (r.retryAfter() != null) m.put("retryAfter", r.retryAfter());
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
                    (int) num(l, "maxDocuments"), (int) num(l, "maxResponseBytes"),
                    l.containsKey("maxPackageBytes") ? num(l, "maxPackageBytes") : Limits.DEFAULT_PACKAGE_BYTES);
            List<CallStep> steps = readStepList(list(m, "steps"));
            Map<String, Object> h = optMap(m, "health");
            HealthCheck health = h == null ? null : new HealthCheck(HttpMethod.valueOf(str(h, "method")),
                    str(h, "path"), bool(h, "signIn"), readRuleList(list(h, "rules")));
            return new IntegrationConfig(UUID.fromString(str(m, "id")), UUID.fromString(str(m, "entity")),
                    str(m, "product"), Kind.valueOf(str(m, "kind")), str(m, "name"), bool(m, "enabled"), endpoint,
                    signIn(map(m, "signIn")), limits, steps, (String) m.get("errorCodePointer"),
                    readRuleList(list(m, "rules")), health, IntegrationConfig.TestMode.valueOf(str(m, "testMode")),
                    bool(m, "sendCorrelationId"), (String) m.get("profile"),
                    m.containsKey("allowedSensitiveFields") ? new java.util.TreeSet<>(stringList(list(m,
                            "allowedSensitiveFields"))) : Set.of(),
                    m.containsKey("settings") ? strings(map(m, "settings")) : Map.of(), folder(optMap(m, "folder")),
                    m.containsKey("callback")
                            ? new CallbackTarget(SecretRef.of(str(map(m, "callback"), "signingSecret"))) : null,
                    poll(optMap(m, "poll")), watch(optMap(m, "watch")));
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
            case "MUTUAL_TLS" -> new SignIn.MutualTls(m.containsKey("with") ? signIn(map(m, "with")) : null,
                    Boolean.TRUE.equals(m.get("certificateForToken")));
            default -> throw new Unreadable("signIn.type");
        };
    }

    private static FolderTarget folder(Map<String, Object> f) {
        if (f == null) return null;
        return new FolderTarget(str(f, "folder"), str(f, "folderName"), (String) f.get("fileName"));
    }

    private static List<Rule> readRuleList(List<Object> raw) {
        List<Rule> out = new ArrayList<>();
        for (Object o : raw) {
            @SuppressWarnings("unchecked") Map<String, Object> r = (Map<String, Object>) o;
            Object pc = r.get("platformCode");
            out.add(new Rule(str(r, "status"), (String) r.get("code"), (String) r.get("pointer"),
                    (String) r.get("equals"), Outcome.valueOf(str(r, "outcome")),
                    pc == null ? null : IntegrationCode.valueOf((String) pc), (Boolean) r.get("attemptUsed"),
                    (Boolean) r.get("retryAfter")));
        }
        return out;
    }

    private static CallStep.Multipart multipart(Map<String, Object> s) {
        Map<String, Object> mp = optMap(s, "multipart");
        return mp == null ? null : new CallStep.Multipart(str(mp, "jsonPart"), str(mp, "filePart"), str(mp, "fileName"));
    }

    private static CallStep.Integrity integrity(Map<String, Object> s) {
        Map<String, Object> in = optMap(s, "integrity");
        return in == null ? null : new CallStep.Integrity((String) in.get("sha256Value"), (String) in.get("sizeValue"));
    }

    private static List<String> stringList(List<Object> raw) {
        List<String> out = new ArrayList<>();
        for (Object o : raw) {
            if (!(o instanceof String s)) throw new Unreadable("a list holds something that is not text");
            out.add(s);
        }
        return out;
    }

    /** Steps as data, from a JSON array (the same shape as in a stored configuration), for profiles. */
    public static List<CallStep> readSteps(String jsonArray) {
        try {
            Map<String, Object> holder = Json.object("{\"format\":\"" + FORMAT + "\",\"steps\":" + jsonArray + "}");
            return readStepList(list(holder, "steps"));
        } catch (Json.Invalid e) {
            throw new Unreadable("steps not JSON");
        } catch (Unreadable u) {
            throw u;
        } catch (RuntimeException bad) {
            throw new Unreadable("a step is not valid");
        }
    }

    private static List<CallStep> readStepList(List<Object> raw) {
        List<CallStep> steps = new ArrayList<>();
        for (Object o : raw) {
            @SuppressWarnings("unchecked") Map<String, Object> s = (Map<String, Object>) o;
            steps.add(readStep(s));
        }
        return steps;
    }

    private static CallStep readStep(Map<String, Object> s) {
        Set<String> required = new HashSet<>(stringList(list(s, "required")));
        return new CallStep(str(s, "name"), HttpMethod.valueOf(str(s, "method")), str(s, "path"),
                strings(map(s, "headers")), CallStep.Body.valueOf(str(s, "body")),
                (String) s.get("bodyTemplate"), CallStep.Repeat.valueOf(str(s, "repeat")),
                strings(map(s, "read")), required,
                s.containsKey("rules") ? readRuleList(list(s, "rules")) : List.of(), multipart(s), integrity(s),
                accepted(s));
    }

    /** A poll source as data, from a JSON object (the same shape as in a stored configuration), for profiles. */
    public static PollSource readPoll(String jsonObject) {
        try {
            return poll(Json.object(jsonObject));
        } catch (Json.Invalid e) {
            throw new Unreadable("poll not JSON");
        } catch (Unreadable u) {
            throw u;
        } catch (RuntimeException bad) {
            throw new Unreadable("the poll source is not valid");
        }
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
