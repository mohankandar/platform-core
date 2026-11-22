package com.documentplatform.integration.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One configured call, from fixed building blocks: method, path template, header templates, a body of a fixed kind,
 * and which values to read from the answer. Steps run in order; a later step uses an earlier one's values as
 * {@code {step.<name>.<value>}}.
 *
 * @param name     the step's name ({@code create}, {@code upload}), letters, digits, '_' or '-'
 * @param method   the HTTP method
 * @param path     path template added to the base address (starts with '/'; placeholders only before any '?')
 * @param headers  header name → value template; sign-in headers come only from the sign-in bundle
 * @param body     NONE, JSON (from {@code bodyTemplate}) or FILE (the document itself, as it is)
 * @param bodyTemplate the JSON template for a JSON body, else null
 * @param repeat   ONCE, or EACH_DOCUMENT (once per document in the delivery)
 * @param read     value name → JSON pointer into a 2xx answer ({@code submissionId → /submissionId})
 * @param required the value names in {@code read} that must be there; without one the answer is UNCLEAR
 */
public record CallStep(String name, HttpMethod method, String path, Map<String, String> headers, Body body,
                       String bodyTemplate, Repeat repeat, Map<String, String> read, Set<String> required) {

    public enum Body { NONE, JSON, FILE }

    public enum Repeat { ONCE, EACH_DOCUMENT }

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
        if ((body == Body.JSON) != (bodyTemplate != null)) {
            throw new IllegalArgumentException("A JSON body needs a body template, and only a JSON body has one");
        }
    }

    /** A step run once, no body, no headers, reading nothing. */
    public static CallStep of(String name, HttpMethod method, String path) {
        return new CallStep(name, method, path, Map.of(), Body.NONE, null, Repeat.ONCE, Map.of(), Set.of());
    }

    public CallStep withHeader(String header, String template) {
        Map<String, String> h = new LinkedHashMap<>(headers);
        h.put(header, template);
        return new CallStep(name, method, path, h, body, bodyTemplate, repeat, read, required);
    }

    public CallStep withJsonBody(String template) {
        return new CallStep(name, method, path, headers, Body.JSON, template, repeat, read, required);
    }

    public CallStep withFileBody() {
        return new CallStep(name, method, path, headers, Body.FILE, null, repeat, read, required);
    }

    public CallStep eachDocument() {
        return new CallStep(name, method, path, headers, body, bodyTemplate, Repeat.EACH_DOCUMENT, read, required);
    }

    /** Reads {@code value} from the answer at {@code pointer}. */
    public CallStep reading(String value, String pointer, boolean isRequired) {
        Map<String, String> r = new LinkedHashMap<>(read);
        r.put(value, pointer);
        Set<String> req = new java.util.HashSet<>(required);
        if (isRequired) req.add(value);
        return new CallStep(name, method, path, headers, body, bodyTemplate, repeat, r, req);
    }
}
