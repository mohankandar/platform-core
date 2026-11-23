package com.documentplatform.licensing.json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The JSON used by licence files, key files and usage exports. A strict subset of JSON, so that every value has
 * exactly one canonical form:
 * <ul>
 *   <li>objects ({@code Map<String, Object>}), arrays ({@code List<Object>}), strings, integers ({@code Long}) and
 *       booleans;</li>
 *   <li>no {@code null} (a field is either present or absent), no fractions or exponents, no leading zeros, no
 *       duplicate keys, nesting at most 32 deep.</li>
 * </ul>
 * The canonical form ({@link #canonical}) follows RFC 8785 (JCS) for this subset: keys sorted by UTF-16 code units,
 * no whitespace, strings escaped only where JSON requires it ({@code \" \\ \b \f \n \r \t}, other control characters
 * as a six-character backslash-u escape with lower-case hex), everything else written as UTF-8. Any implementation (for example the Platform Console) that
 * follows these rules produces the same bytes.
 *
 * <p><b>Not for apps.</b> The strict JSON of the licence files, shared with the setup command (its input files) and
 * the licence-issuing side; apps read licences through {@code LicenceService}.
 */
public final class Json {

    private static final int MAX_DEPTH = 32;

    private Json() {
    }

    /** Parses one JSON value. The message of a failure gives a position only, never the content. */
    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.ws();
        Object value = p.value(0);
        p.ws();
        if (p.pos != text.length()) {
            throw p.fail("unexpected content after the end of the document");
        }
        return value;
    }

    /** Parses a document whose top level must be an object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new JsonException("the document is not a JSON object");
        }
        return (Map<String, Object>) value;
    }

    /** The canonical form: sorted keys, no whitespace. */
    public static String canonical(Object value) {
        StringBuilder b = new StringBuilder();
        write(b, value, -1, 0);
        return b.toString();
    }

    /** The same content as {@link #canonical}, indented by two spaces for people to read; ends with a line feed. */
    public static String pretty(Object value) {
        StringBuilder b = new StringBuilder();
        write(b, value, 2, 0);
        return b.append('\n').toString();
    }

    private static void write(StringBuilder b, Object value, int indent, int level) {
        if (value instanceof String s) {
            string(b, s);
        } else if (value instanceof Long || value instanceof Integer) {
            b.append(((Number) value).longValue());
        } else if (value instanceof Boolean bool) {
            b.append(bool);
        } else if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String key)) {
                    throw new JsonException("object keys must be strings");
                }
                sorted.put(key, e.getValue());
            }
            if (sorted.isEmpty()) {
                b.append("{}");
                return;
            }
            b.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                newline(b, indent, level + 1);
                string(b, e.getKey());
                b.append(indent < 0 ? ":" : ": ");
                write(b, e.getValue(), indent, level + 1);
            }
            newline(b, indent, level);
            b.append('}');
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                b.append("[]");
                return;
            }
            b.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    b.append(',');
                }
                newline(b, indent, level + 1);
                write(b, list.get(i), indent, level + 1);
            }
            newline(b, indent, level);
            b.append(']');
        } else if (value == null) {
            throw new JsonException("null is not used in this format");
        } else {
            throw new JsonException("not a JSON value: " + value.getClass().getSimpleName());
        }
    }

    private static void newline(StringBuilder b, int indent, int level) {
        if (indent >= 0) {
            b.append('\n');
            b.append(" ".repeat(indent * level));
        }
    }

    private static void string(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        JsonException fail(String what) {
            return new JsonException(what + " at character " + (pos + 1));
        }

        void ws() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw fail("nesting too deep");
            }
            if (pos >= s.length()) {
                throw fail("unexpected end of the document");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> throw fail("null is not used in this format");
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield number();
                    }
                    throw fail("unexpected character");
                }
            };
        }

        Object literal(String word, Object value) {
            if (!s.startsWith(word, pos)) {
                throw fail("unexpected character");
            }
            pos += word.length();
            return value;
        }

        Map<String, Object> object(int depth) {
            pos++; // {
            Map<String, Object> map = new TreeMap<>();
            ws();
            if (peek() == '}') {
                pos++;
                return Collections.unmodifiableMap(map);
            }
            while (true) {
                ws();
                if (peek() != '"') {
                    throw fail("expected a key");
                }
                int keyAt = pos;
                String key = string();
                ws();
                expect(':');
                ws();
                Object value = value(depth + 1);
                if (map.put(key, value) != null) {
                    pos = keyAt;
                    throw fail("duplicate key");
                }
                ws();
                char c = peek();
                pos++;
                if (c == '}') {
                    return Collections.unmodifiableMap(map);
                }
                if (c != ',') {
                    pos--;
                    throw fail("expected , or }");
                }
            }
        }

        List<Object> array(int depth) {
            pos++; // [
            List<Object> list = new ArrayList<>();
            ws();
            if (peek() == ']') {
                pos++;
                return Collections.unmodifiableList(list);
            }
            while (true) {
                ws();
                list.add(value(depth + 1));
                ws();
                char c = peek();
                pos++;
                if (c == ']') {
                    return Collections.unmodifiableList(list);
                }
                if (c != ',') {
                    pos--;
                    throw fail("expected , or ]");
                }
            }
        }

        String string() {
            pos++; // "
            StringBuilder b = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw fail("unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return b.toString();
                }
                if (c < 0x20) {
                    pos--;
                    throw fail("control character in a string");
                }
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                if (pos >= s.length()) {
                    throw fail("unterminated string");
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case '"' -> b.append('"');
                    case '\\' -> b.append('\\');
                    case '/' -> b.append('/');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case 'u' -> {
                        if (pos + 4 > s.length()) {
                            throw fail("bad \\u escape");
                        }
                        try {
                            b.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw fail("bad \\u escape");
                        }
                        pos += 4;
                    }
                    default -> {
                        pos--;
                        throw fail("bad escape");
                    }
                }
            }
        }

        Long number() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            if (pos >= s.length() || !isDigit(s.charAt(pos))) {
                throw fail("bad number");
            }
            if (s.charAt(pos) == '0' && pos + 1 < s.length() && isDigit(s.charAt(pos + 1))) {
                throw fail("leading zero in a number");
            }
            while (pos < s.length() && isDigit(s.charAt(pos))) {
                pos++;
            }
            if (pos < s.length() && (s.charAt(pos) == '.' || s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                throw fail("only whole numbers are used in this format");
            }
            String digits = s.substring(start, pos);
            if (digits.equals("-0")) {
                pos = start;
                throw fail("bad number");
            }
            try {
                return Long.parseLong(digits);
            } catch (NumberFormatException ex) {
                pos = start;
                throw fail("number out of range");
            }
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        char peek() {
            if (pos >= s.length()) {
                throw fail("unexpected end of the document");
            }
            return s.charAt(pos);
        }

        void expect(char c) {
            if (peek() != c) {
                throw fail("expected " + c);
            }
            pos++;
        }
    }
}
