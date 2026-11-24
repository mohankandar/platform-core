package com.documentplatform.integration.engine;

import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.integration.config.CallStep;
import com.documentplatform.integration.config.internal.Classification;
import com.documentplatform.integration.config.ConfigCheck;
import com.documentplatform.integration.config.FolderTarget;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.Kind;
import com.documentplatform.integration.config.PollSource;
import com.documentplatform.integration.config.Template;
import com.documentplatform.outbound.Outbound;
import com.documentplatform.outbound.RetryAfter;
import com.documentplatform.integration.intake.CursorState;
import com.documentplatform.integration.intake.IntakeAnswer;
import com.documentplatform.integration.intake.IntakeFile;
import com.documentplatform.integration.intake.IntakeHandler;
import com.documentplatform.integration.intake.LogRef;
import com.documentplatform.integration.intake.PollResult;
import com.documentplatform.integration.intake.PolledItem;
import com.documentplatform.integration.json.internal.Json;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.integration.scan.ScanEvents;
import com.documentplatform.integration.scan.ScanResult;
import com.documentplatform.integration.scan.Scanning;
import com.documentplatform.integration.store.StoredIntegration;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * API polling (kind {@code API_POLL}): one pass over the source per call; the app keeps the schedule and the cursor.
 * The calls go through the same engine as outbound: the integration's sign-in (and client certificate), address guard,
 * pinning, proxy, private CA, limits and classification rules.
 *
 * <p>A pass reads at most the configured pages. For each item of a page, in order:
 * <ol>
 *   <li>its source item ID (a pointer into the item) and the configured values are read; an item without an ID is
 *       reported and left alone;</li>
 *   <li>the document is downloaded, streamed to a temporary file with the size limit and its SHA-256; a download the
 *       source refuses, or one too large, is reported and left alone;</li>
 *   <li>it is scanned when a scanner is configured: an infected file is quarantined (never handed over, not
 *       acknowledged, the app told through {@link ScanEvents}); a file that could not be scanned stops the pass, so it
 *       is scanned again at the next one;</li>
 *   <li>the app's {@link IntakeHandler} answers TAKEN, ALREADY_TAKEN or REFUSED;</li>
 *   <li>only after TAKEN or ALREADY_TAKEN is it acknowledged, when the source has an acknowledge call.</li>
 * </ol>
 * Anything else that fails (sign-in, busy, outage, no connection, an unclear answer, an acknowledge, the handler)
 * stops the pass with the usual classified outcome and where it stopped. The next state is then the position after
 * the last page fully handled: a restart, or the next pass, hands the rest over again, and the app's idempotency
 * answers ALREADY_TAKEN for what it has.
 *
 * <p>Logs hold the integration, version, step, item ID, status and codes; never a value, a cursor or content.
 */
public final class PollEngine {

    private static final System.Logger LOG = System.getLogger(PollEngine.class.getName());
    private static final int MAX_ID = 256;
    private static final Pattern LINK_NEXT = Pattern.compile("<([^>]{1,4000})>\\s*;[^,]*\\brel\\s*=\\s*\"?next\"?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT_TYPE = Pattern.compile("[A-Za-z0-9!#$&^_.+-]{1,64}/[A-Za-z0-9!#$&^_.+-]{1,64}"
            + "(\\s*;[\\x20-\\x7e]{0,120})?");

    private final CallEngine calls;
    private final Scanning scanning;
    private final Path work;

    /**
     * @param calls      the call engine (its outbound rules, sign-in, certificates and clock)
     * @param scanning   the scanner, or {@link Scanning#OFF}
     * @param workFolder where downloads are kept while the handler runs (removed after each item); the app's own
     *                   temporary folder, readable by the service account only
     */
    public PollEngine(CallEngine calls, Scanning scanning, Path workFolder) {
        this.calls = Objects.requireNonNull(calls, "calls");
        this.scanning = Objects.requireNonNull(scanning, "scanning");
        this.work = Objects.requireNonNull(workFolder, "workFolder");
    }

    /** With the system's temporary folder. */
    public PollEngine(CallEngine calls, Scanning scanning) {
        this(calls, scanning, Path.of(System.getProperty("java.io.tmpdir")));
    }

    /**
     * One pass from {@code state} (or from the first page, for a source set to start there every pass).
     *
     * @param handler the app's side; called once per item, in the source's order
     */
    public PollResult pass(StoredIntegration stored, CursorState state, IntakeHandler<PolledItem> handler) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(handler, "handler");
        Pass p = new Pass(stored, state, handler);
        PollResult r = p.go();
        LOG.log(r.ok() ? System.Logger.Level.INFO : System.Logger.Level.WARNING, "Integration {0} v{1} poll: {2}",
                stored.config().id(), stored.version(), r);
        return r;
    }

