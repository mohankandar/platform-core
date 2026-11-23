package com.documentplatform.fields.file;

import com.documentplatform.fields.FieldDefinitionException;
import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.FieldType;
import com.documentplatform.fields.Normalisation;
import com.documentplatform.fields.SearchMode;
import com.documentplatform.fields.json.internal.Json;
import com.documentplatform.fields.json.internal.JsonException;
import com.documentplatform.fields.json.internal.JsonFields;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * The field list file, format {@code platform-fields/1}: one product exports an entity's field definitions, another
 * imports them, so nobody types them twice. JSON (the strict subset the licence files use too), for example:
 *
 * <pre>{@code
 * {
 *   "exportedAt": "2026-11-21T10:00:00Z",
 *   "fields": [
 *     { "key": "loan_number", "label": "Loan number", "type": "TEXT", "required": true, "maxLength": 20,
 *       "letterCase": "UPPER", "strip": "SEPARATORS", "primary": true, "searchable": true, "searchMode": "EXACT",
 *       "sensitive": false, "multiValue": false, "displayable": true, "displayOrder": 0, "active": true },
 *     { "key": "loan_amount", "label": "Loan amount", "type": "MONEY", "currency": "USD", "minValue": "0.00", ... },
 *     { "key": "loan_type", "label": "Loan type", "type": "DROPDOWN", "options": ["Fixed", "Variable"], ... }
 *   ],
 *   "format": "platform-fields/1"
 * }
 * }</pre>
 *
 * <p>Written with every member; read strictly: unknown members are refused, and only {@code key}, {@code label} and
 * {@code type} are required (the others default as in {@link FieldSpec#builder}). Decimals ({@code minValue},
 * {@code maxValue}) are strings. The file holds definitions only: no values, no entity, no app-only settings.
 */
public final class FieldListFile {

    public static final String FORMAT = "platform-fields/1";
    public static final int MAX_FIELDS = 1000;
    public static final int MAX_CHARACTERS = 5_000_000;

    private static final Set<String> TOP = Set.of("format", "exportedAt", "fields");
    private static final Set<String> FIELD = Set.of("key", "label", "type", "currency", "options", "required",
            "exactLength", "minLength", "maxLength", "pattern", "letterCase", "strip", "minValue", "maxValue",
            "primary", "searchable", "searchMode", "sensitive", "multiValue", "displayable", "displayOrder", "active");

    private FieldListFile() {
    }

    /** A file's content. */
    public record Content(Instant exportedAt, List<FieldSpec> fields) {
        public Content {
            Objects.requireNonNull(exportedAt, "exportedAt");
            fields = List.copyOf(fields);
        }
    }

    /** The file for these fields, indented for people to read. */
    public static String write(List<FieldSpec> fields, Instant exportedAt) {
        Map<String, Object> top = new TreeMap<>();
        top.put("format", FORMAT);
        top.put("exportedAt", exportedAt.toString());
        top.put("fields", fields.stream().map(FieldListFile::toJson).toList());
        return Json.pretty(top);
    }

    /**
     * Reads a file, checking every field against the model.
     *
     * @throws FieldFileException with its code; nothing of the file's content in the message
     */
    public static Content read(String text) {
        if (text == null) {
            throw new FieldFileException(FieldFileException.Code.FILE_UNREADABLE, null, null, "The file is empty");
        }
        if (text.length() > MAX_CHARACTERS) {
            throw new FieldFileException(FieldFileException.Code.FILE_TOO_LARGE, null, null,
                    "The file is larger than 5 MB");
        }
        Object parsed;
        try {
            parsed = Json.parse(text.startsWith("﻿") ? text.substring(1) : text);
        } catch (JsonException e) {
            throw new FieldFileException(FieldFileException.Code.FILE_UNREADABLE, null, null,
                    "The file is not readable JSON: " + e.getMessage());
        }
        JsonFields top;
        try {
            top = JsonFields.of(parsed, "file");
            if (!top.has("format") || !(top.raw("format") instanceof String f) || !f.equals(FORMAT)) {
                throw new FieldFileException(FieldFileException.Code.FILE_FORMAT_UNKNOWN, null, null,
                        "Not a field list file: \"format\" must be \"" + FORMAT + "\"");
            }
            top.only(TOP);
        } catch (JsonException e) {
            throw shape(null, e);
        }
        Instant exportedAt;
        List<Object> list;
        try {
            exportedAt = Instant.parse(top.string("exportedAt"));
            list = top.list("fields");
        } catch (JsonException e) {
            throw shape(null, e);
        } catch (DateTimeParseException e) {
            throw new FieldFileException(FieldFileException.Code.FILE_SHAPE_INVALID, null, null,
                    "file.exportedAt must be a time such as 2026-11-21T10:00:00Z");
        }
        if (list.size() > MAX_FIELDS) {
            throw new FieldFileException(FieldFileException.Code.FILE_TOO_LARGE, null, null,
                    "The file has more than " + MAX_FIELDS + " fields");
        }
        List<FieldSpec> fields = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            FieldSpec spec = fromJson(list.get(i), i + 1);
            if (!keys.add(spec.key().folded())) {
                throw new FieldFileException(FieldFileException.Code.FILE_KEY_REPEATED, i + 1, null,
                        "Field " + (i + 1) + " repeats the key " + spec.key() + " (keys are compared ignoring "
                                + "capitals)");
            }
            fields.add(spec);
        }
        return new Content(exportedAt, fields);
    }

    /** One field as a JSON object (also the history's snapshot of a version). */
    public static Map<String, Object> toJson(FieldSpec s) {
        Map<String, Object> m = new TreeMap<>();
        m.put("key", s.key().value());
        m.put("label", s.label());
        m.put("type", s.type().name());
        if (s.currency() != null) {
            m.put("currency", s.currency());
        }
        if (!s.options().isEmpty()) {
            m.put("options", s.options());
        }
        m.put("required", s.required());
        putIf(m, "exactLength", s.exactLength());
        putIf(m, "minLength", s.minLength());
        putIf(m, "maxLength", s.maxLength());
        if (s.pattern() != null) {
            m.put("pattern", s.pattern());
        }
        m.put("letterCase", s.normalisation().letterCase().name());
        m.put("strip", s.normalisation().strip().name());
        if (s.minValue() != null) {
            m.put("minValue", s.minValue().toPlainString());
        }
        if (s.maxValue() != null) {
            m.put("maxValue", s.maxValue().toPlainString());
        }
        m.put("primary", s.primary());
        m.put("searchable", s.searchable());
        m.put("searchMode", s.searchMode().name());
        m.put("sensitive", s.sensitive());
        m.put("multiValue", s.multiValue());
        m.put("displayable", s.displayable());
        m.put("displayOrder", (long) s.displayOrder());
        m.put("active", s.active());
        return m;
    }

    /** The canonical JSON text of one field. */
    public static String toJsonText(FieldSpec s) {
        return Json.canonical(toJson(s));
    }

    /** Reads one field's canonical JSON text (as {@link #toJsonText} wrote it). */
    public static FieldSpec fromJsonText(String text) {
        try {
            return fromJson(Json.parse(text), 1);
        } catch (JsonException e) {
            throw new FieldFileException(FieldFileException.Code.FILE_UNREADABLE, null, null,
                    "Not readable JSON: " + e.getMessage());
        }
    }

    private static FieldSpec fromJson(Object value, int number) {
        String where = "fields[" + number + "]";
        try {
            JsonFields f = JsonFields.of(value, where).only(FIELD);
            FieldType type = choice(f, "type", FieldType.class, where);
            FieldSpec.Builder b = FieldSpec.builder(f.string("key"), f.string("label"), type);
            if (f.has("currency")) {
                b.currency(f.string("currency"));
            }
            if (f.has("options")) {
                List<String> options = new ArrayList<>();
                for (Object o : f.list("options")) {
                    if (!(o instanceof String s)) {
                        throw new JsonException(where + ".options must be a list of strings");
                    }
                    options.add(s);
                }
                b.options(options);
            }
            if (f.has("required")) {
                b.required(f.bool("required"));
            }
            b.exactLength(length(f, "exactLength")).minLength(length(f, "minLength"))
                    .maxLength(length(f, "maxLength"));
            if (f.has("pattern")) {
                b.pattern(f.string("pattern"));
            }
            Normalisation.LetterCase letterCase = f.has("letterCase")
                    ? choice(f, "letterCase", Normalisation.LetterCase.class, where) : Normalisation.LetterCase.PRESERVE;
            Normalisation.Strip strip = f.has("strip")
                    ? choice(f, "strip", Normalisation.Strip.class, where) : Normalisation.Strip.NONE;
            b.normalisation(letterCase, strip);
            b.minValue(decimal(f, "minValue", where)).maxValue(decimal(f, "maxValue", where));
            if (f.has("primary")) {
                b.primary(f.bool("primary"));
            }
            boolean searchable = f.has("searchable") && f.bool("searchable");
            SearchMode mode = f.has("searchMode") ? choice(f, "searchMode", SearchMode.class, where) : SearchMode.EXACT;
            if (searchable) {
                b.searchable(mode);
            } else if (mode != SearchMode.EXACT) {
                throw new FieldFileException(FieldFileException.Code.FILE_FIELD_INVALID, number,
                        FieldDefinitionException.Code.FIELD_SEARCH_MODE_INVALID,
                        "Field " + number + " is not searchable, so its search mode stays EXACT");
            }
            if (f.has("sensitive")) {
                b.sensitive(f.bool("sensitive"));
            }
            if (f.has("multiValue")) {
                b.multiValue(f.bool("multiValue"));
            }
            if (f.has("displayable")) {
                b.displayable(f.bool("displayable"));
            }
            if (f.has("displayOrder")) {
                long order = f.integer("displayOrder");
                b.displayOrder(order < 0 || order > FieldSpec.MAX_DISPLAY_ORDER ? -1 : (int) order);
            }
            if (f.has("active")) {
                b.active(f.bool("active"));
            }
            return b.build();
        } catch (JsonException e) {
            throw shape(number, e);
        } catch (FieldDefinitionException e) {
            throw new FieldFileException(FieldFileException.Code.FILE_FIELD_INVALID, number, e.code(),
                    "Field " + number + ": " + e.getMessage());
        }
    }

    private static Integer length(JsonFields f, String key) {
        if (!f.has(key)) {
            return null;
        }
        long l = f.integer(key);
        return l < 0 || l > Integer.MAX_VALUE ? 0 : (int) l;
    }

    private static BigDecimal decimal(JsonFields f, String key, String where) {
        if (!f.has(key)) {
            return null;
        }
        String s = f.string(key);
        if (!s.matches("-?[0-9]{1,30}(\\.[0-9]{1,10})?")) {
            throw new JsonException(where + "." + key + " must be a number written as a string, such as \"10.50\"");
        }
        return new BigDecimal(s);
    }

    private static <E extends Enum<E>> E choice(JsonFields f, String key, Class<E> type, String where) {
        String s = f.string(key);
        for (E e : type.getEnumConstants()) {
            if (e.name().equals(s)) {
                return e;
            }
        }
        throw new JsonException(where + "." + key + " must be one of " + Arrays.toString(type.getEnumConstants()));
    }

    private static void putIf(Map<String, Object> m, String key, Integer value) {
        if (value != null) {
            m.put(key, (long) value);
        }
    }

    private static FieldFileException shape(Integer number, JsonException e) {
        return new FieldFileException(FieldFileException.Code.FILE_SHAPE_INVALID, number, null, e.getMessage());
    }
}
