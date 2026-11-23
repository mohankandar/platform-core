package com.documentplatform.errors.catalogue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * xc-public-api-1 (P041): the public API rule of {@code docs/design/public-api.md}. Types in a package whose name
 * contains {@code .internal} are not for apps, so no install guide or design doc names one; a testkit uses only public
 * types, or the internals of the module it tests.
 */
class PublicApiTest {

    static final Path ROOT = Path.of("..");
    static final Pattern IMPORT = Pattern.compile("^import\\s+(static\\s+)?(com\\.documentplatform\\.[\\w.]+?)(\\.\\*)?;",
            Pattern.MULTILINE);

    /** Every main source type: its fully qualified name, and the module whose src/main/java holds it. */
    static Map<String, String> mainTypes() throws IOException {
        Map<String, String> out = new HashMap<>();
        try (Stream<Path> modules = Files.list(ROOT)) {
            for (Path module : modules.filter(p -> p.getFileName().toString().startsWith("platform-")).toList()) {
                Path src = module.resolve("src/main/java");
                if (!Files.isDirectory(src)) continue;
                try (Stream<Path> files = Files.walk(src)) {
                    files.filter(p -> p.toString().endsWith(".java") && !p.getFileName().toString().equals("package-info.java"))
                            .forEach(p -> {
                                String rel = src.relativize(p).toString().replace('\\', '/');
                                out.put(rel.substring(0, rel.length() - 5).replace('/', '.'),
                                        module.getFileName().toString());
                            });
                }
            }
        }
        return out;
    }

    static boolean internal(String fqn) {
        return fqn.contains(".internal.");
    }

    @Test
    void noInstallGuideOrDesignDocNamesAnInternalType() throws IOException {
        Map<String, String> types = mainTypes();
        Set<String> publicSimple = new HashSet<>();
        Map<String, String> internalSimple = new HashMap<>();
        for (String fqn : types.keySet()) {
            String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
            if (internal(fqn)) internalSimple.put(simple, fqn);
            else publicSimple.add(simple);
        }
        List<String> offenders = new ArrayList<>();
        for (String dir : List.of("docs/install", "docs/design")) {
            try (Stream<Path> docs = Files.list(ROOT.resolve(dir))) {
                for (Path doc : docs.filter(p -> p.toString().endsWith(".md")).toList()) {
                    String text = Files.readString(doc, StandardCharsets.UTF_8);
                    for (String fqn : types.keySet()) {
                        if (internal(fqn) && text.contains(fqn)) offenders.add(dir + "/" + doc.getFileName() + ": " + fqn);
                    }
                    Matcher m = Pattern.compile("`([A-Z][A-Za-z0-9]+)(?:[.#(][^`]*)?`").matcher(text);
                    while (m.find()) {
                        String simple = m.group(1);
                        if (internalSimple.containsKey(simple) && !publicSimple.contains(simple)) {
                            offenders.add(dir + "/" + doc.getFileName() + ": `" + simple + "` ("
                                    + internalSimple.get(simple) + ")");
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), new ArrayList<>(new TreeSet<>(offenders)),
                "docs name types of internal packages; name the public type instead, or keep the type public");
        assertTrue(types.size() > 400, "read " + types.size() + " types");
    }

    @Test
    void testkitsUseOnlyPublicTypesOrTheInternalsOfTheModuleTheyTest() throws IOException {
        Map<String, String> types = mainTypes();
        Map<String, String> internalPackages = new HashMap<>();
        types.forEach((fqn, module) -> {
            if (internal(fqn)) internalPackages.put(fqn.substring(0, fqn.lastIndexOf('.')), module);
        });
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> modules = Files.list(ROOT)) {
            for (Path kit : modules.filter(p -> p.getFileName().toString().endsWith("-testkit")).toList()) {
                String tested = kit.getFileName().toString().replaceFirst("-testkit$", "");
                Path src = kit.resolve("src/main/java");
                if (!Files.isDirectory(src)) continue;
                try (Stream<Path> files = Files.walk(src)) {
                    for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                        Matcher m = IMPORT.matcher(Files.readString(f, StandardCharsets.UTF_8));
                        while (m.find()) {
                            String name = m.group(2);
                            String owner = types.get(name);
                            if (owner == null) owner = internalPackages.get(name);
                            if (owner == null && name.contains(".")) {
                                owner = types.get(name.substring(0, name.lastIndexOf('.'))); // static import
                            }
                            if (owner != null && internal(name + ".") && !owner.equals(tested)) {
                                offenders.add(kit.getFileName() + "/" + src.relativize(f) + " imports " + name
                                        + " (internal to " + owner + ")");
                            }
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), offenders, "a testkit uses another module's internals");
    }

    @Test
    void theRuleIsWrittenDown() throws IOException {
        String rule = Files.readString(ROOT.resolve("docs/design/public-api.md"), StandardCharsets.UTF_8);
        assertTrue(rule.contains("may change in any release and are not supported for apps"), rule);
        String claude = Files.readString(ROOT.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
        assertTrue(claude.contains("## Public API") && claude.contains("`.internal`"), "CLAUDE.md states the rule");
    }
}
