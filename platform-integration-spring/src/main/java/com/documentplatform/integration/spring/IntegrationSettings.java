package com.documentplatform.integration.spring;

import com.documentplatform.integration.config.Limits;
import com.documentplatform.integration.safety.Installation;
import com.documentplatform.integration.safety.ProxySettings;
import com.documentplatform.notifications.secret.SecretRef;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The checked integration settings. {@link #from} lists every problem at once and stops the app
 * ({@link IntegrationConfigException}). Problems name the property, never a value (a pasted secret stays out of the
 * log).
 *
 * @param installation  the installation every integration runs under
 * @param defaultLimits the limits a new integration starts with
 * @param profileFolder where published profile files are, or null when none is set
 * @param health        whether the health contributor is on
 * @param healthCacheFor how long one health result is kept
 */
public record IntegrationSettings(Installation installation, Limits defaultLimits, Path profileFolder, boolean health,
                                  Duration healthCacheFor) {

    public IntegrationSettings {
        Objects.requireNonNull(installation, "installation");
        Objects.requireNonNull(defaultLimits, "defaultLimits");
        Objects.requireNonNull(healthCacheFor, "healthCacheFor");
    }

    public static IntegrationSettings from(IntegrationProperties p) {
        String prefix = IntegrationProperties.PREFIX + ".";
        List<String> problems = new ArrayList<>();

        boolean hosted = false;
        if (p.getInstallation() == null) {
            problems.add(prefix + "installation is required: ON_PREMISES or HOSTED");
        } else {
            hosted = p.getInstallation() == IntegrationProperties.InstallationMode.HOSTED;
        }
        if (hosted && p.isAllowInternalAddresses()) {
            problems.add(prefix + "allow-internal-addresses is never allowed on the hosted service");
        }
        if (hosted && p.isAllowSignInNone()) {
            problems.add(prefix + "allow-sign-in-none is never allowed on the hosted service");
        }

        String pem = null;
        if (p.getPrivateCaFile() != null && !p.getPrivateCaFile().isBlank()) {
            try {
                Path file = Path.of(p.getPrivateCaFile().strip());
                if (!file.isAbsolute()) {
                    problems.add(prefix + "private-ca-file must be an absolute path");
                } else if (!Files.isReadable(file)) {
                    problems.add(prefix + "private-ca-file cannot be read (missing, or no right to read it)");
                } else {
                    pem = Files.readString(file, StandardCharsets.US_ASCII);
                    Installation.onPremises().withPrivateCa(pem); // the same check the installation makes
                }
            } catch (InvalidPathException e) {
                problems.add(prefix + "private-ca-file is not a valid path");
            } catch (IOException e) {
                problems.add(prefix + "private-ca-file cannot be read");
            } catch (IllegalArgumentException e) {
                problems.add(prefix + "private-ca-file is not a PEM bundle of certificates: " + e.getMessage());
                pem = null;
            }
        }

        ProxySettings proxy = proxy(p.getProxy(), prefix + "proxy.", problems);

        Limits limits = null;
        IntegrationProperties.Limits l = p.getLimits();
        try {
            limits = new Limits(l.getConnectTimeout(), l.getReadTimeout(), bytes(l.getMaxFileSize()),
                    l.getMaxDocuments() == null ? 0 : l.getMaxDocuments(), (int) Math.min(Integer.MAX_VALUE,
                    bytes(l.getMaxResponseSize())), bytes(l.getMaxPackageSize()));
        } catch (IllegalArgumentException | NullPointerException e) {
            problems.add(prefix + "limits: " + (e.getMessage() == null ? "a limit is missing" : e.getMessage()));
        }

        Path profiles = null;
        if (p.getProfileFolder() != null && !p.getProfileFolder().isBlank()) {
            try {
                profiles = Path.of(p.getProfileFolder().strip());
                if (!profiles.isAbsolute()) {
                    problems.add(prefix + "profile-folder must be an absolute path");
                } else if (!Files.isDirectory(profiles)) {
                    problems.add(prefix + "profile-folder is not a folder (missing, or no right to read it)");
                }
            } catch (InvalidPathException e) {
                problems.add(prefix + "profile-folder is not a valid path");
            }
        }

        Duration cache = p.getHealth().getCacheFor();
        if (cache == null || cache.compareTo(Duration.ofSeconds(5)) < 0 || cache.compareTo(Duration.ofHours(1)) > 0) {
            problems.add(prefix + "health.cache-for must be 5 seconds to 1 hour");
        }

        if (!problems.isEmpty()) {
            throw new IntegrationConfigException(problems);
        }
        Installation installation = new Installation(hosted, p.isAllowInternalAddresses(), p.isAllowSignInNone(),
                proxy, pem);
        return new IntegrationSettings(installation, limits, profiles, p.getHealth().isEnabled(), cache);
    }

    private static long bytes(org.springframework.util.unit.DataSize d) {
        if (d == null) throw new IllegalArgumentException("a size is missing");
        return d.toBytes();
    }

    private static ProxySettings proxy(IntegrationProperties.Proxy p, String prefix, List<String> problems) {
        boolean any = p.getHost() != null || p.getPort() != null || p.getUser() != null
                || p.getPasswordSecret() != null || !p.getBypass().isEmpty();
        if (!any) return null;
        if (p.getHost() == null || p.getHost().isBlank()) {
            problems.add(prefix + "host is required when a proxy is set");
            return null;
        }
        if (p.getPort() == null) {
            problems.add(prefix + "port is required when a proxy is set");
            return null;
        }
        boolean user = p.getUser() != null && !p.getUser().isBlank();
        boolean password = p.getPasswordSecret() != null && !p.getPasswordSecret().isBlank();
        if (user != password) {
            problems.add(prefix + "user and password-secret go together (the proxy's Basic sign-in)");
            return null;
        }
        SecretRef ref = null;
        if (password) {
            try {
                ref = SecretRef.of(p.getPasswordSecret().strip());
            } catch (IllegalArgumentException e) {
                problems.add(prefix + "password-secret is not a secret reference (give where the password is kept "
                        + "in the secret store, never the password itself)");
                return null;
            }
        }
        try {
            ProxySettings s = new ProxySettings(p.getHost().strip(), p.getPort(), p.getBypass());
            return user ? s.withSignIn(p.getUser().strip(), ref) : s;
        } catch (IllegalArgumentException e) {
            problems.add(prefix + e.getMessage());
            return null;
        }
    }
}
