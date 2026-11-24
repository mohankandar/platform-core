package com.documentplatform.appcheck;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P047 (P051 question 1): the full public signatures of 1.0.0, docs/api/public-api-1.txt. Every public type outside
 * {@code .internal} packages of every published module, with its public and protected members. A line that is gone or changed
 * breaks 1.x callers and fails the build; a new one is recorded with {@code -Dplatform.api.write=true}, which only adds.
 * A method added to an interface must have a default, or classes of apps that implement it no longer compile.
 */
class StableApiTest {

    private static final Path FILE = Path.of("../docs/api/public-api-1.txt");
    private static final Set<String> NOT_PUBLISHED = Set.of("platform-app-check", "platform-error-catalogue",
            "platform-licensing-issue", "platform-bom");

    @Test
    void nothingPublicIsRemovedOrChanged() throws Exception {
        TreeSet<String> now = signatures();
        assertTrue(now.size() > 3000, "read " + now.size() + " signatures");
        TreeSet<String> recorded = Files.exists(FILE) ? Files.readAllLines(FILE).stream().map(String::strip)
                .filter(l -> !l.isEmpty()).collect(Collectors.toCollection(TreeSet::new)) : new TreeSet<>();
        List<String> gone = recorded.stream().filter(l -> !now.contains(l)).toList();
        assertEquals(List.of(), gone, "removed or changed since 1.0.0: a 2.0 change");
        List<String> added = now.stream().filter(l -> !recorded.contains(l)).toList();
        if (Boolean.getBoolean("platform.api.write")) {
            recorded.addAll(added);
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, String.join("\n", recorded) + "\n");
            return;
        }
        Set<String> oldTypes = recorded.stream().map(l -> l.split(" ")[0]).collect(Collectors.toSet());
        List<String> abstractAdded = added.stream().filter(l -> l.contains(" abstract ") && oldTypes.contains(l.split(" ")[0])).toList();
        assertEquals(List.of(), abstractAdded, "an abstract method added to a type of 1.0.0 breaks what implements it");
        assertEquals(List.of(), added, "new public API: record it with -Dplatform.api.write=true");
    }

    private static TreeSet<String> signatures() throws Exception {
        TreeSet<String> out = new TreeSet<>();
        try (Stream<Path> modules = Files.list(Path.of(".."))) {
            for (Path module : modules.filter(m -> m.getFileName().toString().startsWith("platform-")
                    && !NOT_PUBLISHED.contains(m.getFileName().toString())).toList()) {
                Path src = module.resolve("src/main/java");
                if (!Files.isDirectory(src)) continue;
                try (Stream<Path> files = Files.walk(src)) {
                    for (Path f : files.filter(p -> p.toString().endsWith(".java") && !p.getFileName().toString().contains("-info")).toList()) {
                        String rel = src.relativize(f).toString().replace('\\', '/');
                        String type = rel.substring(0, rel.length() - 5).replace('/', '.');
                        if (!type.contains(".internal.")) add(out, Class.forName(type, false, StableApiTest.class.getClassLoader()));
                    }
                }
            }
        }
        return out;
    }

    private static void add(Set<String> out, Class<?> c) {
        if (!open(c.getModifiers())) return;
        String n = c.getName();
        boolean data = c.isEnum() || c.isRecord();
        out.add(n + " type " + (c.isInterface() ? "interface" : c.isEnum() ? "enum" : c.isRecord() ? "record" : "class")
                + (Modifier.isFinal(c.getModifiers()) && !data ? " final" : "")
                + " extends " + (c.getSuperclass() == null ? "-" : c.getSuperclass().getName())
                + " implements " + Arrays.stream(c.getInterfaces()).map(Class::getName).sorted().toList());
        for (Field f : c.getDeclaredFields()) if (open(f.getModifiers()) && !f.isSynthetic()) {
            out.add(n + " field " + (Modifier.isStatic(f.getModifiers()) ? "static " : "") + f.getGenericType().getTypeName() + " " + f.getName());
        }
        for (Constructor<?> k : c.getDeclaredConstructors()) if (open(k.getModifiers()) && !k.isSynthetic()) {
            out.add(n + " new " + Arrays.stream(k.getGenericParameterTypes()).map(java.lang.reflect.Type::getTypeName).toList());
        }
        for (Method m : c.getDeclaredMethods()) if (open(m.getModifiers()) && !m.isSynthetic() && !m.isBridge()) {
            List<String> thrown = new ArrayList<>(Arrays.stream(m.getExceptionTypes()).map(Class::getName).sorted().toList());
            out.add(n + " method " + (Modifier.isStatic(m.getModifiers()) ? "static " : "") + (Modifier.isAbstract(m.getModifiers()) ? "abstract " : "")
                    + m.getGenericReturnType().getTypeName() + " " + m.getName()
                    + Arrays.stream(m.getGenericParameterTypes()).map(java.lang.reflect.Type::getTypeName).toList()
                    + (thrown.isEmpty() ? "" : " throws " + thrown));
        }
        for (Class<?> inner : c.getDeclaredClasses()) add(out, inner);
    }

    private static boolean open(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }
}
