package com.documentplatform.eraselog.file;

import com.documentplatform.eraselog.ChainPoint;
import com.documentplatform.eraselog.EraseEntry;
import com.documentplatform.eraselog.EraseLogError;
import com.documentplatform.eraselog.EraseLogException;
import com.documentplatform.eraselog.EraseRequest;
import com.documentplatform.eraselog.ErasedBy;
import com.documentplatform.eraselog.Seal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The file store's text: one JSON object per line, flat, with a fixed set of fields. Every field has a character set
 * without quotes or backslashes, so nothing is escaped; a line with any other field, an escape, or a field missing is
 * refused ({@link EraseLogError#ERASE_LOG_FORMAT}) — so a value cannot be slipped into the log by hand either.
 *
 * <p>A keyed entry ({@code platform-erase-log/2}) has two more fields, {@code kv} and {@code mac}, always together.
 */
final class Lines {

    static final List<String> ENTRY_FIELDS = List.of("seq", "loggedAt", "entity", "product", "recordType", "recordId",
            "kind", "erasedAt", "byType", "by", "auditId", "prev", "hash");
    static final List<String> KEYED_FIELDS = List.of("kv", "mac");
    static final List<String> CHECKPOINT_FIELDS = List.of("format", "logId", "seq", "hash", "prunedAt");
    static final String CHECKPOINT_FORMAT = "platform-erase-log-checkpoint/1";
    static final List<String> SEAL_FIELDS = List.of("format", "logId", "from", "anchor", "kv", "mac", "sealedAt");
    static final List<String> APPLIED_FIELDS = List.of("format", "logId", "seq");
    static final String APPLIED_FORMAT = "platform-erase-log-applied/1";

    private Lines() {
    }

    static String entry(EraseEntry e) {
        EraseRequest r = e.request();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", e.seq());
        m.put("loggedAt", e.loggedAt().toString());
        m.put("entity", r.entity().toString());
        m.put("product", r.product());
        m.put("recordType", r.recordType());
        m.put("recordId", r.recordId());
        m.put("kind", r.kind());
        m.put("erasedAt", r.erasedAt().toString());
        m.put("byType", r.by().type().name());
        m.put("by", r.by().id());
        m.put("auditId", r.auditId());
        m.put("prev", e.prev());
        m.put("hash", e.hash());
        if (e.keyed()) {
            m.put("kv", (long) e.keyVersion());
            m.put("mac", e.mac());
        }
        return write(m);
    }

    static EraseEntry entry(UUID logId, String line, long lineNo) {
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1); // a copy saved with Windows line ends
        }
        Map<String, String> m = parse(line, ENTRY_FIELDS, KEYED_FIELDS, lineNo);
        if (m.containsKey("kv") != m.containsKey("mac")) {
            throw format(lineNo, "a keyed entry has both kv and mac");
        }
        try {
            EraseRequest r = new EraseRequest(UUID.fromString(m.get("entity")), m.get("product"), m.get("recordType"),
                    m.get("recordId"), m.get("kind"), Instant.parse(m.get("erasedAt")),
                    new ErasedBy(ErasedBy.Type.valueOf(m.get("byType")), m.get("by")), m.get("auditId"));
            int kv = m.containsKey("kv") ? Integer.parseInt(m.get("kv")) : 0;
            if (m.containsKey("kv") && kv < 1) throw format(lineNo, "field kv is not a key version");
            return new EraseEntry(logId, Long.parseLong(m.get("seq")), Instant.parse(m.get("loggedAt")), r,
                    m.get("prev"), m.get("hash"), kv, m.get("mac"));
        } catch (EraseLogException e) {
            throw format(lineNo, e.reason());
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw format(lineNo, "a field is not of its form");
        }
    }

    static String checkpoint(UUID logId, ChainPoint point, Instant prunedAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", CHECKPOINT_FORMAT);
        m.put("logId", logId.toString());
        m.put("seq", point.seq());
        m.put("hash", point.hash());
        m.put("prunedAt", prunedAt.toString());
        return write(m);
    }

    static ChainPoint checkpoint(UUID logId, String text) {
        Map<String, String> m = parse(text.strip(), CHECKPOINT_FIELDS, List.of(), 1);
        try {
            if (!CHECKPOINT_FORMAT.equals(m.get("format")) || !logId.equals(UUID.fromString(m.get("logId")))) {
                throw new EraseLogException(EraseLogError.ERASE_LOG_FORMAT, "the checkpoint is not this log's");
            }
            return new ChainPoint(Long.parseLong(m.get("seq")), m.get("hash"));
        } catch (IllegalArgumentException e) {
            throw new EraseLogException(EraseLogError.ERASE_LOG_FORMAT, "the checkpoint is not readable");
        }
    }

    static String seal(Seal s, Instant sealedAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", Seal.FORMAT);
        m.put("logId", s.logId().toString());
        m.put("from", s.from());
        m.put("anchor", s.anchor());
        m.put("kv", (long) s.keyVersion());
        m.put("mac", s.mac());
        m.put("sealedAt", sealedAt.toString());
        return write(m);
    }

    static Seal seal(String text) {
        Map<String, String> m = parse(text.strip(), SEAL_FIELDS, List.of(), 1);
        try {
            if (!Seal.FORMAT.equals(m.get("format"))) {
                throw new EraseLogException(EraseLogError.ERASE_LOG_FORMAT, "the seal is not readable");
            }
            Instant.parse(m.get("sealedAt"));
            return new Seal(UUID.fromString(m.get("logId")), Long.parseLong(m.get("from")), m.get("anchor"),
                    Integer.parseInt(m.get("kv")), m.get("mac"));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new EraseLogException(EraseLogError.ERASE_LOG_FORMAT, "the seal is not readable");
        }
    }

    static String applied(UUID logId, long seq) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", APPLIED_FORMAT);
        m.put("logId", logId.toString());
        m.put("seq", seq);
        return write(m);
    }

    /** The recorded high-water mark; 0 for a file of another log or not readable (the check then just has none). */
    static long applied(UUID logId, String text) {
        try {
            Map<String, String> m = parse(text.strip(), APPLIED_FIELDS, List.of(), 1);
            if (!APPLIED_FORMAT.equals(m.get("format")) || !logId.equals(UUID.fromString(m.get("logId")))) return 0;
            return Long.parseLong(m.get("seq"));
        } catch (EraseLogException | IllegalArgumentException e) {
            return 0;
        }
    }

    private static String write(Map<String, Object> m) {
        StringBuilder b = new StringBuilder("{");
        m.forEach((k, v) -> {
            if (b.length() > 1) {
                b.append(',');
            }
            b.append('"').append(k).append("\":");
            if (v instanceof Long n) {
                b.append(n);
            } else {
                String s = (String) v;
                if (s.indexOf('"') >= 0 || s.indexOf('\\') >= 0) {
                    throw new IllegalStateException("field " + k + " has a quote or backslash");
                }
                b.append('"').append(s).append('"');
            }
        });
        return b.append('}').toString();
    }

    /**
     * A flat object of the given fields (each of {@code required}, any of {@code optional}), each a string without
     * escapes or a whole number; nothing else.
     */
    private static Map<String, String> parse(String line, List<String> required, List<String> optional, long lineNo) {
        Map<String, String> m = new LinkedHashMap<>();
        int i;
        int n = line.length();
        if (n < 2 || line.charAt(0) != '{' || line.charAt(n - 1) != '}') {
            throw format(lineNo, "not an entry");
        }
        i = 1;
        while (i < n - 1) {
            if (line.charAt(i) != '"') {
                throw format(lineNo, "not an entry");
            }
            int end = line.indexOf('"', i + 1);
            if (end < 0 || end + 1 >= n || line.charAt(end + 1) != ':') {
                throw format(lineNo, "not an entry");
            }
            String key = line.substring(i + 1, end);
            if (!required.contains(key) && !optional.contains(key)) {
                throw format(lineNo, "unknown field " + (key.matches("[A-Za-z]{1,32}") ? key : "(not a name)"));
            }
            i = end + 2;
            String value;
            if (i < n && line.charAt(i) == '"') {
                int close = line.indexOf('"', i + 1);
                if (close < 0) {
                    throw format(lineNo, "not an entry");
                }
                value = line.substring(i + 1, close);
                if (value.indexOf('\\') >= 0) {
                    throw format(lineNo, "field " + key + " has an escape");
                }
                i = close + 1;
            } else {
                int j = i;
                while (j < n - 1 && Character.isDigit(line.charAt(j))) {
                    j++;
                }
                if (j == i) {
                    throw format(lineNo, "field " + key + " is not a string or a number");
                }
                value = line.substring(i, j);
                i = j;
            }
            if (m.put(key, value) != null) {
                throw format(lineNo, "field " + key + " is given twice");
            }
            if (i < n - 1) {
                if (line.charAt(i) != ',') {
                    throw format(lineNo, "not an entry");
                }
                i++;
            }
        }
        for (String f : required) {
            if (!m.containsKey(f)) {
                throw format(lineNo, "field " + f + " is missing");
            }
        }
        return m;
    }

    private static EraseLogException format(long lineNo, String why) {
        return new EraseLogException(EraseLogError.ERASE_LOG_FORMAT, (lineNo > 0 ? "line " + lineNo
                : "a line near the end") + " of the erase log: " + why);
    }
}
