package com.documentplatform.crypto.vault;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads Vault's JSON replies, which use {@code null} and may use fractions. A copy of platform-licensing's reader, so this
 * module depends on nothing. Objects are {@code Map}, arrays {@code List}, numbers {@code BigDecimal}. Writes the small
 * request bodies. Error messages never repeat the text read.
 */
final class VaultJson {

    private final String s;
    private int pos;

    private VaultJson(String s) {
        this.s = s;
    }

    static final class Malformed extends RuntimeException {
        Malformed() {
            super("Vault's reply is not valid JSON");
        }
    }

    static Map<String, Object> parseObject(String text) {
        VaultJson p = new VaultJson(text == null ? "" : text);
        p.space();
        Object v = p.value(0);
        p.space();
        if (p.pos != p.s.length() || !(v instanceof Map<?, ?>)) {
            throw new Malformed();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) v;
        return m;
    }

    /** {@code root.a.b...} as a map, if every step is one. */
    @SuppressWarnings("unchecked")
    static Optional<Map<String, Object>> object(Map<String, Object> root, String... path) {
        Object at = root;
        for (String step : path) {
            if (!(at instanceof Map<?, ?> m)) {
                return Optional.empty();
            }
            at = m.get(step);
        }
        return at instanceof Map<?, ?> m ? Optional.of((Map<String, Object>) m) : Optional.empty();
    }

    static Optional<String> string(Map<String, Object> m, String key) {
        return m.get(key) instanceof String v ? Optional.of(v) : Optional.empty();
    }

    static Optional<Long> integer(Map<String, Object> m, String key) {
        if (m.get(key) instanceof BigDecimal d) {
            try {
                return Optional.of(d.longValueExact());
            } catch (ArithmeticException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    static boolean bool(Map<String, Object> m, String key) {
        return Boolean.TRUE.equals(m.get(key));
    }

    /** A request body of string, number, boolean and (nested) object values. */
    static String write(Map<String, ?> body) {
        StringBuilder b = new StringBuilder();
        write(b, body);
        return b.toString();
    }

    private static void write(StringBuilder b, Map<String, ?> body) {
        b.append('{');
        boolean first = true;
        for (Map.Entry<String, ?> e : body.entrySet()) {
            if (!first) {
                b.append(',');
            }
            first = false;
            quote(b, e.getKey());
            b.append(':');
            Object v = e.getValue();
            if (v instanceof String str) {
                quote(b, str);
            } else if (v instanceof Number || v instanceof Boolean) {
                b.append(v);
            } else if (v instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, ?> nested = (Map<String, ?>) m;
                write(b, nested);
            } else if (v instanceof List<?> list) {
                b.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        b.append(',');
                    }
                    if (list.get(i) instanceof String s) {
                        quote(b, s);
                        continue;
                    }
                    if (!(list.get(i) instanceof Map<?, ?> item)) {
                        throw new IllegalArgumentException("unsupported list item type");
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, ?> nested = (Map<String, ?>) item;
                    write(b, nested);
                }
                b.append(']');
            } else {
                throw new IllegalArgumentException("unsupported value type");
            }
        }
        b.append('}');
    }

    private static void quote(StringBuilder b, String v) {
        b.append('"');
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
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

    private Object value(int depth) {
        if (depth > 64 || pos >= s.length()) {
            throw new Malformed();
        }
        char c = s.charAt(pos);
        switch (c) {
            case '{' -> {
                pos++;
                Map<String, Object> m = new LinkedHashMap<>();
                space();
                if (peek() == '}') {
                    pos++;
                    return m;
                }
                while (true) {
                    space();
                    String key = string();
                    space();
                    expect(':');
                    space();
                    m.put(key, value(depth + 1));
                    space();
                    if (peek() == ',') {
                        pos++;
                    } else {
                        expect('}');
                        return m;
                    }
                }
            }
            case '[' -> {
                pos++;
                List<Object> l = new ArrayList<>();
                space();
                if (peek() == ']') {
                    pos++;
                    return l;
                }
                while (true) {
                    space();
                    l.add(value(depth + 1));
                    space();
                    if (peek() == ',') {
                        pos++;
                    } else {
                        expect(']');
                        return l;
                    }
                }
            }
            case '"' -> {
                return string();
            }
            case 't' -> {
                literal("true");
                return Boolean.TRUE;
            }
            case 'f' -> {
                literal("false");
                return Boolean.FALSE;
            }
            case 'n' -> {
                literal("null");
                return null;
            }
            default -> {
                int start = pos;
                while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
                    pos++;
                }
                try {
                    return new BigDecimal(s.substring(start, pos));
                } catch (NumberFormatException e) {
                    throw new Malformed();
                }
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            if (pos >= s.length()) {
                throw new Malformed();
            }
            char c = s.charAt(pos++);
            if (c == '"') {
                return b.toString();
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (pos >= s.length()) {
                throw new Malformed();
            }
            char e = s.charAt(pos++);
            switch (e) {
                case '"', '\\', '/' -> b.append(e);
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'u' -> {
                    if (pos + 4 > s.length()) {
                        throw new Malformed();
                    }
                    try {
                        b.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw new Malformed();
                    }
                    pos += 4;
                }
                default -> throw new Malformed();
            }
        }
    }

    private void literal(String word) {
        if (!s.startsWith(word, pos)) {
            throw new Malformed();
        }
        pos += word.length();
    }

    private char peek() {
        if (pos >= s.length()) {
            throw new Malformed();
        }
        return s.charAt(pos);
    }

    private void expect(char c) {
        if (peek() != c) {
            throw new Malformed();
        }
        pos++;
    }

    private void space() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
            pos++;
        }
    }
}
