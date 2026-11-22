package com.documentplatform.integration.engine;

import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.integration.config.CallStep;
import com.documentplatform.integration.config.Classification;
import com.documentplatform.integration.config.ConfigCheck;
import com.documentplatform.integration.config.HealthCheck;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.Kind;
import com.documentplatform.integration.config.Template;
import com.documentplatform.integration.http.Outbound;
import com.documentplatform.integration.http.RetryAfter;
import com.documentplatform.integration.json.Json;
import com.documentplatform.integration.result.AttemptResult;
import com.documentplatform.integration.result.HealthResult;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.integration.result.StepResult;
import com.documentplatform.integration.signin.ClientCredentialsTokens;
import com.documentplatform.integration.signin.SignInHeaders;
import com.documentplatform.integration.store.StoredIntegration;
import com.documentplatform.notifications.secret.SecretResolver;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Runs an API push integration's configured steps once and classifies the result. It never sleeps and never retries:
 * the app owns the schedule (and passes {@link Delivery.Resume} to carry on from the step reached).
 *
 * <p>For each call: the address is the base address plus the rendered path; headers are the sign-in bundle's, the
 * step's rendered templates, {@code X-Correlation-Id} when configured, and the content type of the body; the answer
 * is read up to the integration's limit, the destination's code taken from {@code errorCodePointer}, and the
 * classification rules (data) decide the result class. Logs carry the integration ID, version, step, status and
 * codes only.
 */
