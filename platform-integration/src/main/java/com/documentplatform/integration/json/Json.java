package com.documentplatform.integration.json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A small, strict JSON reader and writer: objects ({@code Map}, keys in order), arrays ({@code List}), strings, numbers
 * ({@code Long} or {@code Double}), booleans and null (Java {@code null}). Used for destination answers and the stored
 * configuration. A failure says only that the text is not valid JSON, never what it contained (an answer may hold a
 * token or a value).
 */
public final class Json {

    /** The text is not valid JSON. Carries no part of the text. */
    public static final class Invalid extends Exception {
        public Invalid() {
            super("not valid JSON", null, false, false);
        }
    }

    private static final int MAX_DEPTH = 32;

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    /** Any JSON value. */
    public static Object parse(String text) throws Invalid {
        if (text == null) throw new Invalid();
        Json p = new Json(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != text.length()) throw new Invalid();
        return v;
    }

    /** A JSON object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(String text) throws Invalid {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new Invalid();
        return (Map<String, Object>) v;
    }

    /**
     * The value at an RFC 6901 JSON pointer ({@code /a/0/b}; {@code ""} is the whole value). Empty if a step is
     * missing, or the value there is JSON null.
     */
    public static Optional<Object> at(Object root, String pointer) {
        if (pointer == null) return Optional.empty();
        if (pointer.isEmpty()) return Optional.ofNullable(root);
        if (!pointer.startsWith("/")) return Optional.empty();
        Object cur = root;
        for (String raw : pointer.substring(1).split("/", -1)) {
            String part = raw.replace("~1", "/").replace("~0", "~");
            if (cur instanceof Map<?, ?> m) {
                if (!m.containsKey(part)) return Optional.empty();
                cur = m.get(part);
            } else if (cur instanceof List<?> l) {
                if (!part.matches("0|[1-9]\\d{0,8}")) return Optional.empty();
                int n = Integer.parseInt(part);
                if (n >= l.size()) return Optional.empty();
                cur = l.get(n);
            } else {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(cur);
    }

    /** True for a valid RFC 6901 pointer that is not empty. */
    public static boolean isPointer(String pointer) {
        return pointer != null && pointer.matches("(/([^~/]|~[01])*)+") && pointer.length() <= 200;
    }

    // ---- Writing ----

    /** Compact JSON for maps, lists, strings, numbers, booleans and null. */
    public static String write(Object value) {
        StringBuilder b = new StringBuilder();
        write(b, value);
        return b.toString();
    }

    /** A JSON string literal, with quotes. */
    public static String quote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        escapeInto(b, s);
        return b.append('"').toString();
    }

    /** The JSON string escaping of {@code s}, without quotes. */
    public static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        escapeInto(b, s);
        return b.toString();
    }

    private static void write(StringBuilder b, Object v) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String str) {
            b.append(quote(str));
        } else if (v instanceof Boolean || v instanceof Long || v instanceof Integer) {
            b.append(v);
        } else if (v instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException("not a JSON number");
            b.append(n);
        } else if (v instanceof Map<?, ?> m) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) b.append(',');
                first = false;
                b.append(quote(String.valueOf(e.getKey()))).append(':');
                write(b, e.getValue());
            }
            b.append('}');
        } else if (v instanceof List<?> l) {
            b.append('[');
            for (int k = 0; k < l.size(); k++) {
                if (k > 0) b.append(',');
                write(b, l.get(k));
            }
            b.append(']');
        } else {
            throw new IllegalArgumentException("not a JSON value: " + v.getClass().getSimpleName());
        }
    }

    private static void escapeInto(StringBuilder b, String s) {
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
    }

    // ---- Reading ----

    private Object value(int depth) throws Invalid {
        if (depth > MAX_DEPTH || i >= s.length()) throw new Invalid();
        char c = s.charAt(i);
        switch (c) {
            case '{' -> {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (peek('}')) return m;
                do {
                    ws();
                    if (i >= s.length() || s.charAt(i) != '"') throw new Invalid();
                    String key = string();
                    ws();
                    expect(':');
                    ws();
                    if (m.containsKey(key)) throw new Invalid(); // a repeated key is refused, not silently merged
                    m.put(key, value(depth + 1));
                    ws();
                } while (peek(','));
                expect('}');
                return Collections.unmodifiableMap(m);
            }
            case '[' -> {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (peek(']')) return l;
                do {
                    ws();
                    l.add(value(depth + 1));
                    ws();
                } while (peek(','));
                expect(']');
                return Collections.unmodifiableList(l);
            }
            case '"' -> {
                return string();
            }
            case 't' -> {
                word("true");
                return Boolean.TRUE;
            }
            case 'f' -> {
                word("false");
                return Boolean.FALSE;
            }
            case 'n' -> {
                word("null");
                return null;
            }
            default -> {
                return number();
            }
        }
    }

    private String string() throws Invalid {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c < 0x20) throw new Invalid();
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (i >= s.length()) throw new Invalid();
            char e = s.charAt(i++);
            switch (e) {
                case '"', '\\', '/' -> b.append(e);
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'u' -> {
                    if (i + 4 > s.length()) throw new Invalid();
                    try {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException x) {
                        throw new Invalid();
                    }
                    i += 4;
                }
                default -> throw new Invalid();
            }
        }
        throw new Invalid();
    }

    private Object number() throws Invalid {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String n = s.substring(start, i);
        if (!n.matches("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?")) throw new Invalid();
        try {
            return n.matches("-?\\d{1,18}") ? (Object) Long.parseLong(n) : (Object) Double.parseDouble(n);
        } catch (NumberFormatException x) {
            throw new Invalid();
        }
    }

    private void word(String w) throws Invalid {
        if (!s.startsWith(w, i)) throw new Invalid();
        i += w.length();
    }

    private boolean peek(char c) {
        if (i < s.length() && s.charAt(i) == c) {
            i++;
            return true;
        }
        return false;
    }

    private void expect(char c) throws Invalid {
        if (!peek(c)) throw new Invalid();
    }

    private void ws() {
        while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++;
    }
}
