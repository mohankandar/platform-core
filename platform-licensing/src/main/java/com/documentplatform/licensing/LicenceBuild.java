package com.documentplatform.licensing;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * What the app's own build says about licences (P045, SEC-12, SEC-43): the public keys it trusts, whether it trusts
 * development keys, and when it was built. Both files are resources inside the app's jar; no installation file, setting
 * or option can add or change a key:
 *
 * <ul>
 *   <li>{@value #KEYS}: the trusted keys ({@link TrustedKeys}, {@code platform-licence-keys/1});</li>
 *   <li>{@value #BUILD}: {@code built-at} (required, ISO-8601 instant; licence dates never go before it) and
 *       {@code development-keys} ({@code true} only in a development build; otherwise keys marked
 *       {@code "development": true} are not trusted, whatever the keys file says).</li>
 * </ul>
 *
 * Each must be on the classpath exactly once and in the same place, so a copy added beside the app's is refused. Someone
 * who rebuilds the app's jar can still change them: on premises this is a deterrent, not tamper-proof.
 */
public final class LicenceBuild {

    public static final String KEYS = "META-INF/platform/licence-keys.json";
    public static final String BUILD = "META-INF/platform/licence-build.properties";

    private static final Set<String> SETTINGS = Set.of("built-at", "development-keys");

    private final TrustedKeys keys;
    private final Instant builtAt;

    private LicenceBuild(TrustedKeys keys, Instant builtAt) {
        this.keys = keys;
        this.builtAt = builtAt;
    }

    /** The build's files are missing, there more than once, or not readable. Names the resource, never its content. */
    public static final class Invalid extends RuntimeException {
        Invalid(String message) {
            super(message);
        }
    }

    /** For a wrapper or test with the values in code. Without {@code developmentKeys}, development keys are dropped. */
    public static LicenceBuild of(TrustedKeys keys, boolean developmentKeys, Instant builtAt) {
        Objects.requireNonNull(builtAt, "builtAt");
        TrustedKeys trusted = developmentKeys ? keys : keys.withoutDevelopmentKeys();
        if (trusted.keyIds().isEmpty()) {
            throw new Invalid("The app's build lists no licence key it may trust" + (keys.keyIds().isEmpty() ? "."
                    : ": all its keys are development keys, and this is not a development build."));
        }
        return new LicenceBuild(trusted, builtAt);
    }

    /** @throws Invalid naming what is wrong with the app's build */
    public static LicenceBuild load() {
        return load(LicenceBuild.class.getClassLoader());
    }

    static LicenceBuild load(ClassLoader loader) {
        URL keysAt = only(loader, KEYS);
        URL buildAt = only(loader, BUILD);
        if (!place(keysAt, KEYS).equals(place(buildAt, BUILD))) {
            throw new Invalid(KEYS + " and " + BUILD + " are not in the same jar of the app.");
        }
        TrustedKeys keys;
        try {
            keys = TrustedKeys.parse(read(keysAt, KEYS));
        } catch (IllegalArgumentException e) {
            throw new Invalid(KEYS + " in the app's build: " + e.getMessage() + ".");
        }
        Properties p = new Properties();
        try {
            p.load(new StringReader(read(buildAt, BUILD)));
        } catch (IOException | IllegalArgumentException e) {
            throw new Invalid(BUILD + " in the app's build is not a properties file.");
        }
        if (!SETTINGS.containsAll(p.stringPropertyNames())) {
            throw new Invalid(BUILD + " in the app's build may hold only built-at and development-keys.");
        }
        String development = p.getProperty("development-keys", "false").strip();
        if (!development.equals("true") && !development.equals("false")) {
            throw new Invalid(BUILD + " in the app's build: development-keys must be true or false.");
        }
        Instant builtAt;
        try {
            builtAt = Instant.parse(p.getProperty("built-at", "").strip());
        } catch (DateTimeException e) {
            throw new Invalid(BUILD + " in the app's build: built-at must be a time such as 2026-11-21T10:00:00Z.");
        }
        return of(keys, development.equals("true"), builtAt);
    }

    /** The keys this build trusts. */
    public TrustedKeys keys() {
        return keys;
    }

    /** When the app was built: licence dates never go before it. */
    public Instant builtAt() {
        return builtAt;
    }

    private static URL only(ClassLoader loader, String name) {
        List<URL> found;
        try {
            found = new ArrayList<>(Collections.list(loader.getResources(name)));
        } catch (IOException e) {
            throw new Invalid(name + " could not be looked up in the app's build (" + e.getClass().getSimpleName() + ").");
        }
        long places = found.stream().map(URL::toString).distinct().count();
        if (places == 0) {
            throw new Invalid(name + " is missing from the app's build: the product ships its licence keys in its jar.");
        }
        if (places > 1) {
            throw new Invalid(name + " is on the classpath " + places + " times; only the app's own may be there. Remove "
                    + "what was added to the classpath.");
        }
        return found.get(0);
    }

    private static String place(URL url, String name) {
        return url.toString().substring(0, url.toString().length() - name.length());
    }

    private static String read(URL url, String name) {
        try (InputStream in = url.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new Invalid(name + " in the app's build cannot be read (" + e.getClass().getSimpleName() + ").");
        }
    }
}
