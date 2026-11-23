package com.documentplatform.notifications.webhook.internal;

import java.util.List;
import java.util.Map;

/**
 * A minimal JSON writer for webhook payloads: objects (insertion-ordered maps), lists, strings, numbers, booleans and
 * null. Strings are escaped fully, including {@code < > &} and the line separators, so no value can break out of its
 * string or be read as markup by an intermediate.
 *
 * <p>Internal (P041): used by the Teams and Slack senders; may change in any release, not for apps.
 */
public final class Json {

    private Json() {
    }

    public static String write(Object value) {
        StringBuilder b = new StringBuilder();
        write(b, value);
        return b.toString();
    }

    private static void write(StringBuilder b, Object value) {
        if (value == null) {
            b.append("null");
        } else if (value instanceof String s) {
            string(b, s);
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            b.append(value);
        } else if (value instanceof Map<?, ?> map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                string(b, (String) e.getKey());
                b.append(':');
                write(b, e.getValue());
            }
            b.append('}');
        } else if (value instanceof List<?> list) {
            b.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    b.append(',');
                }
                write(b, list.get(i));
            }
            b.append(']');
        } else {
            throw new IllegalArgumentException("Not a JSON value: " + value.getClass().getSimpleName());
        }
    }

    private static void string(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20 || c == '<' || c == '>' || c == '&' || c == ' ' || c == ' ') {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }
}
