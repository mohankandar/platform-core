package com.documentplatform.appcheck;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** P051 items 2, 3 and 6: one Nimbus (Spring Security's), nothing of Boot 3, no Jackson 2 in what apps get. */
class DependenciesTest {

    private static List<Path> poms() throws Exception {
        try (Stream<Path> s = Files.list(Path.of(".."))) {
            return s.map(p -> p.resolve("pom.xml")).filter(Files::exists).toList();
        }
    }

    @Test
    void nimbusIsSpringSecuritysOnly() throws Exception {
        assertEquals(1, Classpath.versions("nimbus-jose-jwt").size(), Classpath.versions("nimbus-jose-jwt").toString());
        for (Path pom : poms()) assertFalse(Files.readString(pom).contains("nimbus-jose-jwt"), pom + " pins Nimbus");
        assertFalse(Files.readString(Path.of("../pom.xml")).contains("nimbus-jose-jwt"));
    }

    @Test
    void everySpringJarIsOfTheBaseline() {
        for (Path e : Classpath.elements()) {
            String n = e.getFileName().toString();
            if (!n.endsWith(".jar")) continue;
            if (n.startsWith("spring-boot")) assertTrue(n.contains("-4.2."), n);
            else if (n.startsWith("spring-security")) assertTrue(n.contains("-7."), n);
            else if (n.matches("spring-(core|beans|context|aop|expression|jdbc|tx|web|webmvc|test)-.*")) assertTrue(n.contains("-7.1."), n);
        }
    }

    @Test
    void noJackson2InRequiredDependenciesOrPublicSignatures() throws Exception {
        for (Path pom : poms()) {
            String text = Files.readString(pom).replaceAll("(?s)<exclusions>.*?</exclusions>", "");
            assertFalse(text.contains("<groupId>com.fasterxml"), pom + " requires Jackson 2");
        }
        List<String> found = new ArrayList<>();
        int types = 0;
        for (Path e : Classpath.elements()) {
            String n = e.getFileName().toString();
            boolean ours = n.startsWith("platform-") || e.toString().replace('\\', '/').matches(".*/platform-[a-z-]+/target/classes");
            if (!ours) continue;
            for (String type : typesIn(e)) {
                Class<?> c;
                try {
                    c = Class.forName(type, false, getClass().getClassLoader());
                } catch (NoClassDefFoundError optional) { // an optional library this app does not have (servlet)
                    if (String.valueOf(optional.getMessage()).contains("com/fasterxml")) found.add(type);
                    continue;
                }
                if (!Modifier.isPublic(c.getModifiers())) continue;
                types++;
                List<String> signatures = new ArrayList<>();
                try {
                    for (Method m : c.getDeclaredMethods()) if (open(m.getModifiers())) signatures.add(m.toGenericString());
                    for (Constructor<?> k : c.getDeclaredConstructors()) if (open(k.getModifiers())) signatures.add(k.toGenericString());
                    for (Field f : c.getDeclaredFields()) if (open(f.getModifiers())) signatures.add(f.toGenericString());
                } catch (NoClassDefFoundError optional) {
                    signatures.add(String.valueOf(optional.getMessage())); // an optional module's type; not Jackson
                }
                signatures.stream().filter(s -> s.contains("com.fasterxml") || s.contains("com/fasterxml")).forEach(found::add);
            }
        }
        assertEquals(List.of(), found);
        assertTrue(types > 300, "looked at " + types + " public types");
    }

    private static boolean open(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static List<String> typesIn(Path element) throws Exception {
        if (!Files.exists(element)) return List.of();
        if (Files.isDirectory(element)) return typesUnder(element);
        try (FileSystem jar = FileSystems.newFileSystem(element)) {
            return typesUnder(jar.getPath("/"));
        }
    }

    private static List<String> typesUnder(Path root) throws Exception {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".class") && !p.getFileName().toString().contains("-info"))
                    .map(p -> root.relativize(p).toString().replace('\\', '/').replace('/', '.'))
                    .map(p -> p.substring(0, p.length() - 6)).toList();
        }
    }
}