public final class CallEngine implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(CallEngine.class.getName());
    private static final Pattern SAFE_CODE = Pattern.compile("[A-Za-z0-9_.:-]{1,40}");
    private static final int MAX_VALUE = 256;

    private final Outbound outbound;
    private final SignInHeaders signIn;
    private final Clock clock;

    public CallEngine(Outbound outbound, SecretResolver secrets, Clock clock) {
        this(outbound, secrets, new ClientCredentialsTokens(outbound, clock), clock);
    }

    public CallEngine(Outbound outbound, SecretResolver secrets, ClientCredentialsTokens tokens, Clock clock) {
        this.outbound = Objects.requireNonNull(outbound, "outbound");
        this.signIn = new SignInHeaders(outbound.installation(), secrets, tokens);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** One attempt of the whole step sequence (or the rest of it, after {@code delivery.resume()}). */
    public AttemptResult attempt(StoredIntegration integration, Delivery delivery) {
        return run(integration, delivery, false);
    }

    AttemptResult run(StoredIntegration integration, Delivery delivery, boolean test) {
        Run r = new Run(integration.config(), integration.version(), delivery, test);
        AttemptResult result = r.go();
        LOG.log(System.Logger.Level.INFO, "Integration {0} v{1}{2}: {3} {4}{5}, status {6}{7}",
                result.integrationId(), result.configVersion(), test ? " (test)" : "", result.outcome(),
                result.code(), result.destinationCode() == null ? "" : " (" + result.destinationCode() + ")",
                result.httpStatus(), result.stepReached() == null ? "" : ", step " + result.stepReached());
        return result;
    }

    /** The configured health check, classified by its own rules. */
    public HealthResult health(StoredIntegration integration) {
        IntegrationConfig c = integration.config();
        HealthCheck h = c.health();
        if (h == null || c.endpoint() == null) {
            return health(c, integration.version(), Outcome.NOT_CONFIGURED, IntegrationCode.INT_CONFIG_INVALID, null,
                    0, null);
        }
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        if (h.signIn()) {
            SignInHeaders.Prepared p = signIn.prepare(c.signIn(), c.limits(), c.endpoint().useProxy());
            if (!p.ok()) return health(c, integration.version(), p.failure(), p.code(), null, 0, null);
            headers.addAll(p.headers());
        }
        String correlationId = "health-" + UUID.randomUUID();
        if (c.sendCorrelationId()) headers.add(entry("X-Correlation-Id", correlationId));
        headers.add(entry("Accept", "application/json"));
        String path;
        try {
            path = Template.parse(h.path(), Template.Place.PATH)
                    .render(name -> name.equals("correlationId") ? Optional.of(correlationId) : Optional.empty());
        } catch (Template.Problem p) {
            return health(c, integration.version(), Outcome.NOT_CONFIGURED, IntegrationCode.INT_CONFIG_INVALID, null,
                    0, null);
        }
        Outbound.Answer a;
        try {
            a = outbound.send(new Outbound.Call(h.method().name(), address(c, path), headers, null,
                    c.limits().readTimeout()), c.limits().connectTimeout(), c.limits().maxResponseBytes(),
                    c.endpoint().useProxy());
        } catch (Outbound.Failure f) {
            return health(c, integration.version(), failureOutcome(f.code()), f.code(), null, 0, null);
        }
        Object json = a.tooLarge() ? null : json(a.body());
        String destCode = destinationCode(c, json);
        Classification.Decision d = Classification.of(h.rules()).decide(a.status(), destCode, json);
        if (d.outcome() == Outcome.SIGN_IN_FAILED && h.signIn()) signIn.refused(c.signIn());
        IntegrationCode code = d.outcome() == Outcome.SENT ? IntegrationCode.INT_HEALTHY : d.code();
        HealthResult result = health(c, integration.version(), d.outcome(), code, destCode, a.status(),
                RetryAfter.parse(a.header("Retry-After").orElse(null), clock.instant()));
        LOG.log(System.Logger.Level.INFO, "Integration {0} v{1} health: {2} {3}, status {4}", c.id(),
                integration.version(), result.outcome(), result.code(), result.httpStatus());
        return result;
    }

    /** Only the sign-in: secrets found, a token fetched. For the Test action's check-only mode. */
    SignInHeaders.Prepared checkSignIn(IntegrationConfig c) {
        return signIn.prepare(c.signIn(), c.limits(), c.endpoint() == null || c.endpoint().useProxy());
    }

    private HealthResult health(IntegrationConfig c, int version, Outcome o, IntegrationCode code, String destCode,
                                int status, Duration retryAfter) {
        return new HealthResult(c.id(), version, o, code, destCode, status, retryAfter, clock.instant());
    }

    // ---- One attempt ----

    private final class Run {
        final IntegrationConfig c;
        final int version;
        final Delivery d;
        final boolean test;
        final String correlationId;
        final Map<String, String> values = new LinkedHashMap<>();
        final Map<String, Map<String, String>> documentValues = new LinkedHashMap<>();
        final Set<String> stepsDone = new HashSet<>();
        final Set<String> documentsDone = new HashSet<>();
        final List<StepResult> calls = new ArrayList<>();
        List<Map.Entry<String, String>> signInHeaders;
        int lastStatus;
        boolean anyNew;

        Run(IntegrationConfig c, int version, Delivery d, boolean test) {
            this.c = c;
            this.version = version;
            this.d = d;
            this.test = test;
            this.correlationId = d.correlationId() != null ? d.correlationId() : UUID.randomUUID().toString();
            values.putAll(d.resume().values());
            stepsDone.addAll(d.resume().stepsDone());
            documentsDone.addAll(d.resume().documentsDone());
        }

        AttemptResult go() {
            if (!c.enabled()) return stop(Outcome.NOT_CONFIGURED, IntegrationCode.INT_DISABLED, null, 0, null, null, null);
            if (c.kind() != Kind.API_PUSH) {
                return stop(Outcome.NOT_CONFIGURED, IntegrationCode.INT_KIND_NOT_SUPPORTED, null, 0, null, null, null);
            }
            if (!ConfigCheck.check(c, outbound.installation()).ok()) {
                // Saved under other installation rules (for example it is now hosted): not run.
                return stop(Outcome.NOT_CONFIGURED, IntegrationCode.INT_CONFIG_INVALID, null, 0, null, null, null);
            }
            if (d.documents().size() > c.limits().maxDocuments()) {
                return stop(Outcome.REFUSED, IntegrationCode.INT_TOO_MANY_DOCUMENTS, null, 0, null, null, null);
            }
            for (Delivery.Document doc : d.documents()) {
                if (doc.size() > c.limits().maxFileBytes()) {
                    return stop(Outcome.REFUSED, IntegrationCode.INT_FILE_TOO_LARGE, null, 0, null, null,
                            doc.sourceDocumentId());
                }
            }
            SignInHeaders.Prepared p = signIn.prepare(c.signIn(), c.limits(), c.endpoint().useProxy());
            if (!p.ok()) return stop(p.failure(), p.code(), null, 0, null, null, null);
            signInHeaders = p.headers();

            List<CallStep> steps = c.steps();
            int i = 0;
            while (i < steps.size()) {
                CallStep s = steps.get(i);
                if (s.repeat() == CallStep.Repeat.ONCE) {
                    if (!stepsDone.contains(s.name())) {
                        AttemptResult stopped = call(s, null);
                        if (stopped != null) return stopped;
                        stepsDone.add(s.name());
                    }
                    i++;
                    continue;
                }
                // A run of per-document steps goes document by document: all of them for one, then the next.
                int end = i;
                while (end < steps.size() && steps.get(end).repeat() == CallStep.Repeat.EACH_DOCUMENT) end++;
                List<CallStep> block = steps.subList(i, end);
                for (Delivery.Document doc : d.documents()) {
                    if (documentsDone.contains(doc.sourceDocumentId())) continue;
                    for (CallStep ds : block) {
                        AttemptResult stopped = call(ds, doc);
                        if (stopped != null) return stopped;
                    }
                    documentsDone.add(doc.sourceDocumentId());
                }
                i = end;
            }
            boolean allReplays = !calls.isEmpty() && !anyNew;
            CallStep last = steps.get(steps.size() - 1);
            return finish(allReplays ? Outcome.ALREADY_RECEIVED : Outcome.SENT,
                    allReplays ? IntegrationCode.INT_ALREADY_RECEIVED : IntegrationCode.INT_SENT, null, lastStatus,
                    null, true, last.name(), null);
        }

        /** Makes one call; returns the attempt's result if it ends here, else null. */
        AttemptResult call(CallStep s, Delivery.Document doc) {
            String docId = doc == null ? null : doc.sourceDocumentId();
            Map<String, String> docVals = doc == null ? null
                    : documentValues.computeIfAbsent(docId, k -> new LinkedHashMap<>());
            java.util.function.Function<String, Optional<String>> lookup = name -> lookup(name, doc, docVals);

            String path;
            List<Map.Entry<String, String>> headers = new ArrayList<>(signInHeaders);
            Map<String, String> lower = new HashMap<>();
            HttpRequest.BodyPublisher body = null;
            InputStream content = null;
            try {
                path = Template.parse(s.path(), Template.Place.PATH).render(lookup);
                for (Map.Entry<String, String> h : s.headers().entrySet()) {
                    String v = Template.parse(h.getValue(), Template.Place.HEADER).render(lookup);
                    headers.add(entry(h.getKey(), v));
                    lower.put(h.getKey().toLowerCase(Locale.ROOT), v);
                }
                if (s.body() == CallStep.Body.JSON) {
                    String json = Template.parse(s.bodyTemplate(), Template.Place.JSON).render(lookup);
                    body = HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8);
                    if (!lower.containsKey("content-type")) headers.add(entry("Content-Type", "application/json"));
                }
            } catch (Template.Problem p) {
                return stop(Outcome.NOT_CONFIGURED, p.code(), null, 0, null, s.name(), docId);
            }
            if (s.body() == CallStep.Body.FILE) {
                try {
                    content = doc.content().open();
                } catch (IOException | RuntimeException e) {
                    return stop(Outcome.NOT_CONFIGURED, IntegrationCode.INT_CONTENT_UNREADABLE, null, 0, null,
                            s.name(), docId);
                }
                InputStream in = content;
                body = HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(() -> in),
                        doc.size());
                if (!lower.containsKey("content-type")) headers.add(entry("Content-Type", doc.contentType()));
            }
            if (c.sendCorrelationId()) headers.add(entry("X-Correlation-Id", correlationId));
            if (!lower.containsKey("accept")) headers.add(entry("Accept", "application/json"));

            Outbound.Answer a;
            try {
                a = outbound.send(new Outbound.Call(s.method().name(), address(c, path), headers, body,
                                c.limits().readTimeout()), c.limits().connectTimeout(), c.limits().maxResponseBytes(),
                        c.endpoint().useProxy());
            } catch (Outbound.Failure f) {
                calls.add(new StepResult(s.name(), docId, failureOutcome(f.code()), f.code(), 0, null));
                return stop(failureOutcome(f.code()), f.code(), null, 0, null, s.name(), docId);
            } finally {
                if (content != null) {
                    try {
                        content.close();
                    } catch (IOException ignored) {
                        // nothing to do: the stream was only read
                    }
                }
            }
            lastStatus = a.status();
            Duration retryAfter = RetryAfter.parse(a.header("Retry-After").orElse(null), clock.instant());
            Object json = a.tooLarge() ? null : json(a.body());
            String destCode = destinationCode(c, json);
            if (a.tooLarge() && a.status() / 100 == 2) {
                calls.add(new StepResult(s.name(), docId, Outcome.UNCLEAR, IntegrationCode.INT_RESPONSE_TOO_LARGE,
                        a.status(), null));
                return stop(Outcome.UNCLEAR, IntegrationCode.INT_RESPONSE_TOO_LARGE, null, a.status(), null, s.name(),
                        docId);
            }
            Classification.Decision dec = Classification.of(c.rules()).decide(a.status(), destCode, json);
            calls.add(new StepResult(s.name(), docId, dec.outcome(), dec.code(), a.status(), destCode));
            if (dec.outcome() == Outcome.SIGN_IN_FAILED) signIn.refused(c.signIn());
            if (dec.outcome() != Outcome.SENT && dec.outcome() != Outcome.ALREADY_RECEIVED) {
                return finish(dec.outcome(), dec.code(), destCode, a.status(), retryAfter, dec.attemptUsed(), s.name(),
                        docId);
            }
            if (dec.outcome() == Outcome.SENT) anyNew = true;
            // Read the values this step names.
            for (Map.Entry<String, String> r : s.read().entrySet()) {
                Optional<String> v = json == null ? Optional.empty() : scalar(Json.at(json, r.getValue()));
                if (v.isPresent()) {
                    (docVals != null ? docVals : values).put(s.name() + "." + r.getKey(), v.get());
                } else if (s.required().contains(r.getKey()) && dec.outcome() == Outcome.SENT) {
                    return finish(Outcome.UNCLEAR, IntegrationCode.INT_UNCLEAR_ANSWER, destCode, a.status(), null,
                            true, s.name(), docId);
                }
            }
            return null;
        }

        Optional<String> lookup(String name, Delivery.Document doc, Map<String, String> docVals) {
            switch (name) {
                case "correlationId":
                    return Optional.of(correlationId);
                case "package.id":
                    return Optional.of(d.packageId());
                case "package.documentCount":
                    return Optional.of(Integer.toString(d.documents().size()));
                default:
                    break;
            }
            if (name.startsWith("doc.")) {
                if (doc == null) return Optional.empty();
                return Optional.ofNullable(switch (name) {
                    case "doc.sourceDocumentId" -> doc.sourceDocumentId();
                    case "doc.fileName" -> doc.fileName();
                    case "doc.contentType" -> doc.contentType();
                    case "doc.sha256" -> doc.sha256();
                    case "doc.size" -> Long.toString(doc.size());
                    default -> null;
                });
            }
            if (name.startsWith("value.")) return Optional.ofNullable(d.values().get(name.substring(6)));
            if (name.startsWith("step.")) {
                String key = name.substring(5);
                if (docVals != null && docVals.containsKey(key)) return Optional.of(docVals.get(key));
                return Optional.ofNullable(values.get(key));
            }
            return Optional.empty();
        }

        AttemptResult stop(Outcome o, IntegrationCode code, String destCode, int status, Duration retryAfter,
                           String step, String docId) {
            return finish(o, code, destCode, status, retryAfter, o.usesAttemptByDefault(), step, docId);
        }

        AttemptResult finish(Outcome o, IntegrationCode code, String destCode, int status, Duration retryAfter,
                             boolean attemptUsed, String step, String docId) {
            Map<String, Map<String, String>> dv = new LinkedHashMap<>();
            documentValues.forEach((k, v) -> {
                if (!v.isEmpty()) dv.put(k, Map.copyOf(v));
            });
            return new AttemptResult(c.id(), version, o, code, destCode, status, retryAfter, attemptUsed,
                    c.sendCorrelationId() ? correlationId : null, step, docId, values, dv, stepsDone, documentsDone,
                    calls, test);
        }
    }

    // ---- Helpers ----

    private static URI address(IntegrationConfig c, String path) {
        String base = c.endpoint().baseAddress().toString();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        URI uri = URI.create(base + path);
        if (!c.endpoint().baseAddress().getHost().equalsIgnoreCase(uri.getHost())) {
            throw new IllegalStateException("A path changed the host"); // cannot happen: paths start with one '/'
        }
        return uri;
    }

    static Outcome failureOutcome(IntegrationCode code) {
        return switch (code) {
            case INT_ADDRESS_NOT_SECURE, INT_VALUE_NOT_ALLOWED -> Outcome.NOT_CONFIGURED;
            default -> Outcome.CANT_CONNECT;
        };
    }

    private static Object json(byte[] body) {
        if (body.length == 0) return null;
        try {
            return Json.parse(new String(body, StandardCharsets.UTF_8));
        } catch (Json.Invalid e) {
            return null;
        }
    }

    private static String destinationCode(IntegrationConfig c, Object json) {
        if (c.errorCodePointer() == null || json == null) return null;
        Object v = Json.at(json, c.errorCodePointer()).orElse(null);
        if (!(v instanceof String) && !(v instanceof Long)) return null;
        String s = String.valueOf(v);
        return SAFE_CODE.matcher(s).matches() ? s : null;
    }

    private static Optional<String> scalar(Optional<Object> v) {
        if (v.isEmpty()) return Optional.empty();
        Object o = v.get();
        if (!(o instanceof String) && !(o instanceof Long) && !(o instanceof Double) && !(o instanceof Boolean)) {
            return Optional.empty();
        }
        String s = String.valueOf(o);
        if (s.isEmpty() || s.length() > MAX_VALUE || s.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7f)) {
            return Optional.empty();
        }
        return Optional.of(s);
    }

    private static Map.Entry<String, String> entry(String k, String v) {
        return new AbstractMap.SimpleImmutableEntry<>(k, v);
    }

    @Override
    public void close() {
        outbound.close();
    }
}
