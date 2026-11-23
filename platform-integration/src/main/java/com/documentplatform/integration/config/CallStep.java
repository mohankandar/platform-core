package com.documentplatform.integration.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One configured call, from fixed building blocks: method, path template, header templates, a body of a fixed kind,
 * and which values to read from the answer. Steps run in order; a later step uses an earlier one's values as
 * {@code {step.<name>.<value>}}.
 *
 * @param name      the step's name ({@code create}, {@code upload}), letters, digits, '_' or '-'
 * @param method    the HTTP method
 * @param path      path template added to the base address (starts with '/'; placeholders only before any '?')
 * @param headers   header name → value template; sign-in headers come only from the sign-in bundle
 * @param body      NONE, JSON (from {@code bodyTemplate}), FILE (the document itself, as it is) or MULTIPART (one
 *                  JSON part from {@code bodyTemplate} and every document as a file part, in one call)
 * @param bodyTemplate the JSON template for a JSON or MULTIPART body, else null
 * @param repeat    ONCE, or EACH_DOCUMENT (once per document in the delivery)
 * @param read      value name → JSON pointer into a 2xx answer ({@code submissionId → /submissionId})
 * @param required  the value names in {@code read} that must be there; without one the answer is UNCLEAR
 * @param rules     this step's classification rules, tried before the integration's
 * @param multipart the part names and file name of a MULTIPART body, else null
 * @param integrity which read values hold the checksum and size the destination returns for the document sent
 *                  (per-document FILE steps), else null
 * @param accepted  "accepted, check later": the destination may answer 202 and is asked for the status later; only
 *                  on the last step, run once; else null
 */
