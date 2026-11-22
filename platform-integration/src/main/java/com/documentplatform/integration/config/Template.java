package com.documentplatform.integration.config;

import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.integration.json.Json;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A template built from fixed building blocks only: literal text and {@code {placeholder}}s, plus one list construct
 * in JSON. No expressions, conditions or scripts. Values are escaped for the place they go: a path segment is
 * percent-encoded, a header value must have no control characters, a JSON value is escaped inside its string (or,
 * outside a string, must be a number or a typed field value).
 *
 * <p>Placeholders:
 * <ul>
 *   <li>{@code {correlationId}}</li>
 *   <li>{@code {package.id}}, {@code {package.documentCount}} (a number)</li>
 *   <li>{@code {doc.sourceDocumentId}}, {@code {doc.fileName}}, {@code {doc.contentType}}, {@code {doc.sha256}},
 *       {@code {doc.size}} (a number), {@code {doc.attr.<name>}} (a value the app gives per document): only in steps
 *       run once per document, in a multipart part's file name, or inside {@code {#each doc}}</li>
 *   <li>{@code {value.<name>}}: a plain value the app supplies with the delivery</li>
 *   <li>{@code {field:<key>}}: a case field's value by its platform-fields key, from the delivery's field values.
 *       In JSON outside a string it is written by its type (number, text, address object, list for a multi-value
 *       field); inside a string, in a header or a path, as its text</li>
 *   <li>{@code {step.<step>.<value>}}: a value read from an earlier step's answer</li>
 * </ul>
 * The one list construct, JSON only: {@code [{#each doc}{"id":"{doc.sourceDocumentId}"}{/each}]} writes the part
 * between the markers once per document, separated by commas. It cannot be nested.
 *
 * <p>Refused when the configuration is saved: an unknown placeholder; any placeholder for a secret, sign-in or a
 * document's content; any placeholder in the query string of a path.
 */
public final class Template {

    /** Where the rendered text goes. */
    public enum Place { PATH, HEADER, JSON }

    /** The values a template is filled with. */
    public interface Values {
        /** A placeholder's text, empty when not supplied. */
        Optional<String> text(String name);

        /** A placeholder's JSON literal (typed field values), empty when not supplied. */
        default Optional<String> json(String name) {
            return Optional.empty();
        }

        /** The values of each document, for {@code {#each doc}}. */
        default List<Values> documents() {
            return List.of();
        }
    }

    /** The template cannot be used; says which placeholder (by name, never a value). */
    public static final class Problem extends Exception {
        private final IntegrationCode code;
        private final String placeholder;

        public Problem(IntegrationCode code, String placeholder) {
            super(code + (placeholder == null ? "" : " {" + placeholder + "}"), null, false, false);
            this.code = code;
            this.placeholder = placeholder;
        }

        public IntegrationCode code() {
            return code;
        }

        /** The placeholder's name, or null. */
        public String placeholder() {
            return placeholder;
        }
    }

    public static final String EACH_START = "{#each doc}";
    public static final String EACH_END = "{/each}";
    public static final String FIELD_PREFIX = "field:";
    public static final String ATTR_PREFIX = "doc.attr.";

    public static final Set<String> NUMERIC = Set.of("package.documentCount", "doc.size");
    public static final Set<String> FIXED = Set.of("correlationId", "package.id", "package.documentCount",
            "doc.sourceDocumentId", "doc.fileName", "doc.contentType", "doc.sha256", "doc.size");
    private static final Set<String> FORBIDDEN_ROOTS = Set.of("secret", "secrets", "signin", "sign-in", "credential",
            "credentials", "token", "password", "key", "auth", "content", "body", "bytes", "file");
    private static final Set<String> FORBIDDEN_DOC = Set.of("doc.content", "doc.bytes", "doc.body", "doc.text",
            "doc.data", "doc.file");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_.:-]{0,120})}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final Pattern FIELD_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,99}");
    private static final Pattern NUMBER = Pattern.compile("-?\\d{1,18}");

    private sealed interface Part permits Literal, Hole, Each {
    }

    private record Literal(String text) implements Part {
    }

    /** @param inString for JSON: whether the placeholder is inside a string literal */
    private record Hole(String name, boolean inString) implements Part {
    }

    private record Each(List<Part> inner) implements Part {
    }

    private final String text;
    private final Place place;
    private final List<Part> parts;

    private Template(String text, Place place, List<Part> parts) {
        this.text = text;
        this.place = place;
        this.parts = parts;
    }

    /** Parses and checks a template for its place. */
    public static Template parse(String text, Place place) throws Problem {
        if (text == null || text.length() > 64 * 1024) throw new Problem(IntegrationCode.INT_CONFIG_TEMPLATE_INVALID, null);
        List<Part> parts;
        if (place == Place.JSON && text.contains(EACH_START)) {
            parts = eachParts(text);
        } else {
            if (text.contains(EACH_START) || text.contains(EACH_END)) {
                throw new Problem(IntegrationCode.INT_CONFIG_TEMPLATE_INVALID, null);
            }
            parts = holes(text);
        }
        switch (place) {
            case PATH -> checkPath(text, parts);
            case HEADER -> {
                for (Part p : parts) {
                    if (p instanceof Literal l && l.text().chars().anyMatch(c -> c < 0x20 && c != '\t' || c == 0x7f)) {
                        throw new Problem(IntegrationCode.INT_CONFIG_TEMPLATE_INVALID, null);
                    }
                }
            }
            case JSON -> parts = jsonParts(parts, false);
        }
        return new Template(text, place, List.copyOf(parts));
    }

    private static List<Part> holes(String text) throws Problem {
        List<Part> parts = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(text);
        int at = 0;
        while (m.find()) {
            if (m.start() > at) parts.add(new Literal(text.substring(at, m.start())));
            String name = m.group(1);
            checkName(name);
            parts.add(new Hole(name, false));
            at = m.end();
        }
        if (at < text.length()) parts.add(new Literal(text.substring(at)));
        return parts;
    }

    /** One {@code {#each doc}}…{@code {/each}} block, at most once, not nested. */
    private static List<Part> eachParts(String text) throws Problem {
        int start = text.indexOf(EACH_START);
        int end = text.indexOf(EACH_END, start);
        if (end < 0 || text.indexOf(EACH_START, start + 1) >= 0 || text.indexOf(EACH_END, end + 1) >= 0
                || text.substring(0, start).contains(EACH_END)) {
            throw new Problem(IntegrationCode.INT_CONFIG_TEMPLATE_INVALID, null);
        }
        String inner = text.substring(start + EACH_START.length(), end);
        if (inner.isBlank()) throw new Problem(IntegrationCode.INT_CONFIG_TEMPLATE_INVALID, null);
        List<Part> parts = new ArrayList<>(holes(text.substring(0, start)));
        parts.add(new Each(holes(inner)));
        parts.addAll(holes(text.substring(end + EACH_END.length())));
        return parts;
    }

    /** Whether a placeholder name is a known building block (and not a forbidden one). */
    private static void checkName(String name) throws Problem {
        if (name.startsWith(FIELD_PREFIX)) {
            if (FIELD_KEY.matcher(name.substring(FIELD_PREFIX.length())).matches()) return;
            throw new Problem(IntegrationCode.INT_CONFIG_PLACEHOLDER_UNKNOWN, name);
        }
        if (name.contains(":")) throw new Problem(IntegrationCode.INT_CONFIG_PLACEHOLDER_UNKNOWN, name);
        String root = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        if (FORBIDDEN_ROOTS.contains(root.toLowerCase()) || FORBIDDEN_DOC.contains(name)) {
            throw new Problem(IntegrationCode.INT_CONFIG_PLACEHOLDER_FORBIDDEN, name);
        }
        if (FIXED.contains(name)) return;
        String[] seg = name.split("\\.", -1);
        if (seg.length == 2 && seg[0].equals("value") && NAME.matcher(seg[1]).matches()) return;
        if (seg.length == 3 && seg[0].equals("doc") && seg[1].equals("attr") && NAME.matcher(seg[2]).matches()) {
            return;
        }
        if (seg.length == 3 && seg[0].equals("step") && NAME.matcher(seg[1]).matches()
                && NAME.matcher(seg[2]).matches()) {
            return;
        }
        if (seg[0].equals("doc") || seg[0].equals("package") || seg[0].equals("value") || seg[0].equals("step")) {
            String last = seg[seg.length - 1].toLowerCase();
            if (FORBIDDEN_ROOTS.contains(last) || last.equals("text") || last.equals("data")) {
                throw new Problem(IntegrationCode.INT_CONFIG_PLACEHOLDER_FORBIDDEN, name);
            }
        }
        throw new Problem(IntegrationCode.INT_CONFIG_PLACEHOLDER_UNKNOWN, name);
    }

    private static void checkPath(String text, List<Part> parts) throws Problem {
        if (!text.startsWith("/") || text.startsWith("//") || text.contains("\\") || text.contains("#")
                || text.chars().anyMatch(c -> c <= 0x20 || c == 0x7f)) {
            throw new Problem(IntegrationCode.INT_CONFIG_TEMPLATE_INVALID, null);
        }
        boolean query = false;
        for (Part p : parts) {
            if (p instanceof Literal l) {
                String lit = l.text();
                if (lit.contains("{") || lit.contains("}")) throw new Problem(IntegrationCode.INT_CONFIG_TEMPLATE_INVALID, null);
                String pathPart = query ? "" : (lit.contains("?") ? lit.substring(0, lit.indexOf('?')) : lit);
                for (String seg : pathPart.split("/", -1)) {
                    if (seg.equals("..") || seg.equals(".")) throw new Problem(IntegrationCode.INT_CONFIG_TEMPLATE_INVALID, null);
                }
                if (lit.contains("?")) query = true;
            } else if (p instanceof Hole h && query) {
                throw new Problem(IntegrationCode.INT_CONFIG_PLACEHOLDER_IN_QUERY, h.name());
            }
        }
    }

    /**
     * Marks each placeholder as inside or outside a JSON string; outside, only numbers and field values may go. An
     * each-block must start and end outside a string.
     */
    private static List<Part> jsonParts(List<Part> parts, boolean nested) throws Problem {
        List<Part> out = new ArrayList<>();
        boolean inString = false;
        for (Part p : parts) {
            if (p instanceof Literal l) {
                String s = l.text();
                for (int k = 0; k < s.length(); k++) {
                    char c = s.charAt(k);
                    if (inString && c == '\\') {
                        k++;
                    } else if (c == '"') {
                        inString = !inString;
                    }
                }
                out.add(l);
            } else if (p instanceof Hole h) {
                if (!inString && !NUMERIC.contains(h.name()) && !h.name().startsWith(FIELD_PREFIX)) {
                    throw new Problem(IntegrationCode.INT_CONFIG_PLACEHOLDER_PLACE, h.name());
                }
                out.add(new Hole(h.name(), inString));
            } else {
                Each e = (Each) p;
                if (inString || nested) throw new Problem(IntegrationCode.INT_CONFIG_TEMPLATE_INVALID, null);
                out.add(new Each(jsonParts(e.inner(), true)));
            }
        }
        if (inString) throw new Problem(IntegrationCode.INT_CONFIG_TEMPLATE_INVALID, null);
        return out;
    }

    public String text() {
        return text;
    }

    public Place place() {
        return place;
    }

    /** Every placeholder name, in order of first use (inside an each-block too). */
    public Set<String> placeholders() {
        Set<String> names = new LinkedHashSet<>();
        collect(parts, names, true);
        return names;
    }

    /** The placeholder names outside the each-block (the ones a step's own document context must supply). */
    public Set<String> placeholdersOutsideEach() {
        Set<String> names = new LinkedHashSet<>();
        collect(parts, names, false);
        return names;
    }

    /** Whether the template has the each-block. */
    public boolean hasEach() {
        return parts.stream().anyMatch(p -> p instanceof Each);
    }

    private static void collect(List<Part> parts, Set<String> into, boolean inEach) {
        for (Part p : parts) {
            if (p instanceof Hole h) into.add(h.name());
            else if (p instanceof Each e && inEach) collect(e.inner(), into, true);
        }
    }

    /** Fills in plain text values (no field values, no each-block). */
    public String render(Function<String, Optional<String>> values) throws Problem {
        return fill(values::apply);
    }

    /**
     * Fills in the values.
     *
     * @throws Problem {@code INT_VALUE_MISSING} or {@code INT_VALUE_NOT_ALLOWED}, naming the placeholder
     */
    public String fill(Values values) throws Problem {
        StringBuilder b = new StringBuilder();
        render(parts, values, b);
        return b.toString();
    }

    private void render(List<Part> list, Values values, StringBuilder b) throws Problem {
        for (Part p : list) {
            if (p instanceof Literal l) {
                b.append(l.text());
                continue;
            }
            if (p instanceof Each e) {
                boolean first = true;
                for (Values doc : values.documents()) {
                    if (!first) b.append(',');
                    first = false;
                    render(e.inner(), doc, b);
                }
                continue;
            }
            Hole h = (Hole) p;
            if (place == Place.JSON && !h.inString() && h.name().startsWith(FIELD_PREFIX)) {
                b.append(values.json(h.name()).orElseThrow(() -> new Problem(IntegrationCode.INT_VALUE_MISSING,
                        h.name())));
                continue;
            }
            String v = values.text(h.name()).orElseThrow(() -> new Problem(IntegrationCode.INT_VALUE_MISSING, h.name()));
            switch (place) {
                case PATH -> {
                    if (v.isEmpty()) throw new Problem(IntegrationCode.INT_VALUE_NOT_ALLOWED, h.name());
                    b.append(encodeSegment(v));
                }
                case HEADER -> {
                    if (v.chars().anyMatch(c -> c < 0x20 && c != '\t' || c == 0x7f)) {
                        throw new Problem(IntegrationCode.INT_VALUE_NOT_ALLOWED, h.name());
                    }
                    b.append(v);
                }
                case JSON -> {
                    if (h.inString()) {
                        b.append(Json.escape(v));
                    } else {
                        if (!NUMBER.matcher(v).matches()) throw new Problem(IntegrationCode.INT_VALUE_NOT_ALLOWED, h.name());
                        b.append(v);
                    }
                }
            }
        }
    }

    /** Percent-encodes everything but RFC 3986 unreserved characters, so a value stays one path segment. */
    static String encodeSegment(String v) {
        StringBuilder b = new StringBuilder();
        for (byte x : v.getBytes(StandardCharsets.UTF_8)) {
            int c = x & 0xff;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_'
                    || c == '~' || (c == '.' && !v.equals(".") && !v.equals(".."))) {
                b.append((char) c);
            } else {
                b.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
            }
        }
        return b.toString();
    }

    @Override
    public String toString() {
        return text;
    }
}
