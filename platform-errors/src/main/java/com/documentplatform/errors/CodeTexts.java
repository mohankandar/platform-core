package com.documentplatform.errors;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The texts of a {@link PlatformCode} enum, read once from {@code <EnumName>.codes.properties} next to the enum
 * (UTF-8). A missing text is the empty string; the catalogue test refuses a code without a message, cause or fix.
 */
public final class CodeTexts {

    private static final Map<Class<?>, Properties> LOADED = new ConcurrentHashMap<>();

    private final Properties p;
    private final String code;

    private CodeTexts(Properties p, String code) {
        this.p = p;
        this.code = code;
    }

    /** The texts of this code. */
    public static CodeTexts of(PlatformCode c) {
        return new CodeTexts(LOADED.computeIfAbsent(enumClass(c), CodeTexts::load), c.name());
    }

    /** The family name: the enum's name below its package, nested enums joined with a dot. */
    public static String family(PlatformCode c) {
        Class<?> e = enumClass(c);
        String n = e.getName();
        String pkg = e.getPackageName();
        return (pkg.isEmpty() ? n : n.substring(pkg.length() + 1)).replace('$', '.');
    }

    /** The resource name of a family's texts, relative to the enum's package. */
    public static String resourceName(Class<?> enumClass) {
        String n = enumClass.getName();
        String pkg = enumClass.getPackageName();
        return (pkg.isEmpty() ? n : n.substring(pkg.length() + 1)).replace('$', '.') + ".codes.properties";
    }

    /** The code's own text; else the longest matching prefix default ({@code INT_CONFIG_*.kind}); else {@code *.}. */
    String text(String key) {
        String v = p.getProperty(code + "." + key);
        if (v == null) {
            int best = -1;
            for (String name : p.stringPropertyNames()) {
                int star = name.indexOf("*.");
                if (star <= 0 || !name.substring(star + 2).equals(key)) continue;
                String prefix = name.substring(0, star);
                if (code.startsWith(prefix) && prefix.length() > best) {
                    best = prefix.length();
                    v = p.getProperty(name);
                }
            }
        }
        if (v == null) v = p.getProperty("*." + key);
        return v == null ? "" : v.strip();
    }

    PlatformCode.Kind kind() {
        String k = text("kind");
        return k.isEmpty() ? PlatformCode.Kind.FAILED : PlatformCode.Kind.valueOf(k);
    }

    OptionalInt http() {
        String h = text("http");
        return h.isEmpty() ? OptionalInt.empty() : OptionalInt.of(Integer.parseInt(h));
    }

    private static Class<?> enumClass(PlatformCode c) {
        Class<?> k = c.getClass();
        if (c instanceof Enum<?> e) k = e.getDeclaringClass();
        return k;
    }

    private static Properties load(Class<?> enumClass) {
        Properties p = new Properties();
        try (InputStream in = enumClass.getResourceAsStream(resourceName(enumClass))) {
            if (in != null) p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }
}
