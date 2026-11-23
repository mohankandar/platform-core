package com.documentplatform.integration.config;

import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.integration.json.internal.Json;

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
 *   <li>{@code {item.id}}, {@code {item.<name>}}: API polling only, in the download and acknowledge steps: the polled
 *       item's source ID and the values read from it</li>
 * </ul>
 * The one list construct, JSON only: {@code [{#each doc}{"id":"{doc.sourceDocumentId}"}{/each}]} writes the part
 * between the markers once per document, separated by commas. It cannot be nested.
 *
 * <p>"Omit when missing", JSON only: a placeholder written {@code {name?}} that is the whole value of an object member
 * ({@code "loan":"{field:loan_number?}"} or {@code "amount":{field:loan_amount?}}) leaves out the whole member, with
 * its comma, when it has no value; otherwise the member is written as usual. Still no expressions or conditions.
 * Anywhere else (inside a longer string, in a list, in a path or header) it is refused with
 * {@code INT_CONFIG_OMIT_PLACE}.
 *
 * <p>Refused when the configuration is saved: an unknown placeholder; any placeholder for a secret, sign-in or a
 * document's content; any placeholder in the query string of a path.
 */
public final class Template {

    /** Where the rendered text goes. */
    /**
     * Where the rendered text goes. {@code NAME}: one file or folder name (folder export); literal text without
     * separators or characters Windows refuses, each value percent-encoded as in a path segment.
     */
    public enum Place { PATH, HEADER, JSON, NAME }

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

        /**
         * Whether the placeholder has a value at all, for "omit when missing" members. A value that is there but
         * cannot be placed (a field that is not a number where one is needed) still counts: it is refused, not left
         * out.
         */
        default boolean has(String name) {
            return text(name).isPresent() || json(name).isPresent();
        }
    }

    /** The template cannot be used; says which placeholder (by name, never a value). */
    public static final class Problem extends Exception implements com.documentplatform.errors.HasPlatformCode {
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

        /** The code, for monitoring and the app's error handler ({@code HasPlatformCode}). */
        @Override
        public com.documentplatform.errors.PlatformCode platformCode() {
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
    /** API polling: {@code {item.id}} and {@code {item.<name>}}, the polled item's ID and values read from it. */
    public static final String ITEM = "item";
    public static final String ITEM_ID = "item.id";

    public static final Set<String> NUMERIC = Set.of("package.documentCount", "doc.size");
    public static final Set<String> FIXED = Set.of("correlationId", "package.id", "package.documentCount",
            "doc.sourceDocumentId", "doc.fileName", "doc.contentType", "doc.sha256", "doc.size");
    private static final Set<String> FORBIDDEN_ROOTS = Set.of("secret", "secrets", "signin", "sign-in", "credential",
            "credentials", "token", "password", "key", "auth", "content", "body", "bytes", "file");
    private static final Set<String> FORBIDDEN_DOC = Set.of("doc.content", "doc.bytes", "doc.body", "doc.text",
            "doc.data", "doc.file");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_.:-]{0,120})(\\?)?}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final Pattern FIELD_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,99}");
    private static final Pattern NUMBER = Pattern.compile("-?\\d{1,18}");
    /** Before an "omit when missing" placeholder: {@code {} or {@code ,}, then {@code "key":} and, in a string, {@code "}. */
    private static final Pattern MEMBER_START = Pattern.compile("(?s)(.*[{,]\\s*)(\"(?:[^\"\\\\]|\\\\.)*\"\\s*:\\s*\"?)");
    /** After it: in a string {@code "}, then {@code ,} or {@code }} (outside a string just the {@code ,} or {@code }}). */
    private static final Pattern MEMBER_END_STRING = Pattern.compile("(?s)(\")(\\s*[,}].*)");
    private static final Pattern MEMBER_END_BARE = Pattern.compile("(?s)()(\\s*[,}].*)");

    private sealed interface Part permits Literal, Hole, Each, OptionalMember {
    }

    private record Literal(String text) implements Part {
    }

    /**
     * @param inString for JSON: whether the placeholder is inside a string literal
     * @param optional marked "omit when missing" ({@code {name?}})
     */
    private record Hole(String name, boolean inString, boolean optional) implements Part {
        Hole(String name, boolean inString) {
            this(name, inString, false);
        }
    }

    private record Each(List<Part> inner) implements Part {
    }

    /**
     * A whole JSON object member whose value is one "omit when missing" placeholder: {@code prefix} is
     * {@code "key":} (plus {@code "} in a string), {@code suffix} the closing {@code "} or nothing. Left out, with
     * one neighbouring comma, when the placeholder has no value.
     */
    private record OptionalMember(String prefix, Hole hole, String suffix) implements Part {
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
            case JSON -> parts = optionalMembers(jsonParts(parts, false));
            case NAME -> {
                if (parts.isEmpty()) throw new Problem(IntegrationCode.INT_CONFIG_FOLDER_INVALID, null);
                for (Part p : parts) {
                    if (p instanceof Literal l && (l.text().contains("{") || l.text().contains("}")
                            || l.text().chars().anyMatch(c -> c < 0x20 || c == 0x7f || "/\\:*?\"<>|".indexOf(c) >= 0))) {
                        throw new Problem(IntegrationCode.INT_CONFIG_FOLDER_INVALID, null);
                    }
                }
            }
        }
        if (place != Place.JSON) {
            for (Part p : parts) {
                if (p instanceof Hole h && h.optional()) throw new Problem(IntegrationCode.INT_CONFIG_OMIT_PLACE, h.name());
            }
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
            parts.add(new Hole(name, false, m.group(2) != null));
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
        if (FIXED.contains(name) || name.equals(CallStep.Accepted.REFERENCE)) return;
        String[] seg = name.split("\\.", -1);
        if (seg.length == 2 && seg[0].equals("value") && NAME.matcher(seg[1]).matches()) return;
        // API polling: the polled item's ID and the values read from it (download and acknowledge steps only).
        if (seg.length == 2 && seg[0].equals(ITEM) && NAME.matcher(seg[1]).matches()) return;
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
                out.add(new Hole(h.name(), inString, h.optional()));
            } else {
                Each e = (Each) p;
                if (inString || nested) throw new Problem(IntegrationCode.INT_CONFIG_TEMPLATE_INVALID, null);
                out.add(new Each(jsonParts(e.inner(), true)));
            }
        }
        if (inString) throw new Problem(IntegrationCode.INT_CONFIG_TEMPLATE_INVALID, null);
        return out;
    }

    /**
     * Turns each "omit when missing" placeholder into an {@link OptionalMember}: it must be the whole value of an
     * object member ({@code ,"key":"{x?}"} or {@code {"key":{field:k?}}}), else {@code INT_CONFIG_OMIT_PLACE}.
     */
    private static List<Part> optionalMembers(List<Part> parts) throws Problem {
        List<Part> in = new ArrayList<>(parts);
        List<Part> out = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            Part p = in.get(i);
            if (p instanceof Each e) {
                out.add(new Each(optionalMembers(e.inner())));
                continue;
            }
            if (!(p instanceof Hole h) || !h.optional()) {
                out.add(p);
                continue;
            }
            Part before = out.isEmpty() ? null : out.get(out.size() - 1);
            Part after = i + 1 < in.size() ? in.get(i + 1) : null;
            if (!(before instanceof Literal b) || !(after instanceof Literal a)) {
                throw new Problem(IntegrationCode.INT_CONFIG_OMIT_PLACE, h.name());
            }
            Matcher start = MEMBER_START.matcher(b.text());
            Matcher end = (h.inString() ? MEMBER_END_STRING : MEMBER_END_BARE).matcher(a.text());
            if (!start.matches() || !end.matches()) {
                throw new Problem(IntegrationCode.INT_CONFIG_OMIT_PLACE, h.name());
            }
            String prefix = start.group(2);
            // In a string the prefix ends with the value's opening quote; outside one it ends with the colon.
            boolean opensString = prefix.replaceAll("\\s", "").endsWith(":\"");
            if (opensString != h.inString()) throw new Problem(IntegrationCode.INT_CONFIG_OMIT_PLACE, h.name());
            out.set(out.size() - 1, new Literal(start.group(1)));
            out.add(new OptionalMember(prefix, h, end.group(1)));
            in.set(i + 1, new Literal(end.group(2)));
        }
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
            else if (p instanceof OptionalMember o) into.add(o.hole().name());
            else if (p instanceof Each e && inEach) collect(e.inner(), into, true);
        }
    }

    /** The placeholders marked "omit when missing" ({@code {name?}}), inside an each-block too. */
    public Set<String> optionalPlaceholders() {
        Set<String> names = new LinkedHashSet<>();
        collectOptional(parts, names);
        return names;
    }

    private static void collectOptional(List<Part> parts, Set<String> into) {
        for (Part p : parts) {
            if (p instanceof OptionalMember o) into.add(o.hole().name());
            else if (p instanceof Each e) collectOptional(e.inner(), into);
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
        render(parts, values, b, new boolean[1]);
        return b.toString();
    }

    /** @param dropComma set when a left-out member was the first of its object: the next comma goes too */
    private void render(List<Part> list, Values values, StringBuilder b, boolean[] dropComma) throws Problem {
        for (Part p : list) {
            if (p instanceof Literal l) {
                String t = l.text();
                if (dropComma[0]) {
                    String rest = t.stripLeading();
                    if (rest.startsWith(",")) t = rest.substring(1);
                    if (!t.isBlank() || rest.startsWith(",")) dropComma[0] = false;
                }
                b.append(t);
                continue;
            }
            if (p instanceof Each e) {
                boolean first = true;
                for (Values doc : values.documents()) {
                    if (!first) b.append(',');
                    first = false;
                    render(e.inner(), doc, b, dropComma);
                }
                continue;
            }
            if (p instanceof OptionalMember o) {
                if (values.has(o.hole().name())) {
                    b.append(o.prefix());
                    render(List.of(o.hole()), values, b, dropComma);
                    b.append(o.suffix());
                    continue;
                }
                // Left out: with the comma before it, or (first member) the one after it.
                int end = b.length();
                while (end > 0 && Character.isWhitespace(b.charAt(end - 1))) end--;
                if (end > 0 && b.charAt(end - 1) == ',') {
                    b.setLength(end - 1);
                } else {
                    dropComma[0] = true;
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
                case PATH, NAME -> {
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
