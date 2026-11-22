package com.documentplatform.crypto.spring;

import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.vault.VaultAuth;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The checked crypto config. {@code toString()} shows no login secret.
 *
 * @param customer the installation's customer, or null when no tenant ID is configured
 */
public record CryptoConfig(URI address, String namespace, VaultAuth auth, Duration timeout, String transitMount,
                           String keyPrefix, CustomerId customer) {

    private static final Pattern MOUNT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}(?:/[A-Za-z0-9][A-Za-z0-9._-]"
            + "{0,63}){0,3}");
    private static final Pattern PREFIX = Pattern.compile("[a-z0-9][a-z0-9_-]{0,47}");

    /**
     * @param licensingTenantId {@code platform.licensing.tenant-id}, used when {@code platform.crypto.tenant-id} is
     *                          not set; may be null
     * @throws CryptoConfigException listing every problem
     */
    public static CryptoConfig from(CryptoProperties p, String licensingTenantId) {
        String prefix = CryptoProperties.PREFIX + ".vault.";
        List<String> problems = new ArrayList<>();
        CryptoProperties.Vault v = p.getVault();

        URI address = null;
        String a = blank(v.getAddress());
        if (a == null) {
            problems.add(prefix + "address is missing: give Vault's address, for example https://vault.internal:8200");
        } else {
            try {
                address = URI.create(a);
                if (!("https".equals(address.getScheme()) || "http".equals(address.getScheme()))
                        || address.getHost() == null) {
                    problems.add(prefix + "address must be an http or https address with a host");
                    address = null;
                }
            } catch (IllegalArgumentException e) {
                problems.add(prefix + "address is not a valid address");
            }
        }

        VaultAuth auth = null;
        CryptoProperties.Login login = v.getLogin();
        if (login == null) {
            problems.add(prefix + "login is missing: APPROLE, TOKEN_FILE or TOKEN");
        } else {
            try {
                auth = switch (login) {
                    case TOKEN -> {
                        if (blank(v.getToken()) == null) {
                            problems.add(prefix + "token is missing (login=TOKEN; use only for local runs)");
                            yield null;
                        }
                        yield VaultAuth.token(v.getToken());
                    }
                    case TOKEN_FILE -> {
                        if (blank(v.getTokenFile()) == null) {
                            problems.add(prefix + "token-file is missing (login=TOKEN_FILE)");
                            yield null;
                        }
                        yield VaultAuth.tokenFile(Path.of(v.getTokenFile().strip()));
                    }
                    case APPROLE -> appRole(v, prefix, problems);
                };
            } catch (CryptoException e) {
                problems.add(prefix + "login: " + e.getMessage());
            }
        }
        if (login != CryptoProperties.Login.TOKEN && blank(v.getToken()) != null) {
            problems.add(prefix + "token is set but login is " + login + "; remove it");
        }

        String namespace = blank(v.getNamespace());
        String mount = blank(v.getTransitMount());
        if (mount == null || !MOUNT.matcher(mount).matches()) {
            problems.add(prefix + "transit-mount is not a valid mount path (for example transit)");
        }
        String keyPrefix = blank(v.getKeyPrefix());
        if (keyPrefix == null || !PREFIX.matcher(keyPrefix).matches()) {
            problems.add(prefix + "key-prefix is 1 to 48 of a-z 0-9 _ - and starts with a letter or digit");
        }
        Duration timeout = v.getTimeout();
        if (timeout == null || timeout.compareTo(Duration.ofSeconds(1)) < 0
                || timeout.compareTo(Duration.ofMinutes(2)) > 0) {
            problems.add(prefix + "timeout must be between 1s and 2m");
        }

        CustomerId customer = null;
        String tenant = blank(p.getTenantId()) != null ? blank(p.getTenantId()) : blank(licensingTenantId);
        if (tenant != null) {
            try {
                customer = CustomerId.fromTenantId(tenant);
            } catch (IllegalArgumentException e) {
                problems.add(CryptoProperties.PREFIX + ".tenant-id (or platform.licensing.tenant-id) must be TEN- "
                        + "followed by 1 to 60 of A-Z 0-9 -");
            }
        }
        if (!problems.isEmpty()) {
            throw new CryptoConfigException(problems);
        }
        return new CryptoConfig(address, namespace, auth, timeout, mount, keyPrefix, customer);
    }

    private static VaultAuth appRole(CryptoProperties.Vault v, String prefix, List<String> problems) {
        String roleId = blank(v.getRoleId());
        String secretId = blank(v.getSecretId());
        String secretFile = blank(v.getSecretIdFile());
        if (roleId == null) {
            problems.add(prefix + "role-id is missing (login=APPROLE)");
        }
        if (secretId != null && secretFile != null) {
            problems.add(prefix + "secret-id and secret-id-file are both set; give one");
            return null;
        }
        if (secretFile != null) {
            try {
                secretId = blank(Files.readString(Path.of(secretFile), StandardCharsets.UTF_8));
                if (secretId == null) {
                    problems.add(prefix + "secret-id-file is empty");
                }
            } catch (IOException | RuntimeException e) {
                problems.add(prefix + "secret-id-file cannot be read (" + e.getClass().getSimpleName() + ")");
            }
        } else if (secretId == null) {
            problems.add(prefix + "secret-id (or secret-id-file) is missing (login=APPROLE)");
        }
        if (roleId == null || secretId == null) {
            return null;
        }
        return VaultAuth.appRole(blank(v.getApproleMount()) == null ? "approle" : v.getApproleMount().strip(),
                roleId, secretId);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
