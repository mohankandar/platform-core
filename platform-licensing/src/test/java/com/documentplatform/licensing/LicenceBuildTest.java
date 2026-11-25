package com.documentplatform.licensing;

import com.documentplatform.licensing.json.Json;
import com.documentplatform.licensing.json.JsonException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** P045: the trust root inside the app's build (SEC-12, SEC-43), one canonical form (SEC-26), size caps (SEC-51). */
class LicenceBuildTest {

    @TempDir
    Path dir;

    private static PublicKey key() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();
    }

    private Path place(String name, TrustedKeys keys, String build) throws Exception {
        Path root = dir.resolve(name);
        Files.createDirectories(root.resolve("META-INF/platform"));
        if (keys != null) Files.writeString(root.resolve(LicenceBuild.KEYS), keys.toJson());
        if (build != null) Files.writeString(root.resolve(LicenceBuild.BUILD), build);
        return root;
    }

    private static LicenceBuild load(Path... roots) throws Exception {
        URL[] urls = new URL[roots.length];
        for (int i = 0; i < roots.length; i++) urls[i] = roots[i].toUri().toURL();
        try (URLClassLoader loader = new URLClassLoader(urls, null)) {
            return LicenceBuild.load(loader);
        }
    }

    private static String refused(Path... roots) {
        return assertThrows(LicenceBuild.Invalid.class, () -> load(roots)).getMessage();
    }

    /** P056 (LV-24.10). */
    @Test
    void theBuildSaysWhetherItIsADevelopmentBuild() throws Exception {
        TrustedKeys keys = TrustedKeys.of(Map.of("lic-1", key(), "dev-1", key()), Set.of("dev-1"));
        LicenceBuild development = LicenceBuild.of(keys, true, Instant.parse("2026-11-21T10:00:00Z"));
        LicenceBuild production = LicenceBuild.of(keys, false, Instant.parse("2026-11-21T10:00:00Z"));
        assertTrue(development.isDevelopment());
        assertFalse(production.isDevelopment());
        Licence dev = licenceSignedBy("dev-1");
        assertTrue(development.isDevelopmentLicence(dev));
        assertFalse(development.isDevelopmentLicence(licenceSignedBy("lic-1")));
        assertFalse(production.isDevelopmentLicence(dev));
        String built = "built-at=2026-11-21T10:00:00Z\n";
        assertTrue(load(place("d", keys, built + "development-keys=true")).isDevelopment());
        assertFalse(load(place("p", keys, built)).isDevelopment());
    }

    private static Licence licenceSignedBy(String keyId) {
        return new Licence("LIC-TEST-0001", 1, "Test Customer Ltd", "TEN-TEST-0001", java.util.List.of("PRODUCT_A"),
                java.util.List.of(new EntitySlot("ENT-TEST-0001", java.util.List.of(new Allowance("PRODUCT_A",
                        AllowanceType.PAGES_PER_MONTH, 10)))), LicenceType.SUBSCRIPTION, LicenceKind.PRODUCTION,
                java.time.LocalDate.parse("2026-01-01"), java.time.LocalDate.parse("2027-01-01"), null,
                Deployment.ON_PREM, keyId, Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void theKeysComeFromTheAppsOwnBuildOnly() throws Exception {
        TrustedKeys keys = TrustedKeys.of(Map.of("lic-1", key()));
        String built = "built-at=2026-11-21T10:00:00Z\n";
        Path app = place("app", keys, built);
        LicenceBuild b = load(app);
        assertEquals(Set.of("lic-1"), b.keys().keyIds());
        assertEquals(Instant.parse("2026-11-21T10:00:00Z"), b.builtAt());
        // The customer puts a jar or folder with their own key beside the app's, before or after it.
        Path added = place("added", TrustedKeys.of(Map.of("customer", key())), built);
        assertTrue(refused(added, app).contains("2 times"));
        assertTrue(refused(app, added).contains("2 times"));
        assertTrue(refused(app, place("switch", null, built + "development-keys=true")).contains("2 times"));
        assertTrue(refused(place("k", keys, null), place("b", null, built)).contains("not in the same jar"));
        assertTrue(refused(place("none", null, null)).contains("missing from the app's build"));
        assertTrue(refused(place("time", keys, "built-at=yesterday")).contains("built-at"));
        assertTrue(refused(place("more", keys, built + "x=1")).contains("only built-at"));
        assertTrue(refused(place("dev", keys, built + "development-keys=yes")).contains("true or false"));
        assertTrue(refused(place("bad", TrustedKeys.of(Map.of()), built)).contains("lists no licence key"));
    }

    @Test
    void developmentKeysNeedADevelopmentBuild() throws Exception {
        TrustedKeys keys = TrustedKeys.of(Map.of("lic-1", key(), "dev-1", key()), Set.of("dev-1"));
        LicenceBuild production = load(place("p", keys, "built-at=2026-11-21T10:00:00Z"));
        assertEquals(Set.of("lic-1"), production.keys().keyIds());
        assertFalse(production.keys().isDevelopmentKey("dev-1"));
        LicenceBuild development = load(place("d", keys, "built-at=2026-11-21T10:00:00Z\ndevelopment-keys=true"));
        assertTrue(development.keys().isDevelopmentKey("dev-1"));
        TrustedKeys onlyDev = TrustedKeys.of(Map.of("dev-1", key()), Set.of("dev-1"));
        assertTrue(refused(place("o", onlyDev, "built-at=2026-11-21T10:00:00Z")).contains("development keys"));
    }

    @Test
    void anUnpairedSurrogateIsRefusedNotSignedAsAQuestionMark() {
        for (String bad : new String[]{"\\ud800", "\\udc00", "\\ud800x", "\\udc00\\ud800"}) {
            assertThrows(JsonException.class, () -> Json.parse("{\"customer\":\"A" + bad + "\"}"), bad);
        }
        assertEquals("😀", Json.parse("\"\\ud83d\\ude00\""), "a pair is fine");
        assertThrows(JsonException.class, () -> SignedDocument.signedMessage("f", Map.of("customer", "A\ud800")));
        assertFalse(new String(SignedDocument.signedMessage("f", Map.of("customer", "A?"))).isEmpty());
    }

    @Test
    void aFileLargerThanAnySignedFileIsNotRead() throws Exception {
        SignedDocument.Failure f = assertThrows(SignedDocument.Failure.class, () -> SignedDocument.open(
                new byte[SignedDocument.MAX_BYTES + 1], LicenceFormat.FORMAT, "licence", TrustedKeys.of(Map.of())));
        assertTrue(f.getMessage().contains("larger"), f.getMessage());
    }
}
