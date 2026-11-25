package com.documentplatform.errors.catalogue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P005-2 (notifications-78): once a release ships, its SQL files are never edited again; a change to a schema is a new
 * file with the next version number. Every {@code src/main/resources/db/platform/**}{@code .sql} file of every module
 * is pinned here by the SHA-256 of its text (line ends read as LF, so a Windows checkout with CRLF gives the same
 * value). An edited or removed pinned file fails the build; a new file fails until it is pinned.
 */
class ReleasedSqlFilesTest {

    /** Path from the repository root → SHA-256 of the text with LF line ends. */
    static final Map<String, String> PINNED = new TreeMap<>(Map.ofEntries(
            Map.entry("platform-erase-log/src/main/resources/db/platform/erase-log/V3__erase_log_search_path.sql",
                    "6015d24515ecd352d5b27e9abacbf7b959a60f254633e4129b974bdce598fc8a"),
            Map.entry("platform-fields/src/main/resources/db/platform/fields/V2__fields_search_path.sql",
                    "31e5f1312b325e77e0a0ae6f708ed7124a65f5c8c8195de9d435f9a0244456da"),
            Map.entry("platform-identity-jdbc/src/main/resources/db/platform/identity/V5__identity_history_and_search_path.sql",
                    "535fa07ec564a18d588d258df28349a076b0536d91d400937ba408b54ad4a0df"),
            Map.entry("platform-identity-jdbc/src/main/resources/db/platform/identity/V6__test_sign_in.sql",
                    "bd92cac182cfdbc15aa122769531ae3f425611961274c89efe9cc9750b9cd282"),
            Map.entry("platform-integration/src/main/resources/db/platform/integration/V3__integration_search_path.sql",
                    "5619da5e3a9a3d017812fe37733923602a053da45d2d12643f09b724f2f225be"),
            Map.entry("platform-licensing/src/main/resources/db/platform/licensing/V2__licensing_clock_and_versions.sql",
                    "3cc774b2b1c237c5b37d49338327b4874bd434d60be4fa36c56265ea2a930206"),
            Map.entry("platform-model-governance/src/main/resources/db/platform/modelgov/V5__modelgov_gate_and_history.sql",
                    "846f6c4cc022fe3e7cb9207566e6f65c08c33587d704298ef77a3a815db065ba"),
            Map.entry("platform-erase-log/src/main/resources/db/platform/erase-log/V1__erase_log.sql",
                    "2d5572126da22cfa66abc15fb58e8325eb7879d6f4352e9c1894631d4a1ebf5b"),
            Map.entry("platform-fields/src/main/resources/db/platform/fields-optional/partial-search.sql",
                    "9434740f010c81cfadca450f3dcc07995bc9f367e76cc442e85cdc10638c3419"),
            Map.entry("platform-fields/src/main/resources/db/platform/fields/V1__fields.sql",
                    "98c5a7381c9d678b2d02dcdb07523635e7be3c7356bad6855745fd16ef5607ee"),
            Map.entry("platform-identity-jdbc/src/main/resources/db/platform/identity/V1__identity.sql",
                    "d6c33b77db8495d21ab068306a9a6665aceff574d1e3eec6ac14e8b62b18505f"),
            Map.entry("platform-identity-jdbc/src/main/resources/db/platform/identity/V2__machine_binding.sql",
                    "7f1f0e6c07a8aad41a12003104213408605b4e1aac1fb37704cd16768e2f4b7a"),
            Map.entry("platform-identity-jdbc/src/main/resources/db/platform/identity/V3__grant_history.sql",
                    "5395ddefb890c74743952d51009df418e113a12ac86fecded45537289df84366"),
            Map.entry("platform-identity-jdbc/src/main/resources/db/platform/identity/V4__platform_grants.sql",
                    "bcabbd5e3880459c88d27877399e28ff9421c7875c2c874ff4ffe290dd1c03ba"),
            Map.entry("platform-integration/src/main/resources/db/platform/integration/V1__integration.sql",
                    "0ed514aa1ec39826173d59fa139b350f62668cdc657f776c3585c29ca4bd7579"),
            Map.entry("platform-integration/src/main/resources/db/platform/integration/V2__credentials_callbacks.sql",
                    "49d57dcdc82f3cb8ff51d44c0d716ff6b0b7410379ef6056382d643bb619557d"),
            Map.entry("platform-licensing/src/main/resources/db/platform/licensing/V1__licensing.sql",
                    "b3372f147ba7e696d38192461d6d8e8c23e4762a9e316c8faec803b13ed5219a"),
            // New in 0.7.0 (P044, SEC-49); pinned now so it is never edited after it ships.
            Map.entry("platform-erase-log/src/main/resources/db/platform/erase-log/V2__erase_log_replay_guard.sql",
                    "cdd7e0a922579ad13b55e60433f6a5590ef766cca9789fad96659d737978d1ff"),
            Map.entry("platform-model-governance/src/main/resources/db/platform/modelgov/V1__modelgov.sql",
                    "c9750adf9f1262d0dc09e0a0235a1b3f8472e57cd632784655ac8e4064184496"),
            Map.entry("platform-model-governance/src/main/resources/db/platform/modelgov/V2__modelgov_monitoring.sql",
                    "9196eaa9760b8a37fb6fa5bb182b602d988fff43ff3f9e874025920edfc3470a"),
            // New in this release (not in a release tag yet); pinned now so it is never edited after it ships.
            Map.entry("platform-model-governance/src/main/resources/db/platform/modelgov/V3__modelgov_purge_guard.sql",
                    "479f9446045617b0029bc1f36d606c0d4b26ad26114344233bc238de8800893f"),
            Map.entry("platform-model-governance/src/main/resources/db/platform/modelgov/V4__modelgov_validation_location.sql",
                    "37f64a5ce6163dbfd602a210e9e66d6aa4c66f67dc9dba0b8dba08dbdbad884f"),
            Map.entry("platform-notifications/src/main/resources/db/platform/notifications/V1__notifications.sql",
                    "0bc9fbe0a3aae64af373c86a7aaf94f1a436310a78a8484bf3ef33d95160286e")));

    /** The repository root: this module's parent folder (as {@code CatalogueTest} reads {@code ../pom.xml}). */
    static Path root() {
        Path root = Path.of("..").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(root.resolve("pom.xml")), "repository root not found at " + root);
        return root;
    }

    /** Every module's SQL files under {@code src/main/resources/db/platform}, by path from the root. */
    static Map<String, String> found(Path root) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                Path db = module.resolve("src/main/resources/db/platform");
                if (!Files.isDirectory(db)) continue;
                try (Stream<Path> files = Files.walk(db)) {
                    for (Path f : files.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".sql")).toList()) {
                        out.put(root.relativize(f).toString().replace('\\', '/'), sha256(f));
                    }
                }
            }
        }
        return out;
    }

    /** SHA-256 of the file's text with CRLF read as LF. */
    static String sha256(Path f) throws IOException {
        String text = Files.readString(f, StandardCharsets.UTF_8).replace("\r\n", "\n");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void releasedSqlFilesAreNeverEditedOrRemovedAndNewOnesArePinned() throws IOException {
        Map<String, String> found = found(root());
        assertFalse(found.isEmpty(), "no SQL files found under */src/main/resources/db/platform");
        List<String> problems = new ArrayList<>();
        PINNED.forEach((path, pinned) -> {
            String now = found.get(path);
            if (now == null) {
                problems.add("REMOVED " + path + ": a released SQL file is never removed or renamed (installations "
                        + "have already run it). Put it back.");
            } else if (!now.equals(pinned)) {
                problems.add("EDITED " + path + " (SHA-256 now " + now + ", pinned " + pinned + "): a released SQL "
                        + "file is never edited. Undo the change and put it in a new file with the next version "
                        + "number. Only a file that is in no release tag yet may change; then update its pin here.");
            }
        });
        found.forEach((path, now) -> {
            if (!PINNED.containsKey(path)) {
                problems.add("NEW " + path + " is not pinned. Add it to PINNED in " + getClass().getSimpleName()
                        + ": Map.entry(\"" + path + "\", \"" + now + "\"). From the release that ships it on, it is "
                        + "never edited again.");
            }
        });
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void theHashReadsCrlfAsLf(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        Path lf = Files.writeString(dir.resolve("lf.sql"), "create table t (id int);\n-- x\n");
        Path crlf = Files.writeString(dir.resolve("crlf.sql"), "create table t (id int);\r\n-- x\r\n");
        Path other = Files.writeString(dir.resolve("other.sql"), "create table t (id bigint);\n-- x\n");
        assertEquals(sha256(lf), sha256(crlf));
        assertNotEquals(sha256(lf), sha256(other));
    }
}
