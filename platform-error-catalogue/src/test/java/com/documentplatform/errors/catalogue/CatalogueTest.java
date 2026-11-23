package com.documentplatform.errors.catalogue;

import com.documentplatform.errors.Catalogue;
import com.documentplatform.errors.PlatformCode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P032 B1: every platform code enum implements {@link PlatformCode} with a message, cause and fix; codes are unique
 * across modules; the generated catalogue in {@code docs/errors} is up to date. Run with
 * {@code -Dplatform.errors.write=true} to regenerate it.
 */
class CatalogueTest {

    /** Enums named like codes that are internal (always mapped to a code enum before anyone sees them). */
    static final Set<String> INTERNAL = Set.of(
            "com.documentplatform.licensing.SignedDocument$Problem");
    /** A code enum's name: …Code, …Refusal, …Error, …Problem, …Reason, or the Result of an …Outcome. */
    static final Pattern CODE_NAME = Pattern.compile(".*(Code|Refusal|Error|Problem|Reason|Outcome\\$Result)$");

    static List<Class<?>> enums;
    static Catalogue catalogue;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void scan() throws Exception {
        enums = platformEnums();
        List<Class<? extends PlatformCode>> codes = new ArrayList<>();
        for (Class<?> e : enums) {
            if (PlatformCode.class.isAssignableFrom(e)) codes.add((Class<? extends PlatformCode>) e);
        }
        catalogue = Catalogue.of(codes);
    }

    @Test
    void everyCodeEnumImplementsThePlatformCodeShape() {
        List<String> missing = new ArrayList<>();
        for (Class<?> e : enums) {
            if (CODE_NAME.matcher(e.getName()).matches() && !INTERNAL.contains(e.getName())
                    && !PlatformCode.class.isAssignableFrom(e)) {
                missing.add(e.getName());
            }
        }
        assertEquals(List.of(), missing, "code enums that do not implement PlatformCode");
        assertTrue(catalogue.entries().size() > 550, "found " + catalogue.entries().size() + " codes");
    }

    @Test
    void everyCodeHasAMessageACauseAndAFixAndIsUniqueAcrossModules() {
        assertEquals(List.of(), catalogue.problems());
    }

    @Test
    void theGeneratedCatalogueIsUpToDate() throws IOException {
        Path docs = Path.of(System.getProperty("platform.errors.docs", "../docs/errors"));
        Path md = docs.resolve("platform-error-codes.md");
        Path json = docs.resolve("platform-error-codes.json");
        if (Boolean.getBoolean("platform.errors.write")) {
            Files.createDirectories(docs);
            Files.writeString(md, catalogue.markdown(), StandardCharsets.UTF_8);
            Files.writeString(json, catalogue.json(), StandardCharsets.UTF_8);
        }
        assertTrue(Files.exists(md) && Files.exists(json), "the catalogue is missing: run with "
                + "-Dplatform.errors.write=true");
        String stale = "docs/errors is stale: regenerate with mvn -pl platform-error-catalogue -am verify "
                + "-Dplatform.errors.write=true (or set the property on any full build) and commit it";
        assertEquals(catalogue.markdown(), Files.readString(md, StandardCharsets.UTF_8).replace("\r\n", "\n"), stale);
        assertEquals(catalogue.json(), Files.readString(json, StandardCharsets.UTF_8).replace("\r\n", "\n"), stale);
    }

    /** Every enum under com.documentplatform on the test classpath (the platform modules' classes and jars). */
    static List<Class<?>> platformEnums() throws IOException, URISyntaxException {
        Set<String> names = new TreeSet<>();
        for (URL root : Collections.list(CatalogueTest.class.getClassLoader().getResources("com/documentplatform"))) {
            if (root.getProtocol().equals("jar")) {
                JarURLConnection c = (JarURLConnection) root.openConnection();
                try (JarFile jar = c.getJarFile()) {
                    jar.stream().map(e -> e.getName())
                            .filter(n -> n.startsWith("com/documentplatform/") && n.endsWith(".class"))
                            .forEach(n -> names.add(n.substring(0, n.length() - 6).replace('/', '.')));
                }
            } else if (root.getProtocol().equals("file")) {
                Path dir = Path.of(root.toURI());
                Path base = dir.getParent().getParent();
                try (Stream<Path> s = Files.walk(dir)) {
                    s.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                        String rel = base.relativize(p).toString().replace('\\', '/');
                        names.add(rel.substring(0, rel.length() - 6).replace('/', '.'));
                    });
                }
            }
        }
        List<Class<?>> out = new ArrayList<>();
        for (String n : names) {
            if (n.matches(".*\\$\\d+.*") || n.contains(".catalogue.")) continue;
            try {
                Class<?> c = Class.forName(n, false, CatalogueTest.class.getClassLoader());
                if (c.isEnum()) out.add(c);
            } catch (Throwable notLoadable) {
                // optional dependencies of a class are not on this classpath: not a code enum
            }
        }
        return out;
    }
}
