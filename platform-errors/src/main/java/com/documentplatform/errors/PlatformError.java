package com.documentplatform.errors;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One failure: its code, the safe message for the person (with the reference), the exact cause for IT and the log
 * (the code's cause with its safe details filled in), the reference and the time. Details are names and addresses of
 * things only; a detail named like a secret, or a value that looks like one, is removed ({@link SafeText}).
 * {@link #toString()} and {@link #logLine()} never hold anything else.
 */
public final class PlatformError {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.-]+)}");

    private final PlatformCode code;
    private final Map<String, String> details;
    private final String reference;
    private final Instant at;

    public PlatformError(PlatformCode code, Map<String, ?> safeDetails, String reference, Instant at) {
        this.code = Objects.requireNonNull(code, "code");
        Map<String, String> d = new LinkedHashMap<>();
        if (safeDetails != null) {
            safeDetails.forEach((k, v) -> {
                if (k != null && v != null) d.put(k, SafeText.oneLine(SafeText.forName(k, String.valueOf(v))));
            });
        }
        this.details = Map.copyOf(d);
        this.reference = Objects.requireNonNull(reference, "reference");
        this.at = Objects.requireNonNull(at, "at");
    }

    /** A new failure with a fresh reference, now. */
    public static PlatformError of(PlatformCode code, Map<String, ?> safeDetails) {
        return new PlatformError(code, safeDetails, ReferenceId.next(), Instant.now());
    }

    public static PlatformError of(PlatformCode code) {
        return of(code, Map.of());
    }

    public PlatformCode code() {
        return code;
    }

    /** The safe details (secret-looking values already removed). */
    public Map<String, String> details() {
        return details;
    }

    public String reference() {
        return reference;
    }

    public Instant at() {
        return at;
    }

    /** What the person sees: the code's message, then the reference. */
    public String userMessage() {
        String m = code.userMessage();
        return (m.isEmpty() ? "Something could not be done." : m) + " (reference " + reference + ")";
    }

    /** The exact cause: the code's cause with its placeholders filled from the safe details. */
    public String cause() {
        return fillIn(code.cause(), details);
    }

    /**
     * The code's cause with its {@code {name}} placeholders filled from these details (secret-looking values
     * removed first, as in an error).
     */
    public static String fill(PlatformCode code, Map<String, ?> safeDetails) {
        Map<String, String> d = new LinkedHashMap<>();
        if (safeDetails != null) {
            safeDetails.forEach((k, v) -> {
                if (k != null && v != null) d.put(k, SafeText.oneLine(SafeText.forName(k, String.valueOf(v))));
            });
        }
        return fillIn(code.cause(), d);
    }

    private static String fillIn(String c, Map<String, String> details) {
        Matcher m = PLACEHOLDER.matcher(c);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = details.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group() : v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public String fix() {
        return code.fix();
    }

    /** One key=value log line: {@code code=… ref=… cause="…"} (quotes in the cause doubled away). */
    public String logLine() {
        return "code=" + code.code() + " ref=" + reference + " cause=\"" + cause().replace('"', '\'') + "\"";
    }

    @Override
    public String toString() {
        return "PlatformError[" + logLine() + "]";
    }
}
