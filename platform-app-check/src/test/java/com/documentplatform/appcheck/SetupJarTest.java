package com.documentplatform.appcheck;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.*;

/** P051 item 7: the setup command as its own process, on Boot 4.2's library versions (run by mvn verify). */
class SetupJarTest {

    @TempDir
    Path dir;

    private record Ran(int exit, String out) {
    }

    private Ran java(Map<String, String> env, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar"));
        command.addAll(List.of(args));
        Path out = dir.resolve("out.txt");
        ProcessBuilder b = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).redirectOutput(out.toFile());
        b.environment().putAll(env);
        Process p = b.start();
        if (!p.waitFor(90, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail("the setup command did not end: " + Files.readString(out));
        }
        return new Ran(p.exitValue(), Files.readString(out, StandardCharsets.UTF_8));
    }

    @Test
    void theReleasedJarShowsItsHelp() throws Exception {
        Path jar = Classpath.elements().stream().filter(e -> e.getFileName().toString().matches("platform-setup-.*\\.jar"))
                .findFirst().orElseThrow(() -> new AssertionError("run with mvn verify: the setup jar is not packaged"));
        for (Path e : Classpath.elements()) {
            if (Files.isRegularFile(e)) Files.copy(e, dir.resolve(e.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Ran help = java(Map.of(), dir.resolve(jar.getFileName()).toString(), "--help");
        assertEquals(0, help.exit(), help.out());
        assertTrue(help.out().startsWith("Usage: setup plan|apply"), help.out());
        Ran noKeys = java(Map.of(), dir.resolve(jar.getFileName()).toString(), "plan", "--licence", "l", "--signin", "s", "--product", "PRODUCT_A", "--product-roles", "r");
        assertEquals(2, noKeys.exit(), noKeys.out());
        assertTrue(noKeys.out().contains("SETUP_KEYS_INVALID"), "without a product's keys it refuses: " + noKeys.out());
    }

    @Test
    void aProductsWrapperPlansAgainstTheDatabase() throws Exception {
        Path demo = Path.of("../platform-setup/demo");
        try (Stream<Path> files = Files.list(demo)) {
            for (Path f : files.toList()) Files.copy(f, dir.resolve(f.getFileName()));
        }
        Db db = new Db().migrated();
        Ran plan = java(Map.of("PLATFORM_SETUP_DB_URL", db.url(), "PLATFORM_SETUP_DB_USER", db.user(), "PLATFORM_SETUP_DB_PASSWORD", db.password()),
                wrapper().toString(), "plan", "--licence", "licence-demo.json", "--signin", "signin-demo.json", "--product", "PRODUCT_A",
                "--product-roles", "product-roles-product-a.json", "--no-customer-keys");
        assertEquals(0, plan.exit(), plan.out());
        assertTrue(plan.out().contains("Setup plan for product PRODUCT_A"), plan.out());
    }

    /** A product's setup wrapper: its licence keys, the setup command as main class, the libraries on the class path. */
    private Path wrapper() throws IOException {
        Manifest m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        m.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "com.documentplatform.setup.SetupCommand");
        m.getMainAttributes().put(Attributes.Name.CLASS_PATH, String.join(" ", Classpath.elements().stream()
                .map(e -> e.toUri().toString()).toList()));
        Path jar = dir.resolve("setup-wrapper.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), m)) {
            out.putNextEntry(new ZipEntry("META-INF/platform/licence-keys.json"));
            out.write(Files.readAllBytes(Path.of("../platform-setup/demo/development-trusted-keys.json")));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("META-INF/platform/licence-build.properties"));
            out.write("built-at=2026-11-01T00:00:00Z\ndevelopment-keys=true\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }
}