    private final class Pass {
        final IntegrationConfig c;
        final int version;
        final PollSource src;
        final IntakeHandler<PolledItem> handler;
        final boolean fromStart;
        final String correlationId = UUID.randomUUID().toString();
        CursorState committed;
        String position;
        CallEngine.Ready ready;
        int pages;
        int taken;
        int alreadyTaken;
        int acknowledged;
        boolean more;
        final List<PollResult.Refusal> refused = new ArrayList<>();
        final List<PollResult.ItemProblem> problems = new ArrayList<>();
        final List<PollResult.Quarantined> quarantined = new ArrayList<>();

        Pass(StoredIntegration stored, CursorState state, IntakeHandler<PolledItem> handler) {
            this.c = stored.config();
            this.version = stored.version();
            this.src = c.poll();
            this.handler = handler;
            this.fromStart = src != null && src.paging().resume() == PollSource.Paging.Resume.FROM_START;
            this.committed = fromStart ? CursorState.START : state;
            this.position = fromStart ? null : state.position();
        }

        PollResult go() {
            if (!c.enabled()) return stop(Outcome.NOT_CONFIGURED, IntegrationCode.INT_DISABLED, null, 0, null, null, null);
            if (c.kind() != Kind.API_POLL || src == null) {
                return stop(Outcome.NOT_CONFIGURED, IntegrationCode.INT_KIND_NOT_SUPPORTED, null, 0, null, null, null);
            }
            ConfigCheck.Report check = ConfigCheck.check(c, calls.outbound().installation());
            if (!check.ok()) {
                return stop(Outcome.NOT_CONFIGURED, check.runCode(), null, 0, null, null, null);
            }
            ready = calls.ready(c);
            if (!ready.ok()) return stop(ready.failure(), ready.code(), null, 0, null, PollSource.LIST, null);
            PollSource.Paging g = src.paging();
            while (true) {
                URI uri = listAddress(g);
                if (uri == null) {
                    return stop(Outcome.UNCLEAR, IntegrationCode.INT_POLL_NEXT_PAGE_INVALID, null, 0, null,
                            PollSource.LIST, null);
                }
                Called list = call(PollSource.LIST, src.list(), uri, null, null);
                if (list.failure != null) return list.failure;
                Object items = Json.at(list.json, src.itemsPointer()).orElse(null);
                if (!(items instanceof List<?> page)) {
                    return stop(Outcome.UNCLEAR, IntegrationCode.INT_POLL_ITEMS_MISSING, null, list.answer.status(),
                            null, PollSource.LIST, null);
                }
                for (Object item : page) {
                    PollResult stopped = item(item);
                    if (stopped != null) return stopped;
                }
                pages++;
                Optional<String> next;
                try {
                    next = next(g, list, page.size());
                } catch (IllegalArgumentException invalid) {
                    return stop(Outcome.UNCLEAR, IntegrationCode.INT_POLL_NEXT_PAGE_INVALID, null,
                            list.answer.status(), null, PollSource.LIST, null);
                }
                if (next.isEmpty()) {
                    // The end: the next pass reads this last page again (new items may join it; what the app has is
                    // ALREADY_TAKEN).
                    committed = fromStart ? CursorState.START : CursorState.of(position);
                    break;
                }
                committed = fromStart ? CursorState.START : CursorState.of(next.get());
                if (page.isEmpty()) break; // an empty page with a next position: start there next time
                position = next.get();
                if (pages >= g.maxPagesPerPass()) {
                    more = true;
                    break;
                }
            }
            return result(null);
        }

