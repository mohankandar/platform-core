package com.documentplatform.outbound.spring;

import com.documentplatform.notifications.secret.SecretRef;
import com.documentplatform.outbound.Installation;
import com.documentplatform.outbound.ProxySettings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks {@code platform.outbound.*} and builds the {@link Installation}. Every problem is listed at once
 * ({@link OutboundConfigException}); problems name the property, never a value (a pasted secret stays out of the log).
 */
public final class OutboundSettings {

    private OutboundSettings() {
    }

    /** @throws OutboundConfigException listing every problem */
    public static Installation from(OutboundProperties p) {
        String prefix = OutboundProperties.PREFIX + ".";
        List<String> problems = new ArrayList<>();

        boolean hosted = false;
        if (p.getInstallation() == null) {
            problems.add(prefix + "installation is required: ON_PREMISES or HOSTED");
        } else {
            hosted = p.getInstallation() == OutboundProperties.InstallationMode.HOSTED;
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
        if (!problems.isEmpty()) {
            throw new OutboundConfigException(problems);
        }
        return new Installation(hosted, p.isAllowInternalAddresses(), p.isAllowSignInNone(), proxy, pem);
    }

    private static ProxySettings proxy(OutboundProperties.Proxy p, String prefix, List<String> problems) {
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
