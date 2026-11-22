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
 * A template built from fixed building blocks only: literal text and {@code {placeholder}}s. No expressions, loops,
 * conditions or scripts. Values are escaped for the place they go: a path segment is percent-encoded, a header value
 * must have no control characters, a JSON value is escaped inside its string (or, outside a string, must be a number).
 *
 * <p>Placeholders:
 * <ul>
 *   <li>{@code {correlationId}}</li>
 *   <li>{@code {package.id}}, {@code {package.documentCount}} (a number)</li>
 *   <li>{@code {doc.sourceDocumentId}}, {@code {doc.fileName}}, {@code {doc.contentType}}, {@code {doc.sha256}},
 *       {@code {doc.size}} (a number): only in steps run once per document</li>
 *   <li>{@code {value.<name>}}: a plain value the app supplies with the delivery</li>
 *   <li>{@code {step.<step>.<value>}}: a value read from an earlier step's answer</li>
 * </ul>
 * Refused when the configuration is saved: an unknown placeholder; any placeholder for a secret, sign-in or a
 * document's content; any placeholder in the query string of a path.
 */
public final class Template {

    /** Where the rendered text goes. */
    public enum Place { PATH, HEADER, JSON }

    /** The template cannot be used; says which placeholder (by name, never a value). */
    public static final class Problem extends Exception {
        private final IntegrationCode code;
        private final String placeholder;

        Problem(IntegrationCode code, String placeholder) {
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

    public static final Set<String> NUMERIC = Set.of("package.documentCount", "doc.size");
    public static final Set<String> FIXED = Set.of("correlationId", "package.id", "package.documentCount",
            "doc.sourceDocumentId", "doc.fileName", "doc.contentType", "doc.sha256", "doc.size");
    private static final Set<String> FORBIDDEN_ROOTS = Set.of("secret", "secrets", "signin", "sign-in", "credential",
            "credentials", "token", "password", "key", "auth", "content", "body", "bytes", "file");
    private static final Set<String> FORBIDDEN_DOC = Set.of("doc.content", "doc.bytes", "doc.body", "doc.text",
            "doc.data", "doc.file");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_.-]{0,120})}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final Pattern NUMBER = Pattern.compile("-?\\d{1,18}");

    private sealed interface Part permits Literal, Hole {
    }

    private record Literal(String text) implements Part {
    }

    /** @param inString for JSON: whether the placeholder is inside a string literal */
    private record Hole(String name, boolean inString) implements Part {
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

        switch (place) {
            case PATH -> checkPath(text, parts);
            case HEADER -> {
                for (Part p : parts) {
                    if (p instanceof Literal l && l.text().chars().anyMatch(c -> c < 0x20 && c != '\t' || c == 0x7f)) {
                        throw new Problem(IntegrationCode.INT_CONFIG_TEMPLATE_INVALID, null);
                    }
                }
            }
            case JSON -> parts = jsonParts(parts);
        }
        return new Template(text, place, List.copyOf(parts));
    }

    /** Whether a placeholder name is a known building block (and not a forbidden one). */
    private static void checkName(String name) throws Problem {
        String root = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        if (FORBIDDEN_ROOTS.contains(root.toLowerCase()) || FORBIDDEN_DOC.contains(name)) {
            throw new Problem(IntegrationCode.INT_CONFIG_PLACEHOLDER_FORBIDDEN, name);
        }
        if (FIXED.contains(name)) return;
        String[] seg = name.split("\\.", -1);
        if (seg.length == 2 && seg[0].equals("value") && NAME.matcher(seg[1]).matches()) return;
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

    /** Marks each placeholder as inside or outside a JSON string; outside, only numbers may go. */
    private static List<Part> jsonParts(List<Part> parts) throws Problem {
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
            } else {
                Hole h = (Hole) p;
                if (!inString && !NUMERIC.contains(h.name())) {
                    throw new Problem(IntegrationCode.INT_CONFIG_PLACEHOLDER_PLACE, h.name());
                }
                out.add(new Hole(h.name(), inString));
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

    /** The placeholder names, in order of first use. */
    public Set<String> placeholders() {
        Set<String> names = new LinkedHashSet<>();
        for (Part p : parts) {
            if (p instanceof Hole h) names.add(h.name());
        }
        return names;
    }

    /**
     * Fills in the values.
     *
     * @param values a placeholder's name → its value, empty when not supplied
     * @throws Problem {@code INT_VALUE_MISSING} or {@code INT_VALUE_NOT_ALLOWED}, naming the placeholder
     */
    public String render(Function<String, Optional<String>> values) throws Problem {
        StringBuilder b = new StringBuilder();
        for (Part p : parts) {
            if (p instanceof Literal l) {
                b.append(l.text());
                continue;
            }
            Hole h = (Hole) p;
            String v = values.apply(h.name()).orElseThrow(() -> new Problem(IntegrationCode.INT_VALUE_MISSING, h.name()));
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
        return b.toString();
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
