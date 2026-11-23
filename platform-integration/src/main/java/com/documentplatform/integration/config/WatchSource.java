package com.documentplatform.integration.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A watched folder or network share (kind {@code WATCHED_FOLDER}): where drops arrive and how one is recognised as
 * complete. The customer's process writes the files first and the <b>trigger file last</b>; a drop is taken only when
 * its trigger is there and its files are stable. The folder's rights are the service account's (no sign-in bundle).
 * The file size and the files per drop are the integration's limits ({@link Limits#maxFileBytes()},
 * {@link Limits#maxDocuments()}).
 *
 * @param root            the watched folder: an absolute local path or a share path ({@code \\server\share\in}), as for
 *                        folder export
 * @param layout          SUBFOLDER: one subfolder per drop; BASE_NAME: files in the root grouped by base name (the
 *                        name up to its first dot, {@code loan-17.pdf} and {@code loan-17.json} are drop
 *                        {@code loan-17})
 * @param triggerPattern  the trigger's file name: {@code *} for any characters, {@code ?} for one, and
 *                        {@code <base>} for the drop's name ({@code *.json}, {@code manifest.xml}, {@code <base>.done})
 * @param triggerType     how the trigger is read: JSON, XML, or MARKER (its bytes are handed over as they are, an
 *                        empty {@code .lck} or {@code .done} for example)
 * @param stableFor       sizes and modification times unchanged for this long before a drop is taken (1 s – 1 h)
 * @param incompleteAfter files present without a trigger for this long: reported once as incomplete (1 min – 7 d)
 * @param claimTimeout    a claim older than this (its app instance died) is released back to the root (1 min – 24 h)
 * @param maxDropsPerPass most drops one pass takes (1–1000)
 * @param maxTriggerBytes largest trigger read (1 byte – 4 MiB); a larger one is unreadable
 * @param mapping         value name → JSON pointer (JSON trigger) or path (XML trigger): named values extracted by
 *                        this fixed mapping and handed to the app, which alone decides what they mean
 * @param fileListValue   the mapped value that lists the drop's file names, or null: platform-core then reports files
 *                        the list does not name and names with no file, without deciding anything
 */
public record WatchSource(String root, Layout layout, String triggerPattern, TriggerType triggerType,
                          Duration stableFor, Duration incompleteAfter, Duration claimTimeout, int maxDropsPerPass,
                          long maxTriggerBytes, Map<String, String> mapping, String fileListValue) {

    public enum Layout { SUBFOLDER, BASE_NAME }

    public enum TriggerType { JSON, XML, MARKER }

    /** The folders platform-core keeps in the root; names starting with {@code _} or {@code .} are never drops. */
    public static final String PROCESSING = "_processing";
    public static final String ARCHIVE = "_archive";
    public static final String PROBLEM = "_problem";
    public static final String QUARANTINE = "_quarantine";
    /** The note written next to a drop moved to {@code _problem} or {@code _quarantine}. */
    public static final String PROBLEM_FILE = "problem.json";
    /** The token of the drop's name in a trigger pattern. */
    public static final String BASE = "<base>";

    public static final Duration DEFAULT_STABLE_FOR = Duration.ofSeconds(30);
    public static final Duration DEFAULT_INCOMPLETE_AFTER = Duration.ofMinutes(60);
    public static final Duration DEFAULT_CLAIM_TIMEOUT = Duration.ofMinutes(30);
    public static final long DEFAULT_TRIGGER_BYTES = 1024 * 1024;
    public static final long MAX_TRIGGER_BYTES = 4L * 1024 * 1024;

    private static final Pattern PATTERN = Pattern.compile("[A-Za-z0-9 _.*?<>()\\[\\]{}#@+=,;!~'&$%^-]{1,200}");

    public WatchSource {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(triggerPattern, "triggerPattern");
        triggerType = triggerType == null ? typeOf(triggerPattern) : triggerType;
        stableFor = stableFor == null ? DEFAULT_STABLE_FOR : stableFor;
        incompleteAfter = incompleteAfter == null ? DEFAULT_INCOMPLETE_AFTER : incompleteAfter;
        claimTimeout = claimTimeout == null ? DEFAULT_CLAIM_TIMEOUT : claimTimeout;
        mapping = mapping == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(mapping));
    }

    /** Drops in {@code root} by {@code layout}, triggered by {@code triggerPattern}, with the default times. */
    public static WatchSource of(String root, Layout layout, String triggerPattern) {
        return new WatchSource(root, layout, triggerPattern, null, null, null, null, 100, DEFAULT_TRIGGER_BYTES,
                Map.of(), null);
    }

    /** The trigger type its pattern suggests: {@code .json} JSON, {@code .xml} XML, anything else a marker. */
    public static TriggerType typeOf(String pattern) {
        String p = pattern.toLowerCase(Locale.ROOT);
        if (p.endsWith(".json")) return TriggerType.JSON;
        if (p.endsWith(".xml")) return TriggerType.XML;
        return TriggerType.MARKER;
    }

    /** Whether the pattern has only allowed characters, no separator, and {@code <base>} at most once. */
    public static boolean validPattern(String p) {
        if (p == null || !PATTERN.matcher(p).matches()) return false;
        String without = p.replace(BASE, "");
        if (p.length() - without.length() > BASE.length()) return false;
        if (without.contains("<") || without.contains(">")) return false;
        return !p.startsWith("_") && !p.startsWith(".");
    }

    /** Whether {@code fileName} matches the pattern for a drop named {@code base} (case-insensitive). */
    public boolean isTrigger(String fileName, String base) {
        StringBuilder rx = new StringBuilder();
        String p = triggerPattern.replace(BASE, "\u0000");
        for (char ch : p.toCharArray()) {
            if (ch == '*') rx.append(".*");
            else if (ch == '?') rx.append('.');
            else if (ch == '\u0000') rx.append(Pattern.quote(base));
            else rx.append(Pattern.quote(String.valueOf(ch)));
        }
        return Pattern.compile(rx.toString(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(fileName).matches();
    }

    public WatchSource withTriggerType(TriggerType t) {
        return new WatchSource(root, layout, triggerPattern, t, stableFor, incompleteAfter, claimTimeout,
                maxDropsPerPass, maxTriggerBytes, mapping, fileListValue);
    }

    public WatchSource withStableFor(Duration d) {
        return new WatchSource(root, layout, triggerPattern, triggerType, d, incompleteAfter, claimTimeout,
                maxDropsPerPass, maxTriggerBytes, mapping, fileListValue);
    }

    public WatchSource withIncompleteAfter(Duration d) {
        return new WatchSource(root, layout, triggerPattern, triggerType, stableFor, d, claimTimeout,
                maxDropsPerPass, maxTriggerBytes, mapping, fileListValue);
    }

    public WatchSource withClaimTimeout(Duration d) {
        return new WatchSource(root, layout, triggerPattern, triggerType, stableFor, incompleteAfter, d,
                maxDropsPerPass, maxTriggerBytes, mapping, fileListValue);
    }

    public WatchSource withMaxDropsPerPass(int n) {
        return new WatchSource(root, layout, triggerPattern, triggerType, stableFor, incompleteAfter, claimTimeout, n,
                maxTriggerBytes, mapping, fileListValue);
    }

    public WatchSource withMaxTriggerBytes(long n) {
        return new WatchSource(root, layout, triggerPattern, triggerType, stableFor, incompleteAfter, claimTimeout,
                maxDropsPerPass, n, mapping, fileListValue);
    }

    /** Extracts {@code value} from the trigger at {@code path} (a JSON pointer, or an XML path). */
    public WatchSource mapping(String value, String path) {
        Map<String, String> m = new LinkedHashMap<>(mapping);
        m.put(value, path);
        return new WatchSource(root, layout, triggerPattern, triggerType, stableFor, incompleteAfter, claimTimeout,
                maxDropsPerPass, maxTriggerBytes, m, fileListValue);
    }

    public WatchSource withFileListValue(String value) {
        return new WatchSource(root, layout, triggerPattern, triggerType, stableFor, incompleteAfter, claimTimeout,
                maxDropsPerPass, maxTriggerBytes, mapping, value);
    }

    @Override
    public String toString() {
        return "WatchSource[" + (root.startsWith("\\\\") ? "share" : "local") + ", " + layout + ", " + triggerPattern
                + "]";
    }
}
