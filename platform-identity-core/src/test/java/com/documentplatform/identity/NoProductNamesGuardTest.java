package com.documentplatform.identity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * platform-core never names, depends on or assumes any product. The build fails if a product's name appears, as a
 * whole word or as a part of an identifier ({@code x_y}, {@code X-Y}, {@code xY}), in any Java, SQL, JSON, XML, YAML or
 * properties file, in CLAUDE.md, a README, or the install guides. {@code docs/work/} and {@code docs/design/} are
 * history and examples and are not checked. The words and the old product package names are assembled here, so this
 * file passes itself.
 */
class NoProductNamesGuardTest {

    private static final Set<String> WORDS = Set.of("lexa" + "vault", "ve" + "ro", "s" + "dv");
    private static final List<String> PACKAGES = List.of("com." + "securedoc", "com." + "s" + "dv.");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9]+");

    @Test
    void noProductNameInCodeDataOrInstallGuides() throws IOException {
        Path root = Path.of("").toAbsolutePath().getParent();
        assertTrue(Files.exists(root.resolve("pom.xml")), "expected to run from a module of the library: " + root);
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> checked(root.relativize(p)))
                    .forEach(p -> {
                        List<String> lines = lines(p);
                        for (int i = 0; i < lines.size(); i++) {
                            String hit = hit(lines.get(i));
                            if (hit != null) offenders.add(root.relativize(p).toString().replace('\\', '/') + ":" + (i + 1)
                                    + " (" + hit + ")");
                        }
                    });
        }
        if (!offenders.isEmpty()) fail("Product names found in platform-core: " + offenders);
    }

    @Test
    void theGuardFindsWordsAndIdentifierParts() {
        for (String s : List.of("for " + "Lexa" + "Vault only", "S" + "DV-SYS-018", "s" + "dv_tenant_id",
                "s" + "dvIngestion", "the " + "VE" + "RO profile", "com." + "securedoc.api")) {
            assertTrue(hit(s) != null, s);
        }
        for (String s : List.of("every overt average", "SVD", "devops", "verification", "Veronica wrote this")) {
            assertTrue(hit(s) == null, s);
        }
    }

    /** The file types and places the rule covers. */
    static boolean checked(Path relative) {
        String p = relative.toString().replace('\\', '/');
        if (p.startsWith(".git/") || p.contains("/target/") || p.startsWith("target/") || p.contains("/node_modules/")
                || p.startsWith("docs/work/") || p.startsWith("docs/design/")) {
            return false;
        }
        String name = relative.getFileName().toString();
        return name.matches(".*\\.(java|sql|json|xml|yml|yaml|properties)") || name.equals("CLAUDE.md")
                || name.startsWith("README") || p.startsWith("docs/install/");
    }

    /** The product word in this text, or null. */
    static String hit(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        for (String pkg : PACKAGES) {
            if (lower.contains(pkg)) return pkg;
        }
        Matcher m = TOKEN.matcher(line);
        while (m.find()) {
            String t = m.group();
            if (WORDS.contains(t.toLowerCase(Locale.ROOT))) return t;
            // camelCase and ACRONYMWord parts: fooBar -> foo, Bar; ABCWord -> ABC, Word
            for (String part : t.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                    .split(" ")) {
                if (WORDS.contains(part.toLowerCase(Locale.ROOT))) return t;
            }
        }
        return null;
    }

    private static List<String> lines(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (java.nio.charset.MalformedInputException binary) {
            return List.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
