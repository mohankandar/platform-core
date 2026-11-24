package com.documentplatform.monitoring.spring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.autoconfigure.application.AvailabilityHealthContributorAutoConfiguration;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** P046: Spring Boot classes our wiring names by text only (order, conditions) exist under these names in Boot 4.2. */
class BootNamesTest {

    @Test
    void everyBootClassNamedByTextExists() throws Exception {
        Pattern named = Pattern.compile("\"(org[.]springframework[.]boot[.][A-Za-z.]+)\"");
        Set<String> names = new TreeSet<>();
        try (Stream<Path> modules = Files.list(Path.of(".."))) {
            for (Path module : modules.filter(p -> p.getFileName().toString().endsWith("-spring")).toList()) {
                try (Stream<Path> files = Files.walk(module.resolve("src/main/java"))) {
                    for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                        Matcher m = named.matcher(Files.readString(f));
                        while (m.find()) names.add(m.group(1));
                    }
                }
            }
        }
        assertFalse(names.isEmpty());
        for (String n : names) {
            assertDoesNotThrow(() -> Class.forName(n, false, getClass().getClassLoader()), n);
        }
    }

    @Test
    void ourReadinessTakesThePlaceOfBootsOwn() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AvailabilityHealthContributorAutoConfiguration.class,
                        MonitoringAutoConfiguration.class))
                .withPropertyValues("management.endpoint.health.probes.enabled=true", "management.health.readinessstate.enabled=true")
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure(), () -> String.valueOf(ctx.getStartupFailure()));
                    HealthIndicator ours = ctx.getBean("readinessStateHealthIndicator", HealthIndicator.class);
                    assertTrue(ours.getClass().getName().startsWith("com.documentplatform."), ours.getClass().getName());
                });
    }
}
