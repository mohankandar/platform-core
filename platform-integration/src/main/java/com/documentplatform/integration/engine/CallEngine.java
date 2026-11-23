package com.documentplatform.integration.engine;

import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.integration.callback.CallbackSignature;
import com.documentplatform.integration.config.CallStep;
import com.documentplatform.integration.config.internal.Classification;
import com.documentplatform.integration.config.ConfigCheck;
import com.documentplatform.integration.config.HealthCheck;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.Kind;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.config.Template;
import com.documentplatform.integration.credential.CertificateSource;
import com.documentplatform.outbound.ClientCertificate;
import com.documentplatform.integration.credential.CredentialState;
import com.documentplatform.outbound.Outbound;
import com.documentplatform.outbound.RetryAfter;
import com.documentplatform.integration.json.internal.Json;
import com.documentplatform.integration.result.AttemptResult;
import com.documentplatform.integration.result.HealthResult;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.integration.result.PendingStatus;
import com.documentplatform.integration.result.StepResult;
import com.documentplatform.integration.signin.ClientCredentialsTokens;
import com.documentplatform.integration.store.StoredIntegration;
import com.documentplatform.notifications.secret.Secret;
import com.documentplatform.notifications.secret.SecretResolver;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 *
 * <p><b>Mutual TLS</b> (sign-in {@code MUTUAL_TLS}): every call of the attempt, the health check and (when configured)
 * the token request present the integration's CURRENT client certificate from the {@link CertificateSource}; a Test of
 * the NEXT one presents that instead. Without a usable certificate nothing is sent ({@code INT_CREDENTIAL_*}).
 *
 * <p><b>Accepted, check later</b> ({@link CallStep.Accepted} on the last step): a 202 answer ends the attempt with
 * {@link Outcome#ACCEPTED} and a {@link PendingStatus}; {@link #checkStatus} later makes one status call each time.
 *
 * <p><b>Callbacks</b> (kind {@code CALLBACK}): {@link #sendCallback} makes one signed POST; the outbox
 * ({@code callback.Callbacks}) keeps the schedule.
 */
public final class CallEngine implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(CallEngine.class.getName());
    private static final Pattern SAFE_CODE = Pattern.compile("[A-Za-z0-9_.:-]{1,40}");
    private static final int MAX_VALUE = 256;
    /** The step name of a callback's one call, in results. */
    public static final String CALLBACK_STEP = "callback";

    private final Outbound outbound;
    private final SignInHeaders signIn;
    private final SecretResolver secrets;
    private final CertificateSource certificates;
    private final Clock clock;
    private volatile java.util.function.Consumer<AttemptResult> onAttempt = r -> { };

    public CallEngine(Outbound outbound, SecretResolver secrets, Clock clock) {
        this(outbound, secrets, new ClientCredentialsTokens(outbound, clock), clock);
    }

    public CallEngine(Outbound outbound, SecretResolver secrets, ClientCredentialsTokens tokens, Clock clock) {
        this(outbound, secrets, tokens, CertificateSource.NONE, clock);
    }

    /** @param certificates the integrations' client certificates (mutual TLS) */
    public CallEngine(Outbound outbound, SecretResolver secrets, CertificateSource certificates, Clock clock) {
        this(outbound, secrets, new ClientCredentialsTokens(outbound, clock), certificates, clock);
    }

    public CallEngine(Outbound outbound, SecretResolver secrets, ClientCredentialsTokens tokens,
                      CertificateSource certificates, Clock clock) {
        this.outbound = Objects.requireNonNull(outbound, "outbound");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.signIn = new SignInHeaders(outbound.installation(), secrets, tokens);
        this.certificates = Objects.requireNonNull(certificates, "certificates");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Tells {@code listener} of every attempt's result (not Test actions), for example to count attempts by outcome
     * (platform-monitoring-spring's metrics). Results hold codes and statuses only. A listener that throws is logged
     * by its class name and ignored.
     */
    public CallEngine onAttempt(java.util.function.Consumer<AttemptResult> listener) {
        this.onAttempt = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /** One attempt of the whole step sequence (or the rest of it, after {@code delivery.resume()}). */
    public AttemptResult attempt(StoredIntegration integration, Delivery delivery) {
        return run(integration, delivery, false, CredentialState.CURRENT);
    }

    AttemptResult run(StoredIntegration integration, Delivery delivery, boolean test) {
        return run(integration, delivery, test, CredentialState.CURRENT);
    }

    AttemptResult run(StoredIntegration integration, Delivery delivery, boolean test, CredentialState certificate) {
        Run r = new Run(integration.config(), integration.version(), delivery, test, certificate);
        AttemptResult result = r.go();
        log(result, test ? " (test" + (certificate == CredentialState.NEXT ? ", next certificate)" : ")") : "");
        if (!test) {
            try {
                onAttempt.accept(result);
            } catch (RuntimeException listenerFailed) {
                LOG.log(System.Logger.Level.WARNING, "Attempt listener failed: {0}", listenerFailed.getClass().getName());
            }
        }
        return result;
    }

    private static void log(AttemptResult result, String what) {
        LOG.log(System.Logger.Level.INFO, "Integration {0} v{1}{2}: {3} {4}{5}, status {6}{7}",
                result.integrationId(), result.configVersion(), what, result.outcome(),
                result.code(), result.destinationCode() == null ? "" : " (" + result.destinationCode() + ")",
                result.httpStatus(), result.stepReached() == null ? "" : ", step " + result.stepReached());
    }

    /** The configured health check, classified by its own rules. */
    public HealthResult health(StoredIntegration integration) {
        return health(integration, CredentialState.CURRENT);
    }

    HealthResult health(StoredIntegration integration, CredentialState which) {
        IntegrationConfig c = integration.config();
        HealthCheck h = c.health();
        if (h == null || c.endpoint() == null) {
            return health(c, integration.version(), Outcome.NOT_CONFIGURED, IntegrationCode.INT_CONFIG_INVALID, null,
                    0, null);
        }
        Cert cert = certificate(c, which);
        if (cert.failure() != null) {
            return health(c, integration.version(), certificateOutcome(cert.failure()), cert.failure(), null, 0, null);
        }
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        if (h.signIn()) {
            SignInHeaders.Prepared p = signIn.prepare(c.signIn(), c.limits(), c.endpoint().useProxy(),
                    c.requiredTokenClaims(), cert.certificate());
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
                    c.endpoint().useProxy(), cert.certificate());
        } catch (Outbound.Failure f) {
            return health(c, integration.version(), failureOutcome(IntegrationCode.of(f.code())), IntegrationCode.of(f.code()), null, 0, null);
        }
        Object json = a.tooLarge() ? null : json(a.body());
        String destCode = destinationCode(c, json);
        boolean hasRetryAfter = a.header("Retry-After").isPresent();
        Classification.Decision d = Classification.of(h.rules()).decide(a.status(), destCode, json, hasRetryAfter);
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
        return checkSignIn(c, CredentialState.CURRENT);
    }

    SignInHeaders.Prepared checkSignIn(IntegrationConfig c, CredentialState which) {
        Cert cert = certificate(c, which);
        if (cert.failure() != null) {
            return new SignInHeaders.Prepared(List.of(), certificateOutcome(cert.failure()), cert.failure());
        }
        return signIn.prepare(c.signIn(), c.limits(), c.endpoint() == null || c.endpoint().useProxy(),
                c.requiredTokenClaims(), cert.certificate());
    }

    /** The client certificate (mutual TLS) and sign-in headers for a run of calls, or why there are none. */
    record Ready(ClientCertificate certificate, List<Map.Entry<String, String>> headers, Outcome failure,
                 IntegrationCode code) {
        boolean ok() {
            return failure == null;
        }
    }

    /** For the poll engine: the CURRENT certificate and the sign-in, as an attempt prepares them. */
    Ready ready(IntegrationConfig c) {
        Cert cert = certificate(c, CredentialState.CURRENT);
        if (cert.failure() != null) {
            return new Ready(null, List.of(), certificateOutcome(cert.failure()), cert.failure());
        }
        SignInHeaders.Prepared p = signIn.prepare(c.signIn(), c.limits(), c.endpoint().useProxy(),
                c.requiredTokenClaims(), cert.certificate());
        if (!p.ok()) return new Ready(null, List.of(), p.failure(), p.code());
        return new Ready(cert.certificate(), p.headers(), null, null);
    }

    /** After a source or destination refused the sign-in: a held OAuth 2.0 token is dropped. */
    void signInRefused(IntegrationConfig c) {
        signIn.refused(c.signIn());
    }

    Outbound outbound() {
        return outbound;
    }

    Clock clock() {
        return clock;
    }

    private HealthResult health(IntegrationConfig c, int version, Outcome o, IntegrationCode code, String destCode,
                                int status, Duration retryAfter) {
        return new HealthResult(c.id(), version, o, code, destCode, status, retryAfter, clock.instant());
    }

    // ---- The client certificate (mutual TLS) ----

    /** The certificate of a mutual TLS integration (or none needed), or why it cannot be used. */
    private record Cert(ClientCertificate certificate, IntegrationCode failure) {
        static final Cert NONE = new Cert(null, null);
    }

    private Cert certificate(IntegrationConfig c, CredentialState which) {
        if (!(c.signIn() instanceof SignIn.MutualTls)) return Cert.NONE;
        CertificateSource.Loaded l;
        try {
            l = certificates.load(c, which, clock.instant());
        } catch (RuntimeException e) {
            return new Cert(null, IntegrationCode.INT_CREDENTIAL_KEY_SERVICE_FAILED);
        }
        return l.ok() ? new Cert(l.certificate(), null) : new Cert(null, l.failure());
    }

    /** The result class when the client certificate cannot be used. */
    static Outcome certificateOutcome(IntegrationCode code) {
        return switch (code) {
            case INT_CREDENTIAL_EXPIRED, INT_CREDENTIAL_NOT_YET_VALID, INT_CREDENTIAL_REFUSED -> Outcome.SIGN_IN_FAILED;
            case INT_CREDENTIAL_KEY_SERVICE_FAILED, INT_SECRET_UNAVAILABLE -> Outcome.CANT_CONNECT;
            default -> Outcome.NOT_CONFIGURED;
        };
    }

    // ---- One attempt ----

    private final class Run {
        final IntegrationConfig c;
        final int version;
        final Delivery d;
        final boolean test;
        final CredentialState which;
        final String correlationId;
        final Map<String, String> values = new LinkedHashMap<>();
        final Map<String, Map<String, String>> documentValues = new LinkedHashMap<>();
        final Set<String> stepsDone = new HashSet<>();
        final Set<String> documentsDone = new HashSet<>();
        final List<StepResult> calls = new ArrayList<>();
        List<Map.Entry<String, String>> signInHeaders;
        ClientCertificate certificate;
        int lastStatus;
        boolean anyNew;

        Run(IntegrationConfig c, int version, Delivery d, boolean test, CredentialState which) {
            this.c = c;
            this.version = version;
            this.d = d;
            this.test = test;
            this.which = which;
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
            long total = 0;
            for (Delivery.Document doc : d.documents()) {
                if (doc.size() > c.limits().maxFileBytes()) {
                    return stop(Outcome.REFUSED, IntegrationCode.INT_FILE_TOO_LARGE, null, 0, null, null,
                            doc.sourceDocumentId());
                }
                total += doc.size();
            }
            if (total > c.limits().maxPackageBytes()) {
                return stop(Outcome.REFUSED, IntegrationCode.INT_PACKAGE_TOO_LARGE, null, 0, null, null, null);
            }
            AttemptResult sensitive = sensitiveFields();
            if (sensitive != null) return sensitive;
            Cert cert = certificate(c, which);
            if (cert.failure() != null) {
                return stop(certificateOutcome(cert.failure()), cert.failure(), null, 0, null, null, null);
            }
            certificate = cert.certificate();
            SignInHeaders.Prepared p = signIn.prepare(c.signIn(), c.limits(), c.endpoint().useProxy(),
                    c.requiredTokenClaims(), certificate);
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

        /**
         * Every {@code {field:<key>}} the templates use, before anything is sent: a sensitive field the configuration
         * does not allow, or one placed outside a JSON body, stops the attempt (the configuration check at save does
         * the same with the field list; this uses the delivery's own sensitive flags).
         */
        AttemptResult sensitiveFields() {
            for (CallStep s : c.steps()) {
                List<Template> ts = new ArrayList<>();
                try {
                    ts.add(Template.parse(s.path(), Template.Place.PATH));
                    for (String h : s.headers().values()) ts.add(Template.parse(h, Template.Place.HEADER));
                    if (s.bodyTemplate() != null) ts.add(Template.parse(s.bodyTemplate(), Template.Place.JSON));
                    if (s.multipart() != null) {
                        ts.add(Template.parse(s.multipart().fileNameTemplate(), Template.Place.HEADER));
                    }
                } catch (Template.Problem p) {
                    return stop(Outcome.NOT_CONFIGURED, IntegrationCode.INT_CONFIG_INVALID, null, 0, null, s.name(),
                            null);
                }
                for (Template t : ts) {
                    for (String p : t.placeholders()) {
                        if (!p.startsWith(Template.FIELD_PREFIX)) continue;
                        String key = p.substring(Template.FIELD_PREFIX.length());
                        Optional<CaseFields.Field> f = d.fields().field(key);
                        if (f.isPresent() && f.get().sensitive() && (!c.allowedSensitiveFields().contains(key)
                                || t.place() != Template.Place.JSON)) {
                            return stop(Outcome.NOT_CONFIGURED, IntegrationCode.INT_SENSITIVE_FIELD_NOT_ALLOWED, null,
                                    0, null, s.name(), null);
                        }
                    }
                }
            }
            return null;
        }

        /** The values of one call: the package, a document (or none), and the documents for the each-block. */
        final class Ctx implements Template.Values {
            private final Delivery.Document doc;
            private final Map<String, String> docVals;

            Ctx(Delivery.Document doc, Map<String, String> docVals) {
                this.doc = doc;
                this.docVals = docVals;
            }

            @Override
            public Optional<String> text(String name) {
                if (name.startsWith(Template.FIELD_PREFIX)) {
                    return d.fields().field(name.substring(Template.FIELD_PREFIX.length())).flatMap(CaseFields::text);
                }
                return lookup(name, doc, docVals);
            }

            @Override
            public Optional<String> json(String name) {
                if (!name.startsWith(Template.FIELD_PREFIX)) return Optional.empty();
                return d.fields().field(name.substring(Template.FIELD_PREFIX.length())).flatMap(CaseFields::json);
            }

            @Override
            public boolean has(String name) {
                if (name.startsWith(Template.FIELD_PREFIX)) {
                    return d.fields().field(name.substring(Template.FIELD_PREFIX.length())).isPresent();
                }
                return lookup(name, doc, docVals).isPresent();
            }

            @Override
            public List<Template.Values> documents() {
                List<Template.Values> out = new ArrayList<>();
                for (Delivery.Document dd : d.documents()) {
                    out.add(new Ctx(dd, documentValues.getOrDefault(dd.sourceDocumentId(), Map.of())));
                }
                return out;
            }
        }

        /** Makes one call; returns the attempt's result if it ends here, else null. */
        AttemptResult call(CallStep s, Delivery.Document doc) {
            String docId = doc == null ? null : doc.sourceDocumentId();
            Map<String, String> docVals = doc == null ? null
                    : documentValues.computeIfAbsent(docId, k -> new LinkedHashMap<>());
            Ctx lookup = new Ctx(doc, docVals);

            String path;
            List<Map.Entry<String, String>> headers = new ArrayList<>(signInHeaders);
            Map<String, String> lower = new HashMap<>();
            HttpRequest.BodyPublisher body = null;
            List<InputStream> opened = new ArrayList<>();
            try {
                path = Template.parse(s.path(), Template.Place.PATH).fill(lookup);
                for (Map.Entry<String, String> h : s.headers().entrySet()) {
                    String v = Template.parse(h.getValue(), Template.Place.HEADER).fill(lookup);
                    headers.add(entry(h.getKey(), v));
                    lower.put(h.getKey().toLowerCase(Locale.ROOT), v);
                }
                if (s.body() == CallStep.Body.JSON) {
                    String json = Template.parse(s.bodyTemplate(), Template.Place.JSON).fill(lookup);
                    body = HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8);
                    if (!lower.containsKey("content-type")) headers.add(entry("Content-Type", "application/json"));
                }
                if (s.body() == CallStep.Body.MULTIPART) {
                    String boundary = "----platform-integration-" + UUID.randomUUID();
                    try {
                        body = multipart(s, lookup, boundary, opened);
                    } catch (IOException | RuntimeException e) {
                        close(opened);
                        return stop(Outcome.NOT_CONFIGURED, IntegrationCode.INT_CONTENT_UNREADABLE, null, 0, null,
                                s.name(), docId);
                    }
                    headers.removeIf(h -> h.getKey().equalsIgnoreCase("Content-Type"));
                    lower.remove("content-type");
                    headers.add(entry("Content-Type", "multipart/form-data; boundary=" + boundary));
                }
            } catch (Template.Problem p) {
                close(opened);
                return stop(Outcome.NOT_CONFIGURED, p.code(), null, 0, null, s.name(), docId);
            }
            if (s.body() == CallStep.Body.FILE) {
                InputStream content;
                try {
                    content = doc.content().open();
                } catch (IOException | RuntimeException e) {
                    return stop(Outcome.NOT_CONFIGURED, IntegrationCode.INT_CONTENT_UNREADABLE, null, 0, null,
                            s.name(), docId);
                }
                opened.add(content);
                body = HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(() -> content),
                        doc.size());
                if (!lower.containsKey("content-type")) headers.add(entry("Content-Type", doc.contentType()));
            }
            if (c.sendCorrelationId()) headers.add(entry("X-Correlation-Id", correlationId));
            if (!lower.containsKey("accept")) headers.add(entry("Accept", "application/json"));

            Outbound.Answer a;
            try {
                a = outbound.send(new Outbound.Call(s.method().name(), address(c, path), headers, body,
                                c.limits().readTimeout()), c.limits().connectTimeout(), c.limits().maxResponseBytes(),
                        c.endpoint().useProxy(), certificate);
            } catch (Outbound.Failure f) {
                calls.add(new StepResult(s.name(), docId, failureOutcome(IntegrationCode.of(f.code())), IntegrationCode.of(f.code()), 0, null));
                return stop(failureOutcome(IntegrationCode.of(f.code())), IntegrationCode.of(f.code()), null, 0, null, s.name(), docId);
            } finally {
                close(opened);
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
            Classification.Decision dec = Classification.of(s.rules(), c.rules()).decide(a.status(), destCode, json,
                    a.header("Retry-After").isPresent());
            if (dec.outcome() == Outcome.SENT && a.status() == 202 && s.accepted() != null && doc == null) {
                return accepted(s, a, json, retryAfter);
            }
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
            // The destination's own record of the document against what was sent.
            if (s.integrity() != null && doc != null) {
                String key = s.name() + ".";
                String sha = s.integrity().sha256Value() == null ? null : docVals.get(key + s.integrity().sha256Value());
                String size = s.integrity().sizeValue() == null ? null : docVals.get(key + s.integrity().sizeValue());
                if ((sha != null && !sha.equalsIgnoreCase(doc.sha256()))
                        || (size != null && !size.equals(Long.toString(doc.size())))) {
                    calls.set(calls.size() - 1, new StepResult(s.name(), docId, Outcome.UNCLEAR,
                            IntegrationCode.INT_INTEGRITY_MISMATCH, a.status(), destCode));
                    return finish(Outcome.UNCLEAR, IntegrationCode.INT_INTEGRITY_MISMATCH, destCode, a.status(), null,
                            true, s.name(), docId);
                }
            }
            return null;
        }

        /** A 202 on a step that declares "accepted, check later": the attempt ends ACCEPTED with what to ask later. */
        AttemptResult accepted(CallStep s, Outbound.Answer a, Object json, Duration retryAfter) {
            CallStep.Accepted acc = s.accepted();
            for (Map.Entry<String, String> r : s.read().entrySet()) {
                Optional<String> v = json == null ? Optional.empty() : scalar(Json.at(json, r.getValue()));
                v.ifPresent(x -> values.put(s.name() + "." + r.getKey(), x));
            }
            Optional<String> reference = reference(c, acc, a, json);
            if (reference.isEmpty()) {
                calls.add(new StepResult(s.name(), null, Outcome.UNCLEAR, IntegrationCode.INT_STATUS_REFERENCE_MISSING,
                        a.status(), null));
                return finish(Outcome.UNCLEAR, IntegrationCode.INT_STATUS_REFERENCE_MISSING, null, a.status(), null,
                        true, s.name(), null);
            }
            calls.add(new StepResult(s.name(), null, Outcome.ACCEPTED, IntegrationCode.INT_ACCEPTED, a.status(), null));
            anyNew = true;
            stepsDone.add(s.name());
            Instant next = clock.instant().plus(nextWait(retryAfter, acc));
            PendingStatus pending = new PendingStatus(c.id(), version, s.name(), reference.get(), next,
                    c.sendCorrelationId() ? correlationId : null, values);
            return finish(Outcome.ACCEPTED, IntegrationCode.INT_ACCEPTED, null, a.status(), retryAfter, false,
                    s.name(), null, pending);
        }

        /**
         * multipart/form-data: the JSON part, then one file part per document, streamed; the length is known, so it
         * is sent with Content-Length. The documents' streams are added to {@code opened} and closed by the caller.
         */
        HttpRequest.BodyPublisher multipart(CallStep s, Ctx lookup, String boundary, List<InputStream> opened)
                throws Template.Problem, IOException {
            String json = Template.parse(s.bodyTemplate(), Template.Place.JSON).fill(lookup);
            Template fileName = Template.parse(s.multipart().fileNameTemplate(), Template.Place.HEADER);
            List<HttpRequest.BodyPublisher> parts = new ArrayList<>();
            parts.add(HttpRequest.BodyPublishers.ofString("--" + boundary + "\r\nContent-Disposition: form-data; name=\""
                    + s.multipart().jsonPart() + "\"\r\nContent-Type: application/json\r\n\r\n" + json + "\r\n",
                    StandardCharsets.UTF_8));
            for (Delivery.Document doc : d.documents()) {
                String name = fileName.fill(new Ctx(doc, documentValues.getOrDefault(doc.sourceDocumentId(), Map.of())));
                if (name.contains("\"") || name.contains("\\")) {
                    throw new Template.Problem(IntegrationCode.INT_VALUE_NOT_ALLOWED, "doc.fileName");
                }
                parts.add(HttpRequest.BodyPublishers.ofString("--" + boundary + "\r\nContent-Disposition: form-data; "
                        + "name=\"" + s.multipart().filePart() + "\"; filename=\"" + name + "\"\r\nContent-Type: "
                        + doc.contentType() + "\r\n\r\n", StandardCharsets.UTF_8));
                InputStream in = doc.content().open();
                opened.add(in);
                parts.add(HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(() -> in),
                        doc.size()));
                parts.add(HttpRequest.BodyPublishers.ofString("\r\n", StandardCharsets.UTF_8));
            }
            parts.add(HttpRequest.BodyPublishers.ofString("--" + boundary + "--\r\n", StandardCharsets.UTF_8));
            return HttpRequest.BodyPublishers.concat(parts.toArray(HttpRequest.BodyPublisher[]::new));
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
            if (name.startsWith(Template.ATTR_PREFIX)) {
                return doc == null ? Optional.empty()
                        : Optional.ofNullable(doc.attributes().get(name.substring(Template.ATTR_PREFIX.length())));
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
            return finish(o, code, destCode, status, retryAfter, attemptUsed, step, docId, null);
        }

        AttemptResult finish(Outcome o, IntegrationCode code, String destCode, int status, Duration retryAfter,
                             boolean attemptUsed, String step, String docId, PendingStatus pending) {
            Map<String, Map<String, String>> dv = new LinkedHashMap<>();
            documentValues.forEach((k, v) -> {
                if (!v.isEmpty()) dv.put(k, Map.copyOf(v));
            });
            return new AttemptResult(c.id(), version, o, code, destCode, status, retryAfter, attemptUsed,
                    c.sendCorrelationId() ? correlationId : null, step, docId, values, dv, stepsDone, documentsDone,
                    calls, test, pending);
        }
    }

    // ---- Accepted, check later ----

    /**
     * One status call for work the destination accepted earlier ({@link Outcome#ACCEPTED}): SENT when its status is
     * done (the values the configuration reads from it added), REFUSED ({@code INT_STATUS_FAILED}) when it failed,
     * ACCEPTED again (with the next check time: the destination's Retry-After, or the configured wait) while it is
     * still working, or the usual failures of the status call itself (refused, sign-in, busy, outage, can't connect,
     * unclear). Never waits and never retries; the app calls it again at or after {@code nextCheckAt}.
     *
     * @param integration the integration's current version (the step is found by name)
     */
    public AttemptResult checkStatus(StoredIntegration integration, PendingStatus pending) {
        IntegrationConfig c = integration.config();
        Status st = new Status(c, integration.version(), pending);
        AttemptResult r = st.go();
        log(r, " (status)");
        return r;
    }

    private final class Status {
        final IntegrationConfig c;
        final int version;
        final PendingStatus pending;
        final Map<String, String> values;

        Status(IntegrationConfig c, int version, PendingStatus pending) {
            this.c = c;
            this.version = version;
            this.pending = pending;
            this.values = new LinkedHashMap<>(pending.values());
        }

        AttemptResult go() {
            if (!c.id().equals(pending.integrationId())) throw new IllegalArgumentException("Another integration");
            if (!c.enabled()) return result(Outcome.NOT_CONFIGURED, IntegrationCode.INT_DISABLED, null, 0, null, null);
            CallStep s = c.steps().stream().filter(x -> x.name().equals(pending.step())).findFirst().orElse(null);
            if (c.kind() != Kind.API_PUSH || s == null || s.accepted() == null
                    || !ConfigCheck.check(c, outbound.installation()).ok()) {
                return result(Outcome.NOT_CONFIGURED, IntegrationCode.INT_CONFIG_INVALID, null, 0, null, null);
            }
            CallStep.Accepted acc = s.accepted();
            Cert cert = certificate(c, CredentialState.CURRENT);
            if (cert.failure() != null) {
                return result(certificateOutcome(cert.failure()), cert.failure(), null, 0, null, null);
            }
            SignInHeaders.Prepared p = signIn.prepare(c.signIn(), c.limits(), c.endpoint().useProxy(),
                    c.requiredTokenClaims(), cert.certificate());
            if (!p.ok()) return result(p.failure(), p.code(), null, 0, null, null);
            URI uri;
            if (acc.referencePointer() == null) {
                uri = sameHost(c, pending.reference()).orElse(null);
                if (uri == null) {
                    return result(Outcome.UNCLEAR, IntegrationCode.INT_STATUS_REFERENCE_MISSING, null, 0, null, null);
                }
            } else {
                try {
                    uri = address(c, Template.parse(acc.statusPath(), Template.Place.PATH).render(this::lookup));
                } catch (Template.Problem e) {
                    return result(Outcome.NOT_CONFIGURED, e.code(), null, 0, null, null);
                }
            }
            List<Map.Entry<String, String>> headers = new ArrayList<>(p.headers());
            if (pending.correlationId() != null) headers.add(entry("X-Correlation-Id", pending.correlationId()));
            headers.add(entry("Accept", "application/json"));
            Outbound.Answer a;
            try {
                a = outbound.send(new Outbound.Call(acc.statusMethod().name(), uri, headers, null,
                                c.limits().readTimeout()), c.limits().connectTimeout(), c.limits().maxResponseBytes(),
                        c.endpoint().useProxy(), cert.certificate());
            } catch (Outbound.Failure f) {
                return result(failureOutcome(IntegrationCode.of(f.code())), IntegrationCode.of(f.code()), null, 0, null, null);
            }
            Duration retryAfter = RetryAfter.parse(a.header("Retry-After").orElse(null), clock.instant());
            if (a.tooLarge()) {
                return result(Outcome.UNCLEAR, IntegrationCode.INT_RESPONSE_TOO_LARGE, null, a.status(), null, null);
            }
            Object json = json(a.body());
            String destCode = destinationCode(c, json);
            Classification.Decision dec = Classification.of(s.rules(), c.rules()).decide(a.status(), destCode, json,
                    a.header("Retry-After").isPresent());
            if (dec.outcome() == Outcome.SIGN_IN_FAILED) signIn.refused(c.signIn());
            if (dec.outcome() != Outcome.SENT && dec.outcome() != Outcome.ALREADY_RECEIVED) {
                return new AttemptResult(c.id(), version, dec.outcome(), dec.code(), destCode, a.status(), retryAfter,
                        dec.attemptUsed(), pending.correlationId(), s.name(), null, values, Map.of(),
                        Set.of(s.name()), Set.of(), List.of(new StepResult(s.name(), null, dec.outcome(), dec.code(),
                        a.status(), destCode)), false);
            }
            String state = json == null ? null : scalar(Json.at(json, acc.statePointer())).orElse(null);
            if (a.status() == 202 || (state != null && acc.workingValues().contains(state))) {
                PendingStatus again = pending.nextCheckAt(clock.instant().plus(nextWait(retryAfter, acc)));
                return new AttemptResult(c.id(), version, Outcome.ACCEPTED, IntegrationCode.INT_ACCEPTED, null,
                        a.status(), retryAfter, false, pending.correlationId(), s.name(), null, values, Map.of(),
                        Set.of(s.name()), Set.of(), List.of(new StepResult(s.name(), null, Outcome.ACCEPTED,
                        IntegrationCode.INT_ACCEPTED, a.status(), null)), false, again);
            }
            if (state != null && acc.doneValues().contains(state)) {
                for (Map.Entry<String, String> r : acc.read().entrySet()) {
                    scalar(Json.at(json, r.getValue())).ifPresent(v -> values.put(s.name() + "." + r.getKey(), v));
                }
                return result(Outcome.SENT, IntegrationCode.INT_SENT, null, a.status(), null, null);
            }
            if (state != null && acc.failedValues().contains(state)) {
                return result(Outcome.REFUSED, IntegrationCode.INT_STATUS_FAILED, destCode, a.status(), null, null);
            }
            return result(Outcome.UNCLEAR, IntegrationCode.INT_STATUS_UNKNOWN, destCode, a.status(), null, null);
        }

        Optional<String> lookup(String name) {
            if (name.equals(CallStep.Accepted.REFERENCE)) return Optional.of(pending.reference());
            if (name.equals("correlationId")) return Optional.ofNullable(pending.correlationId());
            if (name.startsWith("step.")) return Optional.ofNullable(values.get(name.substring(5)));
            return Optional.empty();
        }

        AttemptResult result(Outcome o, IntegrationCode code, String destCode, int status, Duration retryAfter,
                             PendingStatus again) {
            List<StepResult> calls = status == 0 && o != Outcome.SENT ? List.of()
                    : List.of(new StepResult(pending.step(), null, o, code, status, destCode));
            return new AttemptResult(c.id(), version, o, code, destCode, status, retryAfter,
                    o.usesAttemptByDefault(), pending.correlationId(), pending.step(), null, values, Map.of(),
                    Set.of(pending.step()), Set.of(), calls, false, again);
        }
    }

    /** The status reference of a 202 answer: from the pointer, or the Location header (on the same host). */
    private static Optional<String> reference(IntegrationConfig c, CallStep.Accepted acc, Outbound.Answer a,
                                              Object json) {
        if (acc.referencePointer() != null) {
            Optional<String> v = json == null ? Optional.empty() : scalar(Json.at(json, acc.referencePointer()));
            return v.filter(x -> x.length() <= MAX_VALUE);
        }
        Optional<String> location = a.header("Location");
        if (location.isEmpty() || sameHost(c, location.get()).isEmpty()) return Optional.empty();
        return location;
    }

    /** A Location (absolute, or a path) resolved against the base address, only when it stays on the same host. */
    static Optional<URI> sameHost(IntegrationConfig c, String location) {
        try {
            if (location.length() > 2000 || location.chars().anyMatch(ch -> ch <= 0x20 || ch == 0x7f)) {
                return Optional.empty();
            }
            URI base = c.endpoint().baseAddress();
            URI target = base.resolve(location);
            if (!base.getScheme().equalsIgnoreCase(target.getScheme())
                    || !base.getHost().equalsIgnoreCase(target.getHost()) || base.getPort() != target.getPort()
                    || target.getRawUserInfo() != null || target.getRawFragment() != null) {
                return Optional.empty();
            }
            return Optional.of(target);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Duration nextWait(Duration retryAfter, CallStep.Accepted acc) {
        Duration d = retryAfter != null && !retryAfter.isNegative() ? retryAfter : acc.defaultWait();
        if (d.compareTo(Duration.ofSeconds(1)) < 0) d = Duration.ofSeconds(1);
        return d.compareTo(CallStep.Accepted.MAX_WAIT) > 0 ? CallStep.Accepted.MAX_WAIT : d;
    }

    // ---- Callbacks ----

    /**
     * One signed callback to the integration's receiver (kind {@code CALLBACK}): a POST of {@code body} (JSON the app
     * gives; never document content) with {@code Platform-Signature} and {@code Platform-Callback-Id}, classified like
     * an API push call. Never retries: the outbox ({@code callback.Callbacks}) does.
     *
     * @param callbackId the callback's unique ID, the same for every attempt of it (receivers ignore repeats by it)
     */
    public AttemptResult sendCallback(StoredIntegration integration, UUID callbackId, String body) {
        IntegrationConfig c = integration.config();
        AttemptResult r = callback(c, integration.version(), callbackId, body);
        log(r, " (callback " + callbackId + ")");
        return r;
    }

    private AttemptResult callback(IntegrationConfig c, int version, UUID callbackId, String body) {
        if (!c.enabled()) return callbackResult(c, version, Outcome.NOT_CONFIGURED, IntegrationCode.INT_DISABLED, 0,
                null, null);
        if (c.kind() != Kind.CALLBACK) {
            return callbackResult(c, version, Outcome.NOT_CONFIGURED, IntegrationCode.INT_KIND_NOT_SUPPORTED, 0, null,
                    null);
        }
        if (!ConfigCheck.check(c, outbound.installation()).ok()) {
            return callbackResult(c, version, Outcome.NOT_CONFIGURED, IntegrationCode.INT_CONFIG_INVALID, 0, null,
                    null);
        }
        Optional<Secret> secret;
        try {
            secret = secrets.resolve(c.callback().signingSecret());
        } catch (RuntimeException storeDown) {
            return callbackResult(c, version, Outcome.CANT_CONNECT, IntegrationCode.INT_SECRET_UNAVAILABLE, 0, null,
                    null);
        }
        if (secret.isEmpty()) {
            return callbackResult(c, version, Outcome.NOT_CONFIGURED, IntegrationCode.INT_SECRET_NOT_FOUND, 0, null,
                    null);
        }
        Cert cert = certificate(c, CredentialState.CURRENT);
        if (cert.failure() != null) {
            return callbackResult(c, version, certificateOutcome(cert.failure()), cert.failure(), 0, null, null);
        }
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        if (!(c.signIn() instanceof SignIn.None)) { // the signature proves the sender; NONE adds nothing
            SignInHeaders.Prepared p = signIn.prepare(c.signIn(), c.limits(), c.endpoint().useProxy(),
                    c.requiredTokenClaims(), cert.certificate());
            if (!p.ok()) return callbackResult(c, version, p.failure(), p.code(), 0, null, null);
            headers.addAll(p.headers());
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        long t = clock.instant().getEpochSecond();
        headers.add(entry("Content-Type", "application/json"));
        headers.add(entry("Accept", "application/json"));
        headers.add(entry(CallbackSignature.SIGNATURE_HEADER, CallbackSignature.header(secret.get().reveal(), t, bytes)));
        headers.add(entry(CallbackSignature.ID_HEADER, callbackId.toString()));
        Outbound.Answer a;
        try {
            a = outbound.send(new Outbound.Call("POST", c.endpoint().baseAddress(), headers,
                            HttpRequest.BodyPublishers.ofByteArray(bytes), c.limits().readTimeout()),
                    c.limits().connectTimeout(), c.limits().maxResponseBytes(), c.endpoint().useProxy(),
                    cert.certificate());
        } catch (Outbound.Failure f) {
            return callbackResult(c, version, failureOutcome(IntegrationCode.of(f.code())), IntegrationCode.of(f.code()), 0, null, null);
        }
        Object json = a.tooLarge() ? null : json(a.body());
        String destCode = destinationCode(c, json);
        Classification.Decision dec = Classification.of(c.rules()).decide(a.status(), destCode, json,
                a.header("Retry-After").isPresent());
        if (dec.outcome() == Outcome.SIGN_IN_FAILED) signIn.refused(c.signIn());
        Duration retryAfter = RetryAfter.parse(a.header("Retry-After").orElse(null), clock.instant());
        return new AttemptResult(c.id(), version, dec.outcome(), dec.code(), destCode, a.status(), retryAfter,
                dec.attemptUsed(), null, CALLBACK_STEP, null, Map.of(), Map.of(),
                dec.outcome() == Outcome.SENT || dec.outcome() == Outcome.ALREADY_RECEIVED ? Set.of(CALLBACK_STEP)
                        : Set.of(), Set.of(), List.of(new StepResult(CALLBACK_STEP, null, dec.outcome(), dec.code(),
                a.status(), destCode)), false);
    }

    private static AttemptResult callbackResult(IntegrationConfig c, int version, Outcome o, IntegrationCode code,
                                                int status, String destCode, Duration retryAfter) {
        return new AttemptResult(c.id(), version, o, code, destCode, status, retryAfter, o.usesAttemptByDefault(),
                null, CALLBACK_STEP, null, Map.of(), Map.of(), Set.of(), Set.of(), List.of(), false);
    }

    // ---- Helpers ----

    static URI address(IntegrationConfig c, String path) {
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
            case INT_ADDRESS_NOT_SECURE, INT_VALUE_NOT_ALLOWED, INT_SECRET_NOT_FOUND -> Outcome.NOT_CONFIGURED;
            case INT_CREDENTIAL_REFUSED -> Outcome.SIGN_IN_FAILED;
            default -> Outcome.CANT_CONNECT;
        };
    }

    static Object json(byte[] body) {
        if (body.length == 0) return null;
        try {
            return Json.parse(new String(body, StandardCharsets.UTF_8));
        } catch (Json.Invalid e) {
            return null;
        }
    }

    static String destinationCode(IntegrationConfig c, Object json) {
        if (c.errorCodePointer() == null || json == null) return null;
        Object v = Json.at(json, c.errorCodePointer()).orElse(null);
        if (!(v instanceof String) && !(v instanceof Long)) return null;
        String s = String.valueOf(v);
        return SAFE_CODE.matcher(s).matches() ? s : null;
    }

    static Optional<String> scalar(Optional<Object> v) {
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

    private static void close(List<InputStream> streams) {
        for (InputStream in : streams) {
            try {
                in.close();
            } catch (IOException ignored) {
                // nothing to do: the stream was only read
            }
        }
        streams.clear();
    }

    static Map.Entry<String, String> entry(String k, String v) {
        return new AbstractMap.SimpleImmutableEntry<>(k, v);
    }

    @Override
    public void close() {
        outbound.close();
    }
}
