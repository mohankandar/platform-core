package com.documentplatform.integration;

import com.documentplatform.integration.json.internal.Json;
import com.documentplatform.integration.profile.IntegrationProfile;
import com.documentplatform.integration.profile.ProfileLoader;
import com.documentplatform.integration.testkit.openapi.OpenApiSpec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

/** The made-up profiles and specs in test resources (no real API). */
final class ProfileFiles {

    static final String ACME = "acme-archive-v1";
    static final String DMS = "example-dms-v2";

    private ProfileFiles() {
    }

    static Path path(String file) {
        try {
            return Path.of(ProfileFiles.class.getClassLoader().getResource("profiles/" + file).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    static String text(String file) {
        try {
            return Files.readString(path(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static IntegrationProfile profile(String name) {
        return ProfileLoader.load(path(name + ".profile.json"));
    }

    static OpenApiSpec spec(String name) {
        return OpenApiSpec.parse(text(name + ".openapi.json"));
    }

    /** A profile's (or spec's) JSON changed by {@code change}, as text. */
    @SuppressWarnings("unchecked")
    static String changed(String file, Consumer<Map<String, Object>> change) {
        try {
            Map<String, Object> m = (Map<String, Object>) deepCopy(Json.object(text(file)));
            change.accept(m);
            return Json.write(m);
        } catch (Json.Invalid e) {
            throw new IllegalStateException(e);
        }
    }

    /** Mutable copy of parsed JSON (the parser's maps and lists are read-only). */
    static Object deepCopy(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            m.forEach((k, x) -> out.put((String) k, deepCopy(x)));
            return out;
        }
        if (v instanceof java.util.List<?> l) {
            java.util.List<Object> out = new java.util.ArrayList<>();
            l.forEach(x -> out.add(deepCopy(x)));
            return out;
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    static java.util.List<Object> arr(Object o) {
        return (java.util.List<Object>) o;
    }
}
