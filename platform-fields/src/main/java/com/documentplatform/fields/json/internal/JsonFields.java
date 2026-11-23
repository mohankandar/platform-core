package com.documentplatform.fields.json.internal;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Typed, strict reads from a parsed JSON object. Every failure is a {@link JsonException} that names the field and
 * the expected shape, never the value found.
 */
public final class JsonFields {

    private final Map<String, Object> map;
    private final String where;

    private JsonFields(Map<String, Object> map, String where) {
        this.map = map;
        this.where = where;
    }

    public static JsonFields of(Object value, String where) {
        if (!(value instanceof Map<?, ?> m)) {
            throw new JsonException(where + " must be an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) m;
        return new JsonFields(typed, where);
    }

    /** Refuses any key outside {@code allowed}. */
    public JsonFields only(Set<String> allowed) {
        Set<String> extra = new TreeSet<>(map.keySet());
        extra.removeAll(allowed);
        if (!extra.isEmpty()) {
            throw new JsonException(where + " has unknown field(s) " + extra);
        }
        return this;
    }

    public boolean has(String key) {
        return map.containsKey(key);
    }

    public Object raw(String key) {
        Object v = map.get(key);
        if (v == null) {
            throw new JsonException(where + "." + key + " is missing");
        }
        return v;
    }

    public String string(String key) {
        if (!(raw(key) instanceof String s)) {
            throw new JsonException(where + "." + key + " must be a string");
        }
        return s;
    }

    public long integer(String key) {
        if (!(raw(key) instanceof Long l)) {
            throw new JsonException(where + "." + key + " must be a whole number");
        }
        return l;
    }

    public boolean bool(String key) {
        if (!(raw(key) instanceof Boolean b)) {
            throw new JsonException(where + "." + key + " must be true or false");
        }
        return b;
    }

    public List<Object> list(String key) {
        if (!(raw(key) instanceof List<?> l)) {
            throw new JsonException(where + "." + key + " must be an array");
        }
        @SuppressWarnings("unchecked")
        List<Object> typed = (List<Object>) l;
        return typed;
    }

    public JsonFields object(String key) {
        return of(raw(key), where + "." + key);
    }

    public Map<String, Object> map() {
        return map;
    }
}
