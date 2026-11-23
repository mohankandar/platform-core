package com.documentplatform.integration.testkit;

import com.documentplatform.integration.config.CallStep;
import com.documentplatform.integration.config.PollSource;
import com.documentplatform.integration.json.Json;
import com.documentplatform.integration.profile.IntegrationProfile;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A mock source API for API polling tests (JDK {@code HttpServer}, plain http on 127.0.0.1). It follows any poll
 * source, given as a configuration's {@link PollSource} or a profile's ({@link #forProfile}): its list call (with the
 * source's paging: cursor, next link in the answer or the {@code Link} header, page number, or none), its download
 * call and its acknowledge call, at their paths and methods, with items placed at the source's pointers.
 *
 * <p>Switches: page size, {@link #empty}, {@link #brokenPage}, {@link #slow}, {@link #busy}, {@link #down},
 * {@link #refuseDownload}, {@link #failAcknowledge}, {@link #duplicateOnNextPage}, {@link #removeOnAcknowledge}, {@link #withoutIds},
 * {@link #expectBearer}. It records the list calls (their query), the downloads and the acknowledges (item IDs only);
 * never a document's content. For tests only.
 */
public final class MockSource implements AutoCloseable {

    public static final String HEALTH_PATH = "/health";
    /** The mock's own query parameter of a next-page address (NEXT_LINK paging). */
    public static final String OFFSET = "offset";

    /** One item: its ID, its document and its values (by the source's value names). */
    public record Item(String id, byte[] content, Map<String, String> values) {
        public Item {
            Objects.requireNonNull(id, "id");
            content = content.clone();
            values = Map.copyOf(values);
        }
    }

    private final PollSource source;
    private final HttpServer server;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mock-source");
        t.setDaemon(true);
        return t;
    });
    private final List<Item> items = new CopyOnWriteArrayList<>();
    private final List<String> listQueries = new CopyOnWriteArrayList<>();
    private final List<String> downloads = new CopyOnWriteArrayList<>();
    private final List<String> acknowledged = new CopyOnWriteArrayList<>();
    private final Map<String, int[]> refusedDownloads = new ConcurrentHashMap<>();
    private final Map<String, String> refusedCodes = new ConcurrentHashMap<>();
    private final Set<String> failingAcks = ConcurrentHashMap.newKeySet();
    private final Set<String> duplicates = ConcurrentHashMap.newKeySet();
    private final Set<Integer> brokenPages = ConcurrentHashMap.newKeySet();
    private final AtomicInteger listCalls = new AtomicInteger();
    private volatile int pageSize;
    private volatile boolean empty;
    private volatile boolean down;
    private volatile boolean allAcksFail;
    private volatile boolean removeOnAck;
    private volatile boolean withoutIds;
    private volatile Duration slow = Duration.ZERO;
    private volatile Duration busy;
    private volatile String bearer;

    private MockSource(PollSource source) {
        this.source = Objects.requireNonNull(source, "source");
        this.pageSize = source.paging().pageSize();
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
    }

    /** A mock of this poll source. */
    public static MockSource start(PollSource source) {
        return new MockSource(source);
    }

    /** A mock of a polling profile's source. */
    public static MockSource forProfile(IntegrationProfile profile) {
        if (profile.poll() == null) throw new IllegalArgumentException("Not a polling profile");
        return new MockSource(profile.poll());
    }

    public URI address() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public int port() {
        return server.getAddress().getPort();
    }

    // ---- Items and switches ----

    public MockSource add(String id, byte[] content) {
        return add(id, content, Map.of());
    }

    public MockSource add(String id, byte[] content, Map<String, String> values) {
        items.add(new Item(id, content, values));
        return this;
    }

    public List<Item> items() {
        return List.copyOf(items);
    }

    /** Items per page (the source's page size by default; the size parameter, when sent, is followed). */
    public MockSource pageSize(int n) {
        pageSize = n;
        return this;
    }

    /** The list is empty. */
    public MockSource empty(boolean on) {
        empty = on;
        return this;
    }

    /** Page {@code n} (0 = the first one served) answers 200 with something that is not the list. */
    public MockSource brokenPage(int n) {
        brokenPages.add(n);
        return this;
    }

    /** Every answer takes this long. */
    public MockSource slow(Duration d) {
        slow = d;
        return this;
    }

    /** 429 with Retry-After on every call (null: off). */
    public MockSource busy(Duration retryAfter) {
        busy = retryAfter;
        return this;
    }

    /** 503 on every call, health included. */
    public MockSource down(boolean on) {
        down = on;
        return this;
    }

    /** The download of {@code id} is refused with {@code status} and the source's {@code code}. */
    public MockSource refuseDownload(String id, int status, String code) {
        refusedDownloads.put(id, new int[]{status});
        refusedCodes.put(id, code);
        return this;
    }

    /** The acknowledge of {@code id} (null: of every item) fails with 500. */
    public MockSource failAcknowledge(String id) {
        if (id == null) allAcksFail = true;
        else failingAcks.add(id);
        return this;
    }

    /** {@code id} appears again at the start of the next page. */
    public MockSource duplicateOnNextPage(String id) {
        duplicates.add(id);
        return this;
    }

    /** An acknowledged item leaves the list (a queue). */
    public MockSource removeOnAcknowledge(boolean on) {
        removeOnAck = on;
        return this;
    }

    /** Items are listed without their ID (at the source's ID pointer). */
    public MockSource withoutIds(boolean on) {
        withoutIds = on;
        return this;
    }

    /** Every call must carry {@code Authorization: Bearer <token>}, else 401. */
    public MockSource expectBearer(String token) {
        bearer = token;
        return this;
    }

    /** Everything back to working (items kept, records cleared). */
    public MockSource working() {
        empty = false;
        down = false;
        allAcksFail = false;
        removeOnAck = false;
        withoutIds = false;
        slow = Duration.ZERO;
        busy = null;
        bearer = null;
        brokenPages.clear();
        refusedDownloads.clear();
        refusedCodes.clear();
        failingAcks.clear();
        duplicates.clear();
        pageSize = source.paging().pageSize();
        clearRecords();
        return this;
    }

    public MockSource clearRecords() {
        listQueries.clear();
        downloads.clear();
        acknowledged.clear();
        listCalls.set(0);
        return this;
    }

    // ---- Records ----

    /** The list calls' query strings (null for none), in order. */
    public List<String> listQueries() {
        return List.copyOf(listQueries);
    }

    public int listCalls() {
        return listCalls.get();
    }

    /** The item IDs downloaded, in order. */
    public List<String> downloads() {
        return List.copyOf(downloads);
    }

    /** The item IDs acknowledged, in order. */
    public List<String> acknowledged() {
        return List.copyOf(acknowledged);
    }

    // ---- Serving ----

    private void handle(HttpExchange x) throws IOException {
        try (x) {
            x.getRequestBody().readAllBytes();
            Duration d = slow;
            if (!d.isZero()) {
                try {
                    Thread.sleep(d.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            String path = x.getRequestURI().getRawPath();
            String method = x.getRequestMethod();
            if (path.equals(HEALTH_PATH)) {
                send(x, down ? 503 : 200, "{\"status\":\"" + (down ? "DOWN" : "UP") + "\"}");
                return;
            }
            if (down) {
                send(x, 503, "{\"code\":\"MOCK-SRC-DOWN\"}");
                return;
            }
            Duration b = busy;
            if (b != null) {
                x.getResponseHeaders().add("Retry-After", Long.toString(Math.max(1, b.toSeconds())));
                send(x, 429, "{\"code\":\"MOCK-SRC-BUSY\"}");
                return;
            }
            String expected = bearer;
            if (expected != null) {
                String auth = x.getRequestHeaders().getFirst("Authorization");
                if (auth == null || !auth.equals("Bearer " + expected)) {
                    send(x, 401, "{\"code\":\"MOCK-SRC-AUTH\"}");
                    return;
                }
            }
            if (matches(source.list(), method, path) != null) {
                list(x);
                return;
            }
            Map<String, String> dl = matches(source.download(), method, path);
            if (dl != null) {
                download(x, dl);
                return;
            }
            Map<String, String> ack = source.acknowledge() == null ? null : matches(source.acknowledge(), method, path);
            if (ack != null) {
                acknowledge(x, ack);
                return;
            }
            send(x, 404, "{\"code\":\"MOCK-SRC-NOT-FOUND\"}");
        }
    }

    private void list(HttpExchange x) throws IOException {
        int call = listCalls.getAndIncrement();
        String rawQuery = x.getRequestURI().getRawQuery();
        listQueries.add(rawQuery);
        Map<String, String> q = query(rawQuery);
        PollSource.Paging g = source.paging();
        int size = pageSize;
        if (g.sizeParam() != null && q.containsKey(g.sizeParam())) {
            try {
                size = Integer.parseInt(q.get(g.sizeParam()));
            } catch (NumberFormatException e) {
                send(x, 400, "{\"code\":\"MOCK-SRC-BAD-SIZE\"}");
                return;
            }
        }
        int offset = switch (g.mode()) {
            case CURSOR -> q.containsKey(g.param()) ? Integer.parseInt(q.get(g.param()).substring(1)) : 0;
            case PAGE_NUMBER -> (Integer.parseInt(q.getOrDefault(g.param(), Integer.toString(g.firstPage())))
                    - g.firstPage()) * size;
            case NEXT_LINK -> Integer.parseInt(q.getOrDefault(OFFSET, "0"));
            case NONE -> 0;
        };
        if (g.mode() == PollSource.Paging.Mode.NONE) size = Integer.MAX_VALUE;
        if (brokenPages.contains(call)) {
            send(x, 200, "this is not the list");
            return;
        }
        List<Item> all = empty ? List.of() : List.copyOf(items);
        List<Item> page = new ArrayList<>();
        // Duplicates from the previous page are served again first.
        for (int i = Math.max(0, offset - size); i < offset && i < all.size(); i++) {
            if (duplicates.contains(all.get(i).id())) page.add(all.get(i));
        }
        for (int i = offset; i < all.size() && i < (long) offset + size; i++) page.add(all.get(i));
        boolean more = (long) offset + size < all.size();
        List<Object> json = new ArrayList<>();
        for (Item it : page) json.add(itemJson(it));
        Object answer;
        if (source.itemsPointer().isEmpty()) {
            answer = json;
        } else {
            Map<String, Object> root = new LinkedHashMap<>();
            put(root, source.itemsPointer(), json);
            int next = offset + size;
            switch (g.mode()) {
                case CURSOR -> {
                    if (more) put(root, g.pointer(), "c" + next);
                }
                case NEXT_LINK -> {
                    if (more) {
                        String link = address() + x.getRequestURI().getRawPath() + "?" + OFFSET + "=" + next;
                        if (g.pointer() != null) put(root, g.pointer(), link);
                        else x.getResponseHeaders().add("Link", "<" + link + ">; rel=\"next\"");
                    }
                }
                default -> {
                }
            }
            answer = root;
        }
        send(x, 200, Json.write(answer));
    }

    private Map<String, Object> itemJson(Item it) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (!withoutIds) put(m, source.idPointer(), it.id());
        else m.put("name", it.id());
        for (Map.Entry<String, String> v : source.values().entrySet()) {
            String value = it.values().get(v.getKey());
            if (value != null) put(m, v.getValue(), value);
        }
        return m;
    }

    private Item find(Map<String, String> placeholders) {
        for (Item it : items) {
            boolean all = true;
            for (Map.Entry<String, String> p : placeholders.entrySet()) {
                String want = p.getKey().equals("item.id") ? it.id()
                        : p.getKey().startsWith("item.") ? it.values().get(p.getKey().substring(5)) : null;
                if (want != null && !want.equals(p.getValue())) all = false;
            }
            if (all) return it;
        }
        return null;
    }

    private void download(HttpExchange x, Map<String, String> placeholders) throws IOException {
        Item it = find(placeholders);
        if (it == null) {
            send(x, 404, "{\"code\":\"MOCK-SRC-NO-ITEM\"}");
            return;
        }
        downloads.add(it.id());
        int[] refused = refusedDownloads.get(it.id());
        if (refused != null) {
            send(x, refused[0], "{\"code\":" + Json.quote(refusedCodes.get(it.id())) + "}");
            return;
        }
        x.getResponseHeaders().add("Content-Type", "application/pdf");
        x.sendResponseHeaders(200, it.content().length == 0 ? -1 : it.content().length);
        try (OutputStream out = x.getResponseBody()) {
            out.write(it.content());
        }
    }

    private void acknowledge(HttpExchange x, Map<String, String> placeholders) throws IOException {
        Item it = find(placeholders);
        if (it == null) {
            send(x, 404, "{\"code\":\"MOCK-SRC-NO-ITEM\"}");
            return;
        }
        if (allAcksFail || failingAcks.contains(it.id())) {
            send(x, 500, "{\"code\":\"MOCK-SRC-ACK-FAILED\"}");
            return;
        }
        acknowledged.add(it.id());
        if (removeOnAck) items.remove(it);
        send(x, 200, "{\"acknowledged\":true}");
    }

    /** The placeholder values when the request is this step's (method and path), else null. */
    private static Map<String, String> matches(CallStep s, String method, String path) {
        if (!s.method().name().equals(method)) return null;
        String template = s.path().contains("?") ? s.path().substring(0, s.path().indexOf('?')) : s.path();
        Matcher ph = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_.:-]*)}").matcher(template);
        StringBuilder rx = new StringBuilder();
        List<String> names = new ArrayList<>();
        int last = 0;
        while (ph.find()) {
            rx.append(Pattern.quote(template.substring(last, ph.start()))).append("([^/]+)");
            names.add(ph.group(1));
            last = ph.end();
        }
        rx.append(Pattern.quote(template.substring(last)));
        Matcher m = Pattern.compile(rx.toString()).matcher(path);
        if (!m.matches()) return null;
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            out.put(names.get(i), URLDecoder.decode(m.group(i + 1).replace("+", "%2B"), StandardCharsets.UTF_8));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> root, String pointer, Object value) {
        String[] parts = pointer.substring(1).split("/", -1);
        Map<String, Object> cur = root;
        for (int i = 0; i < parts.length - 1; i++) {
            String k = parts[i].replace("~1", "/").replace("~0", "~");
            cur = (Map<String, Object>) cur.computeIfAbsent(k, n -> new LinkedHashMap<String, Object>());
        }
        cur.put(parts[parts.length - 1].replace("~1", "/").replace("~0", "~"), value);
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> q = new LinkedHashMap<>();
        if (raw == null) return q;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            q.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return q;
    }

    private static void send(HttpExchange x, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        if (!x.getResponseHeaders().containsKey("Content-Type")) {
            x.getResponseHeaders().add("Content-Type", body.startsWith("{") || body.startsWith("[")
                    ? "application/json" : "text/plain");
        }
        x.sendResponseHeaders(status, b.length);
        try (OutputStream out = x.getResponseBody()) {
            out.write(b);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        pool.shutdownNow();
    }

    @Override
    public String toString() {
        return "MockSource[" + port() + "]";
    }
}
