package com.documentplatform.identity.signin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small strict JSON reader for discovery documents, key sets and token parts (identity-core has no dependencies).
 * Objects become {@code Map<String, Object>}, arrays {@code List<Object>}, numbers {@code Long} or {@code Double},
 * and {@code true}, {@code false}, {@code null} become {@code Boolean} and null. A duplicate key, trailing text or a
 * nesting deeper than 64 refuses the text. Error messages name the position only, never the text.
 */
final class MiniJson {

    static final class Invalid extends Exception {
        Invalid(String message) {
            super(message, null, false, false);
        }
    }

    private static final int MAX_DEPTH = 64;

    private final String s;
    private int i;

    private MiniJson(String s) {
        this.s = s;
    }

    static Object parse(String text) throws Invalid {
        MiniJson p = new MiniJson(text);
        p.space();
        Object v = p.value(0);
        p.space();
        if (p.i != text.length()) throw new Invalid("text after the value at " + p.i);
        return v;
    }

    /** The text as a JSON object, or refused. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(String text) throws Invalid {
        Object v = parse(text);
        if (!(v instanceof Map<?, ?>)) throw new Invalid("not a JSON object");
        return (Map<String, Object>) v;
    }

    private Object value(int depth) throws Invalid {
        if (depth > MAX_DEPTH) throw new Invalid("nested too deeply");
        if (i >= s.length()) throw new Invalid("value expected at " + i);
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) yield number();
                throw new Invalid("unexpected character at " + i);
            }
        };
    }

    private Map<String, Object> object(int depth) throws Invalid {
        Map<String, Object> out = new LinkedHashMap<>();
        i++;
        space();
        if (peek() == '}') {
            i++;
            return out;
        }
        while (true) {
            space();
            if (peek() != '"') throw new Invalid("key expected at " + i);
            String key = string();
            space();
            expect(':');
            space();
            Object v = value(depth + 1);
            if (out.containsKey(key)) throw new Invalid("duplicate key at " + i);
            out.put(key, v);
            space();
            char c = next();
            if (c == '}') return out;
            if (c != ',') throw new Invalid("',' or '}' expected at " + (i - 1));
        }
    }

    private List<Object> array(int depth) throws Invalid {
        List<Object> out = new ArrayList<>();
        i++;
        space();
        if (peek() == ']') {
            i++;
            return out;
        }
        while (true) {
            space();
            out.add(value(depth + 1));
            space();
            char c = next();
            if (c == ']') return out;
            if (c != ',') throw new Invalid("',' or ']' expected at " + (i - 1));
        }
    }

    private String string() throws Invalid {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw new Invalid("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c < 0x20) throw new Invalid("control character in a string at " + (i - 1));
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = next();
            switch (e) {
                case '"', '\\', '/' -> b.append(e);
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'u' -> {
                    if (i + 4 > s.length()) throw new Invalid("bad escape at " + i);
                    try {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw new Invalid("bad escape at " + i);
                    }
                    i += 4;
                }
                default -> throw new Invalid("bad escape at " + (i - 1));
            }
        }
    }

    private Object number() throws Invalid {
        int start = i;
        if (peek() == '-') i++;
        while (i < s.length() && "0123456789+-.eE".indexOf(s.charAt(i)) >= 0) i++;
        String n = s.substring(start, i);
        try {
            if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
            try {
                return Long.parseLong(n);
            } catch (NumberFormatException tooLong) {
                return Double.parseDouble(n);
            }
        } catch (NumberFormatException e) {
            throw new Invalid("bad number at " + start);
        }
    }

    private Object literal(String word, Object v) throws Invalid {
        if (!s.startsWith(word, i)) throw new Invalid("unexpected word at " + i);
        i += word.length();
        return v;
    }

    private void space() {
        while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++;
    }

    private char peek() throws Invalid {
        if (i >= s.length()) throw new Invalid("unexpected end");
        return s.charAt(i);
    }

    private char next() throws Invalid {
        char c = peek();
        i++;
        return c;
    }

    private void expect(char c) throws Invalid {
        if (next() != c) throw new Invalid("'" + c + "' expected at " + (i - 1));
    }
}