        /** The list call's address for the current position, or null when a stored position cannot be followed. */
        URI listAddress(PollSource.Paging g) {
            if (g.mode() == PollSource.Paging.Mode.NEXT_LINK && position != null) {
                return CallEngine.sameHost(c, position).orElse(null);
            }
            String path;
            try {
                path = Template.parse(src.list().path(), Template.Place.PATH).render(this::passValue);
            } catch (Template.Problem e) {
                return null;
            }
            List<String> query = new ArrayList<>();
            switch (g.mode()) {
                case CURSOR -> {
                    if (position != null) query.add(param(g.param(), position));
                }
                case PAGE_NUMBER -> {
                    int pageNo;
                    try {
                        pageNo = position == null ? g.firstPage() : Integer.parseInt(position);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                    if (pageNo < 0) return null;
                    query.add(param(g.param(), Integer.toString(pageNo)));
                }
                default -> {
                }
            }
            if (g.sizeParam() != null && g.mode() != PollSource.Paging.Mode.NONE) {
                query.add(param(g.sizeParam(), Integer.toString(g.pageSize())));
            }
            if (!query.isEmpty()) path = path + (path.contains("?") ? "&" : "?") + String.join("&", query);
            return CallEngine.address(c, path);
        }

        /** The next position, empty at the end. @throws IllegalArgumentException when it cannot be followed */
        Optional<String> next(PollSource.Paging g, Called list, int size) {
            switch (g.mode()) {
                case NONE -> {
                    return Optional.empty();
                }
                case CURSOR -> {
                    Optional<String> v = CallEngine.scalar(Json.at(list.json, g.pointer()));
                    if (v.isPresent() && v.get().length() > CursorState.MAX) throw new IllegalArgumentException();
                    return v;
                }
                case PAGE_NUMBER -> {
                    if (size < g.pageSize()) return Optional.empty();
                    int current = position == null ? g.firstPage() : Integer.parseInt(position);
                    return Optional.of(Integer.toString(current + 1));
                }
                case NEXT_LINK -> {
                    String link = null;
                    if (g.pointer() != null) {
                        Object v = Json.at(list.json, g.pointer()).orElse(null);
                        if (v != null && !(v instanceof String)) throw new IllegalArgumentException();
                        link = (String) v;
                    } else {
                        for (String h : list.answer.headers().allValues("Link")) {
                            Matcher m = LINK_NEXT.matcher(h);
                            if (m.find()) {
                                link = m.group(1);
                                break;
                            }
                        }
                    }
                    if (link == null || link.isEmpty()) return Optional.empty();
                    URI u = CallEngine.sameHost(c, link).orElseThrow(IllegalArgumentException::new);
                    String s = u.toString();
                    if (s.length() > CursorState.MAX) throw new IllegalArgumentException();
                    return Optional.of(s);
                }
                default -> throw new IllegalStateException();
            }
        }

        // ---- One item ----

        PollResult item(Object item) {
            String id = CallEngine.scalar(Json.at(item, src.idPointer())).filter(v -> v.length() <= MAX_ID)
                    .orElse(null);
            if (id == null) {
                problems.add(new PollResult.ItemProblem(null, IntegrationCode.INT_POLL_ITEM_ID_MISSING, null, 0));
                LOG.log(System.Logger.Level.WARNING, "Integration {0} poll: an item without a usable ID was left alone",
                        c.id());
                return null;
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (Map.Entry<String, String> v : src.values().entrySet()) {
                CallEngine.scalar(Json.at(item, v.getValue())).ifPresent(x -> values.put(v.getKey(), x));
            }
            Path file;
            try {
                file = Files.createTempFile(work, "intake-", ".part");
            } catch (IOException | RuntimeException e) {
                return stop(Outcome.OUTAGE, IntegrationCode.INT_POLL_STORAGE_FAILED, null, 0, null, PollSource.DOWNLOAD,
                        id);
            }
            try {
                return item(id, values, file);
            } finally {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    LOG.log(System.Logger.Level.WARNING, "Integration {0} poll: a temporary download could not be "
                            + "removed", c.id());
                }
            }
        }

        PollResult item(String id, Map<String, String> values, Path file) {
            Download dl = new Download(file, c.limits().maxFileBytes());
            Called d;
            try (OutputStream out = Files.newOutputStream(file)) {
                dl.out = out;
                d = call(PollSource.DOWNLOAD, src.download(), null, itemValues(id, values), dl);
            } catch (IOException e) {
                return stop(Outcome.OUTAGE, IntegrationCode.INT_POLL_STORAGE_FAILED, null, 0, null,
                        PollSource.DOWNLOAD, id);
            }
            if (d.failure != null) {
                if (d.failure.failure().outcome() == Outcome.REFUSED) {
                    PollResult.Failure f = d.failure.failure();
                    problems.add(new PollResult.ItemProblem(id, f.code(), f.destinationCode(), f.httpStatus()));
                    return null;
                }
                return withItem(d.failure, id);
            }
            if (dl.tooLarge) {
                problems.add(new PollResult.ItemProblem(id, IntegrationCode.INT_FILE_TOO_LARGE, null,
                        d.answer.status()));
                return null;
            }
            if (dl.local != null) {
                return stop(Outcome.OUTAGE, IntegrationCode.INT_POLL_STORAGE_FAILED, null, 0, null,
                        PollSource.DOWNLOAD, id);
            }
            String sha = dl.sha256();
            long size = dl.size;
            IntakeFile.Content content = () -> Files.newInputStream(file);
            String name = FolderTarget.safeName(src.fileNameValue() != null && values.containsKey(src.fileNameValue())
                    ? values.get(src.fileNameValue()) : id);

            ScanResult scan = scanning.scan(content, size);
            if (scan.verdict() == ScanResult.Verdict.INFECTED) {
                quarantined.add(new PollResult.Quarantined(id, scan.threat()));
                LOG.log(System.Logger.Level.WARNING, "Integration {0} poll: item={1} infected ({2}); quarantined, not "
                        + "acknowledged", c.id(), LogRef.of(id), scan.threat());
                try {
                    scanning.events().infected(new ScanEvents.Infected(c.id(), c.entity(), c.product(), "poll", id,
                            name, scan.threat(), calls.clock().instant()));
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "Integration {0} poll: the infected-file event failed",
                            c.id());
                }
                return null;
            }
            if (scan.verdict() == ScanResult.Verdict.SCAN_FAILED) {
                return stop(scan.code() == IntegrationCode.INT_SCAN_FAILED ? Outcome.OUTAGE : Outcome.CANT_CONNECT,
                        scan.code(), null, 0, null, "scan", id);
            }

            IntakeFile f = new IntakeFile(name, size, sha, contentType(d.answer), content);
            IntakeAnswer answer;
            try {
                answer = handler.handle(new PolledItem(c.id(), id, values, f, correlationId));
            } catch (RuntimeException e) {
                answer = null;
            }
            if (answer == null) {
                return stop(Outcome.OUTAGE, IntegrationCode.INT_POLL_HANDLER_FAILED, null, 0, null, "handler", id);
            }
            switch (answer.kind()) {
                case TAKEN -> taken++;
                case ALREADY_TAKEN -> alreadyTaken++;
                case REFUSED -> {
                    refused.add(new PollResult.Refusal(id, answer.code()));
                    return null;
                }
            }
            if (src.acknowledge() != null) {
                Called a = call(PollSource.ACKNOWLEDGE, src.acknowledge(), null, itemValues(id, values), null);
                if (a.failure != null) return withItem(a.failure, id);
                acknowledged++;
            }
            return null;
        }

