package com.documentplatform.errors;

import java.net.URL;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The catalogue of codes: one entry per code (module, family, code, kind, message, cause, fix, support note, HTTP
 * status), written as Markdown and JSON. Used for the platform's {@code docs/errors/platform-error-codes.*}; an app can
 * build its own from its enums the same way.
 */
public final class Catalogue {

    /** One code. */
    public record Entry(String module, String family, String code, PlatformCode.Kind kind, String message,
                        String cause, String fix, String support, Integer http) {
    }

    private final List<Entry> entries;

    private Catalogue(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /** The catalogue of these enums, sorted by module, family and code. */
    public static Catalogue of(List<Class<? extends PlatformCode>> enums) {
        List<Entry> out = new ArrayList<>();
        for (Class<? extends PlatformCode> e : enums) {
            if (!e.isEnum()) throw new IllegalArgumentException(e.getName() + " is not an enum");
            String module = moduleOf(e);
            for (PlatformCode c : e.getEnumConstants()) {
                out.add(new Entry(module, c.family(), c.code(), c.kind(), c.userMessage(), c.cause(), c.fix(),
                        c.supportNote(), c.httpStatus().isPresent() ? c.httpStatus().getAsInt() : null));
            }
        }
        out.sort(Comparator.comparing(Entry::module).thenComparing(Entry::family).thenComparing(Entry::code));
        return new Catalogue(out);
    }

    public List<Entry> entries() {
        return entries;
    }

    /**
     * What is wrong with the catalogue: a code without a message, cause or fix; a code listed twice in one family; a
     * code name used in two modules.
     */
    public List<String> problems() {
        List<String> p = new ArrayList<>();
        Map<String, String> moduleOfCode = new HashMap<>();
        Map<String, Boolean> seen = new HashMap<>();
        for (Entry e : entries) {
            String id = e.module() + "/" + e.family() + "/" + e.code();
            if (seen.put(id, true) != null) p.add(id + ": listed twice");
            if (e.message().isBlank()) p.add(id + ": no message");
            if (e.cause().isBlank()) p.add(id + ": no cause");
            if (e.fix().isBlank()) p.add(id + ": no fix");
            String other = moduleOfCode.putIfAbsent(e.code(), e.module());
            if (other != null && !other.equals(e.module())) {
                p.add(e.code() + ": used in two modules (" + other + ", " + e.module() + ")");
            }
        }
        return p;
    }

    /** The Markdown catalogue: one table per module. */
    public String markdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Platform error codes\n\n")
                .append("Generated from the code enums (`PlatformCode`, module `platform-errors`); do not edit by hand. ")
                .append("Regenerate with `mvn -pl platform-error-catalogue verify -Dplatform.errors.write=true`. ")
                .append("The shape and the API error format: `docs/design/error-codes.md`.\n\n")
                .append("Kinds: REFUSED (the request is not allowed), FAILED (we or a dependency failed), NEEDS_PERSON ")
                .append("(sent to a person for review), INFO (a result). ").append(entries.size()).append(" codes.\n");
        String module = null;
        for (Entry e : entries) {
            if (!e.module().equals(module)) {
                module = e.module();
                sb.append("\n## ").append(module).append("\n\n")
                        .append("| Code | Family | Kind | Message (to the person) | Cause (for IT) | Fix (what IT does) |")
                        .append(" Support note |\n|---|---|---|---|---|---|---|\n");
            }
            sb.append("| `").append(e.code()).append("` | ").append(e.family()).append(" | ").append(e.kind())
                    .append(e.http() == null ? "" : " (HTTP " + e.http() + ")").append(" | ").append(cell(e.message()))
                    .append(" | ").append(cell(e.cause())).append(" | ").append(cell(e.fix())).append(" | ")
                    .append(cell(e.support())).append(" |\n");
        }
        return sb.toString();
    }

    /** The JSON catalogue: {"codes":[{module, family, code, kind, message, cause, fix, support, http}, …]}. */
    public String json() {
        StringBuilder sb = new StringBuilder("{\n  \"format\": \"platform-error-codes/1\",\n  \"codes\": [\n");
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            sb.append("    {\"module\": ").append(q(e.module())).append(", \"family\": ").append(q(e.family()))
                    .append(", \"code\": ").append(q(e.code())).append(", \"kind\": ").append(q(e.kind().name()))
                    .append(", \"message\": ").append(q(e.message())).append(", \"cause\": ").append(q(e.cause()))
                    .append(", \"fix\": ").append(q(e.fix())).append(", \"support\": ").append(q(e.support()));
            if (e.http() != null) sb.append(", \"http\": ").append(e.http());
            sb.append(i + 1 < entries.size() ? "},\n" : "}\n");
        }
        return sb.append("  ]\n}\n").toString();
    }

    /** The module a class comes from: {@code platform-x} of {@code …/platform-x/target/classes} or of its jar. */
    public static String moduleOf(Class<?> c) {
        CodeSource cs = c.getProtectionDomain().getCodeSource();
        if (cs == null || cs.getLocation() == null) return "unknown";
        URL u = cs.getLocation();
        String path = u.getPath().replace('\\', '/');
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.endsWith("/target/test-classes")) path = path.substring(0, path.length() - "test-classes".length()) + "classes";
        if (path.endsWith("/target/classes")) {
            String dir = path.substring(0, path.length() - "/target/classes".length());
            return dir.substring(dir.lastIndexOf('/') + 1);
        }
        String file = path.substring(path.lastIndexOf('/') + 1);
        return file.replaceAll("-\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?\\.jar$", "").replaceAll("\\.jar$", "");
    }

    private static String cell(String s) {
        return s.replace("|", "\\|").replace("\n", " ");
    }

    private static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
