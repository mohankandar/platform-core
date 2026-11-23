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
 * whole word or as a part of an identifier ({@code x_y}, {@code X-Y}, {@code xY}), in any text file of the repository
 * (code, data, root Markdown, install guides, the catalogue, release notes, scripts, workflows). {@code docs/work/} and
 * {@code docs/design/} are history and examples and are not checked. The words and the old product package names are assembled here, so this
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

    /** xc-independence-2 (P040): every text file outside the history folders is checked. */
    @Test
    void theGuardCoversEveryTextFileOutsideTheHistoryFolders() {
        for (String p : List.of("README.md", "ANY-NOTES.md", "docs/install/identity.md", "docs/errors/code-names.txt",
                "docs/releases/platform-core-v0.6.0.md", "scripts/build-release.sh", "scripts/build-release.ps1",
                ".github/workflows/ci.yml", "platform-errors/pom.xml", "platform-x/src/main/resources/db/a.sql")) {
            assertTrue(checked(Path.of(p)), p);
        }
        for (String p : List.of("docs/work/P001.md", "docs/design/setup.md", "platform-x/target/x.txt", ".git/HEAD")) {
            org.junit.jupiter.api.Assertions.assertFalse(checked(Path.of(p)), p);
        }
    }

    /**
     * The places the rule covers (P040, xc-independence-2): every text file of the repository, root Markdown files,
     * docs/install, docs/errors, docs/releases, scripts and workflow files included, except docs/work/ and
     * docs/design/ (history and examples), build output and local tool folders. Binary files are skipped when read.
     */
    static boolean checked(Path relative) {
        String p = relative.toString().replace('\\', '/');
        return !(p.startsWith(".git/") || p.contains("/target/") || p.startsWith("target/") || p.contains("/node_modules/")
                || p.startsWith(".claude/") || p.startsWith(".idea/") || p.startsWith(".vscode/")
                || p.startsWith("docs/work/") || p.startsWith("docs/design/"));
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
