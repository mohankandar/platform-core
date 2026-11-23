package com.documentplatform.licensing.spring.internal;

import com.documentplatform.licensing.Ed25519;
import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.TrustedKeys;
import com.documentplatform.licensing.spring.LicensingProperties;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The checked licensing config. Internal: read by the licensing and identity auto-configurations; apps set
 * {@code platform.licensing.*} ({@link LicensingProperties}) and use {@code LicenceService}.
 */
public record LicensingConfig(TrustedKeys trustedKeys, String productCode, String tenantId, Duration clockTolerance) {

    /** Prefix of a trusted-keys file read from the classpath. */
    public static final String CLASSPATH = "classpath:";

    /** @throws LicensingConfigException listing every problem */
    public static LicensingConfig from(LicensingProperties p) {
        String prefix = LicensingProperties.PREFIX + ".";
        List<String> problems = new ArrayList<>();
        Map<String, PublicKey> keys = new LinkedHashMap<>();
        // The file's "development": true marks (inline keys carry none): kept, so a development-key licence is seen.
        Set<String> development = new TreeSet<>();
        String keysFile = blankToNull(p.getTrustedKeysFile());
        boolean inline = p.getTrustedKeys() != null && !p.getTrustedKeys().isEmpty();
        if (keysFile == null && !inline) {
            problems.add(prefix + "trusted-keys-file is missing: give the path of the trusted-keys.json shipped with "
                    + "this release (or list the keys under " + prefix + "trusted-keys)");
        }
        if (keysFile != null) {
            readKeysFile(keysFile, prefix, problems).ifPresent(file -> file.keyIds().forEach(id -> {
                keys.put(id, file.key(id).orElseThrow());
                if (file.isDevelopmentKey(id)) development.add(id);
            }));
        }
        if (inline) {
            p.getTrustedKeys().forEach((id, value) -> {
                try {
                    Licence.requireKeyId(id);
                } catch (IllegalArgumentException e) {
                    problems.add(prefix + "trusted-keys: key ID '" + id.replaceAll("[^A-Za-z0-9._:-]", "?")
                            + "' is not valid: " + e.getMessage());
                    return;
                }
                PublicKey key;
                try {
                    key = Ed25519.decodePublic(value == null ? "" : value.strip());
                } catch (IllegalArgumentException e) {
                    problems.add(prefix + "trusted-keys." + id + " is not a Base64 X.509 Ed25519 public key");
                    return;
                }
                PublicKey fromFile = keys.putIfAbsent(id, key);
                if (fromFile != null && !fromFile.equals(key)) {
                    problems.add(prefix + "trusted-keys." + id + " differs from the key with the same ID in "
                            + prefix + "trusted-keys-file; keep one of them");
                }
            });
        }
        String product = blankToNull(p.getProductCode());
        if (product == null) {
            problems.add(prefix + "product-code is missing: give this app's product code as listed in licences");
        } else {
            try {
                Licence.requireProductCode(product);
            } catch (IllegalArgumentException e) {
                problems.add(prefix + "product-code: " + e.getMessage());
            }
        }
        String tenant = blankToNull(p.getTenantId());
        if (tenant == null) {
            problems.add(prefix + "tenant-id is missing: give this installation's customer ID (TEN-...) as in the "
                    + "licence; the setup command writes it into the config fragment it creates "
                    + "(platform-tenant.properties)");
        } else {
            try {
                Licence.requireId(tenant, "tenant ID");
            } catch (IllegalArgumentException e) {
                problems.add(prefix + "tenant-id: " + e.getMessage());
            }
        }
        Duration tolerance = p.getClockTolerance();
        if (tolerance == null || tolerance.isNegative()) {
            problems.add(prefix + "clock-tolerance must be zero or more (for example 1h)");
        } else if (tolerance.compareTo(Duration.ofDays(1)) > 0) {
            problems.add(prefix + "clock-tolerance must be at most 1 day; it is " + tolerance);
        }
        if (!problems.isEmpty()) {
            throw new LicensingConfigException(problems);
        }
        return new LicensingConfig(TrustedKeys.of(keys, development), product, tenant, tolerance);
    }

    /** The keys file, or empty with a problem added. */
    private static java.util.Optional<TrustedKeys> readKeysFile(String location, String prefix, List<String> problems) {
        String shown = location.replaceAll("[^A-Za-z0-9._:/\\\\ -]", "?");
        String text;
        try {
            if (location.startsWith(CLASSPATH)) {
                String resource = location.substring(CLASSPATH.length()).replaceFirst("^/", "");
                ClassLoader loader = Thread.currentThread().getContextClassLoader() != null
                        ? Thread.currentThread().getContextClassLoader() : LicensingConfig.class.getClassLoader();
                try (InputStream in = loader.getResourceAsStream(resource)) {
                    if (in == null) {
                        problems.add(prefix + "trusted-keys-file " + shown + " is not on the classpath");
                        return java.util.Optional.empty();
                    }
                    text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            } else {
                text = Files.readString(Path.of(location), StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            problems.add(prefix + "trusted-keys-file " + shown + " cannot be read (" + e.getClass().getSimpleName()
                    + ")");
            return java.util.Optional.empty();
        }
        try {
            TrustedKeys keys = TrustedKeys.parse(text);
            if (keys.keyIds().isEmpty()) {
                problems.add(prefix + "trusted-keys-file " + shown + " lists no keys");
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(keys);
        } catch (IllegalArgumentException e) {
            problems.add(prefix + "trusted-keys-file " + shown + ": " + e.getMessage());
            return java.util.Optional.empty();
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
