package com.documentplatform.monitoring;

import com.documentplatform.errors.PlatformError;
import com.documentplatform.errors.SafeText;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A zip an offline customer can send to support: versions (the platform modules', the app's own), Java and the
 * operating system, the configuration with secrets removed, the latest results of all checks, the last failures
 * (code, reference, cause, time) and the schema versions, with a manifest holding the SHA-256 of each entry.
 *
 * <p><b>Never</b> document content, field values, tokens or secrets: a property named like a secret loses its value,
 * every value that looks like a JWT, a PEM block, a {@code password=…} pair or a key loses it too
 * ({@link SafeText}); secret references (names) and addresses stay. A bundle has a size limit: entries that do not
 * fit are left out and the manifest says so ({@code MON_BUNDLE_TOO_LARGE}).
 */
public final class DiagnosticBundle {

    public static final long DEFAULT_LIMIT = 5L * 1024 * 1024;

    /** What goes in. Every field may be empty. */
    public record Inputs(Map<String, String> appVersions, Map<String, String> configuration,
                         List<CheckResult> checks, List<PlatformError> failures, Map<String, String> schemaVersions) {
        public Inputs {
            appVersions = appVersions == null ? Map.of() : Map.copyOf(appVersions);
            configuration = configuration == null ? Map.of() : new TreeMap<>(configuration);
            checks = checks == null ? List.of() : List.copyOf(checks);
            failures = failures == null ? List.of() : List.copyOf(failures);
            schemaVersions = schemaVersions == null ? Map.of() : new TreeMap<>(schemaVersions);
        }
    }

    /** What was written: the entry names in order (the manifest last), and whether some were left out. */
    public record Written(List<String> entries, boolean truncated, long bytes) {
    }

    private DiagnosticBundle() {
    }

    /** Writes the bundle with the default size limit (5 MiB). */
    public static Written build(OutputStream sink, Inputs in) throws IOException {
        return build(sink, in, DEFAULT_LIMIT, Instant.now());
    }

    public static Written build(OutputStream sink, Inputs in, long limitBytes, Instant at) throws IOException {
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(in, "in");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("versions.txt", utf8(versions(in.appVersions())));
        entries.put("system.txt", utf8(system()));
        entries.put("configuration.properties", utf8(configuration(in.configuration())));
        entries.put("checks.txt", utf8(checks(in.checks())));
        entries.put("failures.txt", utf8(failures(in.failures())));
        entries.put("schemas.txt", utf8(lines(in.schemaVersions())));

        List<String> written = new ArrayList<>();
        Map<String, String> sums = new LinkedHashMap<>();
        long total = 0;
        boolean truncated = false;
        try (ZipOutputStream zip = new ZipOutputStream(new NonClosing(sink), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                if (total + e.getValue().length > limitBytes - 4096) {
                    truncated = true;
                    continue;
                }
                put(zip, e.getKey(), e.getValue());
                written.add(e.getKey());
                sums.put(e.getKey(), sha256(e.getValue()));
                total += e.getValue().length;
            }
            StringBuilder m = new StringBuilder("format=platform-diagnostic-bundle/1\ncreated=").append(at)
                    .append('\n');
            sums.forEach((k, v) -> m.append("sha256 ").append(v).append("  ").append(k).append('\n'));
            if (truncated) {
                m.append("truncated=").append(MonitoringCode.MON_BUNDLE_TOO_LARGE.code()).append(": ")
                        .append(PlatformError.fill(MonitoringCode.MON_BUNDLE_TOO_LARGE, Map.of("limit",
                                limitBytes + " bytes"))).append('\n');
            }
            byte[] manifest = utf8(m.toString());
            put(zip, "manifest.txt", manifest);
            written.add("manifest.txt");
            total += manifest.length;
        }
        return new Written(List.copyOf(written), truncated, total);
    }

    /** The configuration as it goes into a bundle: secret-named values and secret-looking values removed. */
    public static Map<String, String> scrubbed(Map<String, String> configuration) {
        Map<String, String> out = new TreeMap<>();
        configuration.forEach((k, v) -> {
            if (k != null) out.put(k, v == null ? "" : SafeText.forName(k, v));
        });
        return out;
    }

    private static String configuration(Map<String, String> c) {
        StringBuilder sb = new StringBuilder("# Secrets removed: values of secret-named properties, and every value "
                + "that looks like a token, key or PEM block, are " + SafeText.REMOVED + ".\n");
        scrubbed(c).forEach((k, v) -> sb.append(k).append('=').append(v.replace("\n", " ")).append('\n'));
        return sb.toString();
    }

    private static String checks(List<CheckResult> results) {
        StringBuilder sb = new StringBuilder();
        for (CheckResult r : results) {
            sb.append(r.checkedAt()).append(' ').append(r.group()).append('/').append(r.name()).append(' ')
                    .append(r.state()).append(' ').append(r.code().code()).append(r.requiredAtStartup() ? " required" : "")
                    .append(" took=").append(r.took().toMillis()).append("ms cause=\"")
                    .append(SafeText.scrub(r.cause())).append("\"\n");
        }
        return sb.toString();
    }

    private static String failures(List<PlatformError> failures) {
        StringBuilder sb = new StringBuilder();
        for (PlatformError e : failures) {
            sb.append(e.at()).append(' ').append(SafeText.scrub(e.logLine())).append('\n');
        }
        return sb.toString();
    }

    private static String versions(Map<String, String> app) {
        StringBuilder sb = new StringBuilder("# Platform modules\n");
        platformVersions().forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        sb.append("# The app\n");
        new TreeMap<>(app).forEach((k, v) -> sb.append(k).append('=').append(SafeText.scrub(v)).append('\n'));
        return sb.toString();
    }

    /** The platform modules on the classpath and their versions (from the released jars' Maven properties). */
    public static Map<String, String> platformVersions() {
        Map<String, String> out = new TreeMap<>();
        try {
            for (URL u : Collections.list(DiagnosticBundle.class.getClassLoader()
                    .getResources("META-INF/maven/com.documentplatform.platform"))) {
                if (!u.getProtocol().equals("jar")) continue;
                try (JarFile jar = ((JarURLConnection) u.openConnection()).getJarFile()) {
                    jar.stream().filter(e -> e.getName().endsWith("/pom.properties")).forEach(e -> {
                        Properties p = new Properties();
                        try (InputStream in = jar.getInputStream(e)) {
                            p.load(in);
                            out.put(p.getProperty("artifactId", "?"), p.getProperty("version", "?"));
                        } catch (IOException ignored) {
                            // unreadable entry: left out
                        }
                    });
                }
            }
        } catch (IOException ignored) {
            // no jar metadata (running from classes): none listed
        }
        return out;
    }

    private static String system() {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("java.version", System.getProperty("java.version"));
        s.put("java.vendor", System.getProperty("java.vendor"));
        s.put("java.vm.name", System.getProperty("java.vm.name"));
        s.put("os.name", System.getProperty("os.name"));
        s.put("os.version", System.getProperty("os.version"));
        s.put("os.arch", System.getProperty("os.arch"));
        s.put("processors", String.valueOf(Runtime.getRuntime().availableProcessors()));
        s.put("max.memory.mb", String.valueOf(Runtime.getRuntime().maxMemory() / (1024 * 1024)));
        return lines(s);
    }

    private static String lines(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        m.forEach((k, v) -> sb.append(k).append('=').append(SafeText.forName(k, String.valueOf(v))).append('\n'));
        return sb.toString();
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The zip is finished, but the caller's stream stays open. */
    private static final class NonClosing extends java.io.FilterOutputStream {
        NonClosing(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
