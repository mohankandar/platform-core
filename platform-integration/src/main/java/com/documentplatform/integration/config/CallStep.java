package com.documentplatform.integration.config;

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
 */
public record CallStep(String name, HttpMethod method, String path, Map<String, String> headers, Body body,
                       String bodyTemplate, Repeat repeat, Map<String, String> read, Set<String> required,
                       List<Rule> rules, Multipart multipart, Integrity integrity) {

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

    /** Without per-step rules, multipart or integrity check. */
    public CallStep(String name, HttpMethod method, String path, Map<String, String> headers, Body body,
                    String bodyTemplate, Repeat repeat, Map<String, String> read, Set<String> required) {
        this(name, method, path, headers, body, bodyTemplate, repeat, read, required, List.of(), null, null);
    }

    /** A step run once, no body, no headers, reading nothing. */
    public static CallStep of(String name, HttpMethod method, String path) {
        return new CallStep(name, method, path, Map.of(), Body.NONE, null, Repeat.ONCE, Map.of(), Set.of());
    }

    public CallStep withHeader(String header, String template) {
        Map<String, String> h = new LinkedHashMap<>(headers);
        h.put(header, template);
        return new CallStep(name, method, path, h, body, bodyTemplate, repeat, read, required, rules, multipart,
                integrity);
    }

    public CallStep withJsonBody(String template) {
        return new CallStep(name, method, path, headers, Body.JSON, template, repeat, read, required, rules, null,
                integrity);
    }

    public CallStep withFileBody() {
        return new CallStep(name, method, path, headers, Body.FILE, null, repeat, read, required, rules, null,
                integrity);
    }

    /** A multipart body: {@code jsonTemplate} as the JSON part, then every document as a file part. */
    public CallStep withMultipartBody(String jsonTemplate, Multipart parts) {
        return new CallStep(name, method, path, headers, Body.MULTIPART, jsonTemplate, repeat, read, required, rules,
                parts, integrity);
    }

    public CallStep eachDocument() {
        return new CallStep(name, method, path, headers, body, bodyTemplate, Repeat.EACH_DOCUMENT, read, required,
                rules, multipart, integrity);
    }

    /** Reads {@code value} from the answer at {@code pointer}. */
    public CallStep reading(String value, String pointer, boolean isRequired) {
        Map<String, String> r = new LinkedHashMap<>(read);
        r.put(value, pointer);
        Set<String> req = new java.util.HashSet<>(required);
        if (isRequired) req.add(value);
        return new CallStep(name, method, path, headers, body, bodyTemplate, repeat, r, req, rules, multipart,
                integrity);
    }

    public CallStep withRules(List<Rule> stepRules) {
        return new CallStep(name, method, path, headers, body, bodyTemplate, repeat, read, required, stepRules,
                multipart, integrity);
    }

    /** Compares the destination's checksum and size (read values, null to skip one) with what was sent. */
    public CallStep withIntegrity(String sha256Value, String sizeValue) {
        return new CallStep(name, method, path, headers, body, bodyTemplate, repeat, read, required, rules,
                multipart, new Integrity(sha256Value, sizeValue));
    }
}
