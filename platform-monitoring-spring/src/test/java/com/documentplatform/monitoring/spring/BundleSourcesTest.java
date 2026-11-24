package com.documentplatform.monitoring.spring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.env.ConfigTreePropertySource;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** P045, SEC-11: the bundle never holds values from mounted secret files, only allow-listed sources and names. */
class BundleSourcesTest {

    @Test
    void mountedSecretFilesNeverReachTheBundle(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("dbcreds"), "S3cr3tPassw0rdNotNamed");
        Files.writeString(dir.resolve("platform.vendor-json"), "{\"user\":\"svc\",\"password\":\"hunter2\"}");
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new ConfigTreePropertySource("Config tree '" + dir + "'", dir));
        env.getPropertySources().addLast(new OriginTrackedMapPropertySource("Config resource 'application.yml'",
                Map.of("platform.monitoring.every", "60s", "server.port", "8080", "vendor.dsn", "u:p", "myapp.mode",
                        "x")));
        env.getPropertySources().addLast(new OriginTrackedMapPropertySource("bootstrapProperties-vault/app",
                Map.of("platform.from-vault", "v")));
        assertEquals(Map.of("platform.monitoring.every", "60s", "server.port", "8080"),
                new DiagnosticBundles(null, env, null, Map::of, List.of()).configuration());
        assertEquals(Map.of("platform.monitoring.every", "60s", "server.port", "8080", "myapp.mode", "x"),
                new DiagnosticBundles(null, env, null, Map::of, List.of("myapp.")).configuration());
    }
}
