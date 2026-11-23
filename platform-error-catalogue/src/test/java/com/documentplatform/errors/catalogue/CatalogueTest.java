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
import java.util.Map;
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

    static List<Class<?>> classes;
    static List<Class<?>> enums;
    static Catalogue catalogue;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void scan() throws Exception {
        classes = platformClasses();
        enums = classes.stream().filter(Class::isEnum).toList();
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
            // A code enum by its name, or by having texts next to it (error-codes-9: the name pattern alone misses one).
            boolean hasTexts = e.getResource(e.getSimpleName() + ".codes.properties") != null;
            if ((CODE_NAME.matcher(e.getName()).matches() || hasTexts) && !INTERNAL.contains(e.getName())
                    && !PlatformCode.class.isAssignableFrom(e)) {
                missing.add(e.getName());
            }
        }
        assertEquals(List.of(), missing, "code enums that do not implement PlatformCode");
        assertTrue(catalogue.entries().size() > 550, "found " + catalogue.entries().size() + " codes");
    }

    /**
     * monitoring-69, xc-catalogue-4 … -12 (P037): each Spring module's start-up refusal (settings, missing beans) carries
     * a {@code *_SETTINGS_INVALID} {@link PlatformCode} and its message starts with that code.
     */
    @Test
    void everySpringModulesStartupRefusalCarriesItsSettingsCode() throws Exception {
        List<String> classes = List.of(
                "com.documentplatform.outbound.spring.OutboundConfigException",
                "com.documentplatform.integration.spring.IntegrationConfigException",
                "com.documentplatform.crypto.spring.CryptoConfigException",
                "com.documentplatform.licensing.spring.LicensingConfigException",
                "com.documentplatform.notifications.spring.NotificationsConfigException",
                "com.documentplatform.eraselog.spring.EraseLogConfigException",
                "com.documentplatform.modelgov.spring.ModelGovConfig$Invalid",
                "com.documentplatform.identity.spring.PushCallerConfigException",
                "com.documentplatform.monitoring.spring.MonitoringConfigException");
        Set<String> codes = new TreeSet<>();
        for (String name : classes) {
            java.lang.reflect.Constructor<?> c = Class.forName(name).getDeclaredConstructor(List.class);
            c.setAccessible(true);
            Throwable t = (Throwable) c.newInstance(List.of("platform.x.y is missing"));
            assertInstanceOf(com.documentplatform.errors.HasPlatformCode.class, t, name);
            PlatformCode code = ((com.documentplatform.errors.HasPlatformCode) t).platformCode();
            assertTrue(code.code().endsWith("_SETTINGS_INVALID"), name + ": " + code.code());
            assertTrue(t.getMessage().startsWith(code.code() + ": "), t.getMessage());
            assertTrue(t.getMessage().contains("platform.x.y is missing"), t.getMessage());
            assertTrue(catalogue.entries().stream().anyMatch(e -> e.code().equals(code.code())), code.code());
            codes.add(code.code());
        }
        assertEquals(classes.size(), codes.size(), "one code per module: " + codes);
    }

    /** xc-catalogue-19 (P037): store and read failures carry a code; the message names the operation and SQL state. */
    @Test
    void everyStoreFailureCarriesItsCode() throws Exception {
        Map<String, String> stores = new java.util.LinkedHashMap<>();
        stores.put("com.documentplatform.fields.jdbc.FieldStoreException", "FLD_STORE_FAILED");
        stores.put("com.documentplatform.identity.jdbc.IdentityStoreException", "IDN_STORE_FAILED");
        stores.put("com.documentplatform.licensing.jdbc.LicensingStoreException", "LIC_STORE_FAILED");
        stores.put("com.documentplatform.notifications.jdbc.NotificationStoreException", "NOTIF_STORE_FAILED");
        stores.put("com.documentplatform.modelgov.jdbc.PostgresModelGovStore$StoreException", "MG_STORE_FAILED");
        for (Map.Entry<String, String> s : stores.entrySet()) {
            java.lang.reflect.Constructor<?> c = Class.forName(s.getKey())
                    .getDeclaredConstructor(String.class, java.sql.SQLException.class);
            c.setAccessible(true);
            Throwable t = (Throwable) c.newInstance("read things", new java.sql.SQLException("denied", "42501"));
            assertEquals(s.getValue(), ((com.documentplatform.errors.HasPlatformCode) t).platformCode().code(), s.getKey());
            assertTrue(t.getMessage().startsWith(s.getValue() + ": "), t.getMessage());
            assertTrue(t.getMessage().contains("read things") && t.getMessage().contains("SQL state 42501"),
                    t.getMessage());
        }
        Throwable unreadable = new com.documentplatform.integration.config.ConfigJson.Unreadable("version 3");
        assertEquals("INT_CONFIG_UNREADABLE",
                ((com.documentplatform.errors.HasPlatformCode) unreadable).platformCode().code());
        assertTrue(unreadable.getMessage().startsWith("INT_CONFIG_UNREADABLE: "), unreadable.getMessage());
    }

    /**
     * xc-catalogue-3, error-codes-9 (P037): the check sees every module. Every module of the root {@code <modules>}
     * except the BOM, the testkits and this one is a dependency of this module, so a new module cannot be left out.
     */
    @Test
    void everyPlatformModuleIsOnTheCataloguesClasspath() throws IOException {
        String root = Files.readString(Path.of("../pom.xml"), StandardCharsets.UTF_8);
        String own = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        String modules = root.substring(root.indexOf("<modules>"), root.indexOf("</modules>"));
        java.util.regex.Matcher m = Pattern.compile("<module>([^<]+)</module>").matcher(modules);
        List<String> missing = new ArrayList<>();
        int checked = 0;
        while (m.find()) {
            String module = m.group(1).strip();
            if (module.equals("platform-bom") || module.endsWith("-testkit") || module.equals("platform-error-catalogue")) {
                continue;
            }
            checked++;
            if (!own.contains("<artifactId>" + module + "</artifactId>")) missing.add(module);
        }
        assertEquals(List.of(), missing, "modules missing from platform-error-catalogue/pom.xml");
        assertTrue(checked >= 20, "read " + checked + " modules");
    }

    /**
     * xc-catalogue-18 (P037): an exception that carries a code ({@code code()} or {@code reason()} returning a
     * {@link PlatformCode}) implements {@link com.documentplatform.errors.HasPlatformCode}, so monitoring and the
     * app's error handler read the real code in one generic way.
     */
    @Test
    void everyExceptionThatCarriesACodeImplementsHasPlatformCode() {
        List<String> missing = new ArrayList<>();
        int carrying = 0;
        for (Class<?> c : classes) {
            if (!Throwable.class.isAssignableFrom(c)) continue;
            boolean carries = false;
            for (String accessor : List.of("code", "reason")) {
                try {
                    java.lang.reflect.Method m = c.getMethod(accessor);
                    if (PlatformCode.class.isAssignableFrom(m.getReturnType())) carries = true;
                } catch (NoSuchMethodException none) {
                    // no such accessor
                } catch (Throwable notLoadable) {
                    // an optional dependency is missing: skipped like platformClasses does
                }
            }
            if (!carries) continue;
            carrying++;
            if (!com.documentplatform.errors.HasPlatformCode.class.isAssignableFrom(c)) missing.add(c.getName());
        }
        assertEquals(List.of(), missing, "exceptions that carry a PlatformCode but do not implement HasPlatformCode");
        assertTrue(carrying >= 18, "found " + carrying + " code-carrying exceptions");
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

    /** Every class under com.documentplatform on the test classpath (the platform modules' classes and jars). */
    static List<Class<?>> platformClasses() throws IOException, URISyntaxException {
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
                out.add(c);
            } catch (Throwable notLoadable) {
                // optional dependencies of a class are not on this classpath: not a code enum
            }
        }
        return out;
    }
}
