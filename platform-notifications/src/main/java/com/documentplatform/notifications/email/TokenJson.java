package com.documentplatform.notifications.email;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the token endpoint's JSON reply: objects, arrays, strings, numbers, true, false, null. Strict and small. A
 * failure says only that the reply is not valid JSON, never what it contained (it may hold a token).
 */
final class TokenJson {

    static final class Invalid extends Exception {
        Invalid() {
            super("not valid JSON", null, false, false);
        }
    }

    private static final int MAX_DEPTH = 16;

    private final String s;
    private int i;

    private TokenJson(String s) {
        this.s = s;
    }

    /** The top-level object. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(String text) throws Invalid {
        TokenJson p = new TokenJson(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != text.length() || !(v instanceof Map)) throw new Invalid();
        return (Map<String, Object>) v;
    }

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
                    m.put(key, value(depth + 1));
                    ws();
                } while (peek(','));
                expect('}');
                return m;
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
                return l;
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
        if (n.isEmpty()) throw new Invalid();
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
