package com.documentplatform.crypto.vault;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The Vault policies the design (section 7) and the install guide (section 1) show are exactly the ones the tests run
 * against ({@code src/test/resources/vault/crypto-*.hcl}): every {@code path} rule with its capabilities, under the
 * policy's own heading, nothing more and nothing less. Layout (one line or several, spacing) may differ.
 */
class PolicyTextInDocsTest {

    private static final Path RESOURCES = Path.of("src", "test", "resources", "vault");
    private static final Pattern RULE = Pattern.compile("path\\s+\"([^\"]+)\"\\s*\\{\\s*capabilities\\s*=\\s*\\[([^]]*)]"
            + "\\s*}");
    private static final Pattern HEADING = Pattern.compile("^#\\s*(platform-crypto-[a-z]+)(:.*)?$");

    @Test
    void theDesignShowsExactlyThePoliciesTested() throws IOException {
        assertEquals(tested(), shown(Path.of("..", "docs", "design", "crypto.md")),
                "docs/design/crypto.md section 7 must show the policies in src/test/resources/vault/");
    }

    @Test
    void theInstallGuideShowsExactlyThePoliciesTested() throws IOException {
        assertEquals(tested(), shown(Path.of("..", "docs", "install", "crypto.md")),
                "docs/install/crypto.md section 1 must show the policies in src/test/resources/vault/");
    }

    /** Policy name (from the file name crypto-x.hcl: platform-crypto-x) → its rules. */
    private static Map<String, List<String>> tested() throws IOException {
        Map<String, List<String>> policies = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(RESOURCES)) {
            for (Path file : files.filter(f -> f.getFileName().toString().matches("crypto-[a-z]+\\.hcl")).sorted()
                    .toList()) {
                String name = "platform-" + file.getFileName().toString().replace(".hcl", "");
                policies.put(name, rules(Files.readString(file)));
            }
        }
        assertEquals(3, policies.size(), "the app, set-up and offboard policies: " + policies.keySet());
        policies.values().forEach(r -> assertFalse(r.isEmpty()));
        return policies;
    }

    /** The doc's {@code hcl} blocks, split at the {@code # platform-crypto-…} headings. */
    private static Map<String, List<String>> shown(Path doc) throws IOException {
        Map<String, StringBuilder> sections = new LinkedHashMap<>();
        boolean inHcl = false;
        StringBuilder current = null;
        for (String raw : Files.readAllLines(doc)) {
            String line = raw.strip();
            if (line.startsWith("```")) {
                inHcl = !inHcl && line.equals("```hcl");
                current = null;
                continue;
            }
            if (!inHcl) {
                continue;
            }
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                current = sections.computeIfAbsent(heading.group(1), k -> new StringBuilder());
            } else if (!line.startsWith("#") && current != null) {
                current.append(line).append('\n');
            }
        }
        Map<String, List<String>> policies = new LinkedHashMap<>();
        sections.forEach((name, text) -> policies.put(name, rules(text.toString())));
        return policies;
    }

    private static List<String> rules(String hcl) {
        List<String> rules = new ArrayList<>();
        Matcher m = RULE.matcher(hcl.replaceAll("(?m)^\\s*#.*$", ""));
        while (m.find()) {
            rules.add("path \"" + m.group(1) + "\" { capabilities = [" + m.group(2).replaceAll("\\s+", " ").strip()
                    + "] }");
        }
        return rules;
    }
}