public record CallStep(String name, HttpMethod method, String path, Map<String, String> headers, Body body,
                       String bodyTemplate, Repeat repeat, Map<String, String> read, Set<String> required,
                       List<Rule> rules, Multipart multipart, Integrity integrity, Accepted accepted) {

    public enum Body { NONE, JSON, FILE, MULTIPART }

    public enum Repeat { ONCE, EACH_DOCUMENT }

    /**
     * A multipart/form-data body: the JSON part and one file part per document.
     *
     * @param jsonPart         the JSON part's name ({@code metadata})
     * @param filePart         each file part's name ({@code files})
     * @param fileNameTemplate each file part's file name, a header template with {@code {doc.*}} placeholders
     */
    public record Multipart(String jsonPart, String filePart, String fileNameTemplate) {
        private static final Pattern PART = Pattern.compile("[A-Za-z0-9_.\\[\\]-]{1,64}");

        public Multipart {
            Objects.requireNonNull(fileNameTemplate, "fileNameTemplate");
            if (jsonPart == null || !PART.matcher(jsonPart).matches() || filePart == null
                    || !PART.matcher(filePart).matches()) {
                throw new IllegalArgumentException("Multipart part names are 1-64 of A-Z a-z 0-9 _ . [ ] -");
            }
        }
    }

    /**
     * The destination's own record of what it received, compared with what was sent.
     *
     * @param sha256Value the read value holding the SHA-256 the destination computed (hex), or null
     * @param sizeValue   the read value holding the size in bytes it received, or null
     */
    public record Integrity(String sha256Value, String sizeValue) {
        public Integrity {
            if (sha256Value == null && sizeValue == null) {
                throw new IllegalArgumentException("An integrity check compares a checksum, a size or both");
            }
        }
    }

    /**
     * "Accepted, check later": the destination may answer <b>202 Accepted</b> and do the work later. The attempt then
     * ends with outcome {@code ACCEPTED} and a {@code PendingStatus}; the app asks for the status later with
     * {@code CallEngine.checkStatus}, one call each time, on its own schedule. The engine never waits or retries.
     *
     * @param referencePointer where the 202 answer holds the status reference (a JSON pointer), or null to take the
     *                         {@code Location} header (an address on the same host, or a path)
     * @param statusMethod     the status call's method (GET unless the API says otherwise)
     * @param statusPath       the status call's path template, with {@code {status.reference}} (and the usual
     *                         placeholders: step values, {@code package.id}, {@code correlationId}); null when the
     *                         reference is the {@code Location} header (the status call goes there)
     * @param statePointer     where a status answer holds the state
     * @param doneValues       the states that mean done (the result is SENT)
     * @param failedValues     the states that mean failed (the result is REFUSED, {@code INT_STATUS_FAILED})
     * @param workingValues    the states that mean still working (ACCEPTED again); a 202 status answer is still
     *                         working too
     * @param defaultWait      when to check again if the destination sends no Retry-After (1 second to 24 hours)
     * @param read             value name → JSON pointer read from the "done" status answer, as {@code <step>.<name>}
     */
    public record Accepted(String referencePointer, HttpMethod statusMethod, String statusPath, String statePointer,
                           Set<String> doneValues, Set<String> failedValues, Set<String> workingValues,
                           Duration defaultWait, Map<String, String> read) {

        public static final Duration DEFAULT_WAIT = Duration.ofSeconds(30);
        public static final Duration MAX_WAIT = Duration.ofHours(24);
        /** The placeholder of the status reference in {@code statusPath}. */
        public static final String REFERENCE = "status.reference";

        public Accepted {
            Objects.requireNonNull(statusMethod, "statusMethod");
            Objects.requireNonNull(statePointer, "statePointer");
            doneValues = doneValues == null ? Set.of() : Set.copyOf(doneValues);
            failedValues = failedValues == null ? Set.of() : Set.copyOf(failedValues);
            workingValues = workingValues == null ? Set.of() : Set.copyOf(workingValues);
            defaultWait = defaultWait == null ? DEFAULT_WAIT : defaultWait;
            read = read == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(read));
        }

        /** The reference from a JSON pointer, a status path with {@code {status.reference}}, polled with GET. */
        public static Accepted byPointer(String referencePointer, String statusPath, String statePointer,
                                         Set<String> done, Set<String> failed, Set<String> working) {
            return new Accepted(referencePointer, HttpMethod.GET, statusPath, statePointer, done, failed, working,
                    DEFAULT_WAIT, Map.of());
        }

        /** The reference from the {@code Location} header, polled with GET there. */
        public static Accepted byLocation(String statePointer, Set<String> done, Set<String> failed,
                                          Set<String> working) {
            return new Accepted(null, HttpMethod.GET, null, statePointer, done, failed, working, DEFAULT_WAIT,
                    Map.of());
        }

        public Accepted withDefaultWait(Duration d) {
            return new Accepted(referencePointer, statusMethod, statusPath, statePointer, doneValues, failedValues,
                    workingValues, d, read);
        }

        public Accepted reading(String value, String pointer) {
            Map<String, String> r = new LinkedHashMap<>(read);
            r.put(value, pointer);
            return new Accepted(referencePointer, statusMethod, statusPath, statePointer, doneValues, failedValues,
                    workingValues, defaultWait, r);
        }
    }

    public static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");

    public CallStep {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(repeat, "repeat");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Step name is not valid");
        }
        headers = headers == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        read = read == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(read));
        required = required == null ? Set.of() : Set.copyOf(required);
        rules = rules == null ? List.of() : List.copyOf(rules);
        boolean templated = body == Body.JSON || body == Body.MULTIPART;
        if (templated != (bodyTemplate != null)) {
            throw new IllegalArgumentException("A JSON or multipart body needs a body template, and only they have one");
        }
        if ((body == Body.MULTIPART) != (multipart != null)) {
            throw new IllegalArgumentException("A multipart body needs its part names, and only it has them");
        }
    }

    /** Without "accepted, check later". */
    public CallStep(String name, HttpMethod method, String path, Map<String, String> headers, Body body,
                    String bodyTemplate, Repeat repeat, Map<String, String> read, Set<String> required,
                    List<Rule> rules, Multipart multipart, Integrity integrity) {
        this(name, method, path, headers, body, bodyTemplate, repeat, read, required, rules, multipart, integrity,
                null);
    }

    /** Without per-step rules, multipart or integrity check. */
    public CallStep(String name, HttpMethod method, String path, Map<String, String> headers, Body body,
                    String bodyTemplate, Repeat repeat, Map<String, String> read, Set<String> required) {
        this(name, method, path, headers, body, bodyTemplate, repeat, read, required, List.of(), null, null, null);
    }

    /** A step run once, no body, no headers, reading nothing. */
    public static CallStep of(String name, HttpMethod method, String path) {
        return new CallStep(name, method, path, Map.of(), Body.NONE, null, Repeat.ONCE, Map.of(), Set.of());
    }

    public CallStep withHeader(String header, String template) {
        Map<String, String> h = new LinkedHashMap<>(headers);
        h.put(header, template);
        return new CallStep(name, method, path, h, body, bodyTemplate, repeat, read, required, rules, multipart,
                integrity, accepted);
    }

    public CallStep withJsonBody(String template) {
        return new CallStep(name, method, path, headers, Body.JSON, template, repeat, read, required, rules, null,
                integrity, accepted);
    }

    public CallStep withFileBody() {
        return new CallStep(name, method, path, headers, Body.FILE, null, repeat, read, required, rules, null,
                integrity, accepted);
    }

    /** A multipart body: {@code jsonTemplate} as the JSON part, then every document as a file part. */
    public CallStep withMultipartBody(String jsonTemplate, Multipart parts) {
        return new CallStep(name, method, path, headers, Body.MULTIPART, jsonTemplate, repeat, read, required, rules,
                parts, integrity, accepted);
    }

    public CallStep eachDocument() {
        return new CallStep(name, method, path, headers, body, bodyTemplate, Repeat.EACH_DOCUMENT, read, required,
                rules, multipart, integrity, accepted);
    }

    /** Reads {@code value} from the answer at {@code pointer}. */
    public CallStep reading(String value, String pointer, boolean isRequired) {
        Map<String, String> r = new LinkedHashMap<>(read);
        r.put(value, pointer);
        Set<String> req = new java.util.HashSet<>(required);
        if (isRequired) req.add(value);
        return new CallStep(name, method, path, headers, body, bodyTemplate, repeat, r, req, rules, multipart,
                integrity, accepted);
    }

    public CallStep withRules(List<Rule> stepRules) {
        return new CallStep(name, method, path, headers, body, bodyTemplate, repeat, read, required, stepRules,
                multipart, integrity, accepted);
    }

    /** Compares the destination's checksum and size (read values, null to skip one) with what was sent. */
    public CallStep withIntegrity(String sha256Value, String sizeValue) {
        return new CallStep(name, method, path, headers, body, bodyTemplate, repeat, read, required, rules,
                multipart, new Integrity(sha256Value, sizeValue), accepted);
    }

    /** The destination may answer 202 Accepted; its status is checked later ({@code CallEngine.checkStatus}). */
    public CallStep acceptingLater(Accepted a) {
        return new CallStep(name, method, path, headers, body, bodyTemplate, repeat, read, required, rules,
                multipart, integrity, a);
    }
}