        Template.Values itemValues(String id, Map<String, String> values) {
            return new Template.Values() {
                @Override
                public Optional<String> text(String name) {
                    if (name.equals(Template.ITEM_ID)) return Optional.of(id);
                    if (name.startsWith(Template.ITEM + ".")) {
                        return Optional.ofNullable(values.get(name.substring(Template.ITEM.length() + 1)));
                    }
                    return passValue(name);
                }
            };
        }

        Optional<String> passValue(String name) {
            return name.equals("correlationId") ? Optional.of(correlationId) : Optional.empty();
        }

        // ---- One call ----

        final class Called {
            Outbound.Answer answer;
            Object json;
            PollResult failure;
        }

        /**
         * Makes one call of a step. {@code uri} is the list call's address (null: the step's own path); the values fill
         * the templates; {@code sink} receives a 2xx body (the download). The result holds the answer, or the pass's
         * end when it failed or was classified as anything but SENT or ALREADY_RECEIVED.
         */
        Called call(String name, CallStep s, URI uri, Template.Values values, Download sink) {
            Called r = new Called();
            Template.Values v = values != null ? values : new Template.Values() {
                @Override
                public Optional<String> text(String n) {
                    return passValue(n);
                }
            };
            List<Map.Entry<String, String>> headers = new ArrayList<>(ready.headers());
            Map<String, String> lower = new LinkedHashMap<>();
            HttpRequest.BodyPublisher body = null;
            try {
                if (uri == null) uri = CallEngine.address(c, Template.parse(s.path(), Template.Place.PATH).fill(v));
                for (Map.Entry<String, String> h : s.headers().entrySet()) {
                    String hv = Template.parse(h.getValue(), Template.Place.HEADER).fill(v);
                    headers.add(CallEngine.entry(h.getKey(), hv));
                    lower.put(h.getKey().toLowerCase(Locale.ROOT), hv);
                }
                if (s.body() == CallStep.Body.JSON) {
                    body = HttpRequest.BodyPublishers.ofString(
                            Template.parse(s.bodyTemplate(), Template.Place.JSON).fill(v), StandardCharsets.UTF_8);
                    if (!lower.containsKey("content-type")) {
                        headers.add(CallEngine.entry("Content-Type", "application/json"));
                    }
                }
            } catch (Template.Problem e) {
                r.failure = stop(Outcome.NOT_CONFIGURED, e.code(), null, 0, null, name, null);
                return r;
            }
            if (c.sendCorrelationId()) headers.add(CallEngine.entry("X-Correlation-Id", correlationId));
            if (!lower.containsKey("accept")) {
                headers.add(CallEngine.entry("Accept", sink != null ? "*/*" : "application/json"));
            }
            Outbound.Answer a;
            try {
                a = calls.outbound().send(new Outbound.Call(s.method().name(), uri, headers, body,
                                c.limits().readTimeout()), c.limits().connectTimeout(), c.limits().maxResponseBytes(),
                        c.endpoint().useProxy(), ready.certificate(), sink == null ? null : sink::read);
            } catch (Outbound.Failure f) {
                r.failure = stop(CallEngine.failureOutcome(IntegrationCode.of(f.code())), IntegrationCode.of(f.code()), null, 0, null, name, null);
                return r;
            }
            r.answer = a;
            Duration retryAfter = RetryAfter.parse(a.header("Retry-After").orElse(null), calls.clock().instant());
            boolean streamed = sink != null && a.status() / 100 == 2;
            if (a.tooLarge() && a.status() / 100 == 2) {
                r.failure = stop(Outcome.UNCLEAR, IntegrationCode.INT_RESPONSE_TOO_LARGE, null, a.status(), null,
                        name, null);
                return r;
            }
            r.json = streamed || a.tooLarge() ? null : CallEngine.json(a.body());
            String destCode = CallEngine.destinationCode(c, r.json);
            Classification.Decision dec = Classification.of(s.rules(), c.rules()).decide(a.status(), destCode, r.json,
                    a.header("Retry-After").isPresent());
            if (dec.outcome() == Outcome.SIGN_IN_FAILED) calls.signInRefused(c);
            if (dec.outcome() != Outcome.SENT && dec.outcome() != Outcome.ALREADY_RECEIVED) {
                r.failure = stop(dec.outcome(), dec.code(), destCode, a.status(), retryAfter, name, null);
                return r;
            }
            if (!streamed && name.equals(PollSource.LIST) && r.json == null) {
                r.failure = stop(Outcome.UNCLEAR, IntegrationCode.INT_POLL_ITEMS_MISSING, null, a.status(), null,
                        name, null);
            }
            return r;
        }

