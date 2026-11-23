package com.documentplatform.modelgov;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A use's configuration fingerprint: {@code sha256:} plus the SHA-256 of the canonical form of the app's JSON of its
 * prompt and settings. The app writes the JSON; platform-core gives the canonical form (object keys sorted, no
 * whitespace, numbers in their shortest plain form, strings with the minimal escapes), so the same settings always
 * give the same fingerprint whatever order or spacing the app used.
 */
public final class ConfigFingerprint {

    private ConfigFingerprint() {
    }

    /** The fingerprint of the app's JSON. */
    public static String of(String json) {
        return "sha256:" + sha256(canonical(json));
    }

    /** The canonical form of a JSON document. */
    public static String canonical(String json) {
        Parser p = new Parser(json);
        Object v = p.value();
        p.end();
        StringBuilder sb = new StringBuilder();
        write(sb, v);
        return sb.toString();
    }

    /** Whether the text has the form of a fingerprint. */
    /** The canonical JSON of a tree of maps, lists, strings, numbers, booleans and nulls (the pack's JSON). */
    static String canonicalOf(Object tree) {
        StringBuilder sb = new StringBuilder();
        write(sb, tree);
        return sb.toString();
    }

    /** {@code sha256:} plus the SHA-256 of the text's UTF-8 bytes. */
    static String sha256Of(String text) {
        return "sha256:" + sha256(text);
    }

    public static boolean isFingerprint(String s) {
        return s != null && s.matches("sha256:[0-9a-f]{64}");
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Boolean b) {
            sb.append(b);
        } else if (v instanceof Number num && !(v instanceof BigDecimal)) {
            write(sb, new BigDecimal(num.toString()));
        } else if (v instanceof BigDecimal n) {
            BigDecimal s = n.stripTrailingZeros();
            sb.append(s.scale() <= 0 ? s.toBigIntegerExact().toString() : s.toPlainString());
        } else if (v instanceof String s) {
            sb.append('"');
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    case '\b' -> sb.append("\\b");
                    case '\f' -> sb.append("\\f");
                    default -> {
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                    }
                }
            }
            sb.append('"');
        } else if (v instanceof List<?> l) {
            sb.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(',');
                write(sb, l.get(i));
            }
            sb.append(']');
        } else {
            Map<String, Object> m = new TreeMap<>((Map<String, Object>) v);
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                write(sb, e.getKey());
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        }
    }

    /** A strict small JSON reader (RFC 8259); a duplicate key is refused. */
    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            if (s == null) throw Values.invalid("configuration", "JSON is required");
            this.s = s;
        }

        void end() {
            ws();
            if (i != s.length()) throw bad();
        }

        Object value() {
            ws();
            if (i >= s.length()) throw bad();
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            i++;
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                if (peek() != '"') throw bad();
                String k = string();
                ws();
                expect(':');
                if (m.containsKey(k)) throw Values.invalid("configuration", "a key appears twice");
                m.put(k, value());
                ws();
                if (peek() == ',') {
                    i++;
                    continue;
                }
                expect('}');
                return m;
            }
        }

        private List<Object> array() {
            i++;
            List<Object> l = new ArrayList<>();
            ws();
            if (peek() == ']') {
                i++;
                return l;
            }
            while (true) {
                l.add(value());
                ws();
                if (peek() == ',') {
                    i++;
                    continue;
                }
                expect(']');
                return l;
            }
        }

        private String string() {
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (i >= s.length()) throw bad();
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"', '\\', '/' -> sb.append(e);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (i + 4 > s.length()) throw bad();
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> throw bad();
                    }
                } else if (c < 0x20) {
                    throw bad();
                } else {
                    sb.append(c);
                }
            }
            throw bad();
        }

        private BigDecimal number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(start, i);
            if (!n.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw bad();
            return new BigDecimal(n);
        }

        private Object literal(String word, Object value) {
            if (!s.startsWith(word, i)) throw bad();
            i += word.length();
            return value;
        }

        private void expect(char c) {
            if (peek() != c) throw bad();
            i++;
        }

        private char peek() {
            return i < s.length() ? s.charAt(i) : '\0';
        }

        private void ws() {
            while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++;
        }

        private ModelGovException bad() {
            return Values.invalid("configuration", "not valid JSON (at character " + i + ")");
        }
    }
}