        // ---- Results ----

        PollResult stop(Outcome o, IntegrationCode code, String destCode, int status, Duration retryAfter,
                        String step, String itemId) {
            return result(new PollResult.Failure(o, code, destCode, status, retryAfter, step, itemId));
        }

        PollResult withItem(PollResult stopped, String itemId) {
            PollResult.Failure f = stopped.failure();
            return result(new PollResult.Failure(f.outcome(), f.code(), f.destinationCode(), f.httpStatus(),
                    f.retryAfter(), f.step(), itemId));
        }

        PollResult result(PollResult.Failure failure) {
            return new PollResult(c.id(), version, committed, failure == null && more, pages, taken, alreadyTaken,
                    refused, acknowledged, problems, quarantined, failure,
                    c.sendCorrelationId() ? correlationId : null);
        }
    }

    private static String param(String name, String value) {
        return name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String contentType(Outbound.Answer a) {
        return a.header("Content-Type").map(String::strip).filter(v -> CONTENT_TYPE.matcher(v).matches()).orElse(null);
    }

    /** A download into a temporary file: SHA-256 and size, stopped at the size limit; a local failure recorded. */
    private static final class Download {
        final Path file;
        final long max;
        final MessageDigest digest;
        OutputStream out;
        long size;
        boolean tooLarge;
        IOException local;

        Download(Path file, long max) {
            this.file = file;
            this.max = max;
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        boolean read(InputStream in) throws IOException {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                size += n;
                if (size > max) {
                    tooLarge = true;
                    return false;
                }
                digest.update(buf, 0, n);
                try {
                    out.write(buf, 0, n);
                } catch (IOException e) {
                    local = e;
                    return false;
                }
            }
            try {
                out.flush();
            } catch (IOException e) {
                local = e;
            }
            return true;
        }

        String sha256() {
            return HexFormat.of().formatHex(digest.digest());
        }

        @Override
        public String toString() {
            return "Download[" + file.getFileName() + "]";
        }
    }
}
