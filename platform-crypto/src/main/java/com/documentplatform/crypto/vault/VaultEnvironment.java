package com.documentplatform.crypto.vault;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.CryptoException;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A Vault key service from environment variables, for the commands that run with a login given to that run only
 * (the setup command, {@code crypto destroy-customer-keys}). Never from a file, never printed. With the prefix
 * {@value #SETUP} the variables are:
 * <pre>
 * PLATFORM_SETUP_VAULT_ADDR           Vault's address (https://vault.internal:8200)
 * PLATFORM_SETUP_VAULT_NAMESPACE      optional (Vault Enterprise / HCP)
 * one login:
 *   PLATFORM_SETUP_VAULT_TOKEN        a token with the platform-crypto-setup policy
 *   PLATFORM_SETUP_VAULT_TOKEN_FILE   a file holding such a token
 *   PLATFORM_SETUP_VAULT_ROLE_ID and PLATFORM_SETUP_VAULT_SECRET_ID   an AppRole login
 *     (PLATFORM_SETUP_VAULT_APPROLE_MOUNT, default approle)
 * PLATFORM_SETUP_VAULT_TRANSIT_MOUNT  default transit
 * PLATFORM_SETUP_VAULT_KEY_PREFIX     default customer- (the same as the app's)
 * </pre>
 */
public final class VaultEnvironment {

    /** The set-up login's variables. */
    public static final String SETUP = "PLATFORM_SETUP_VAULT_";

    /** A connected key service; {@link #close()} ends the login (an AppRole token is revoked). */
    public record Connection(VaultClient client, VaultKeyService keys) implements AutoCloseable {
        public Connection {
            Objects.requireNonNull(client, "client");
            Objects.requireNonNull(keys, "keys");
        }

        @Override
        public void close() {
            client.close();
        }
    }

    private VaultEnvironment() {
    }

    /**
     * @param prefix the start of every variable name, for example {@value #SETUP}
     * @throws CryptoException {@link CryptoError#CRYPTO_SETTINGS_INVALID} naming the variable that is missing or not
     *                         valid (never its value)
     */
    public static Connection connect(Map<String, String> env, String prefix) {
        String addrVar = prefix + "ADDR";
        String tokenVar = prefix + "TOKEN";
        String tokenFileVar = prefix + "TOKEN_FILE";
        String roleVar = prefix + "ROLE_ID";
        String secretVar = prefix + "SECRET_ID";
        String address = blank(env.get(addrVar));
        if (address == null) {
            throw invalid(addrVar + " is not set in the environment.");
        }
        String token = blank(env.get(tokenVar));
        String tokenFile = blank(env.get(tokenFileVar));
        String roleId = blank(env.get(roleVar));
        String secretId = blank(env.get(secretVar));
        List<String> logins = new ArrayList<>();
        if (token != null) logins.add(tokenVar);
        if (tokenFile != null) logins.add(tokenFileVar);
        if (roleId != null || secretId != null) logins.add(roleVar + " + " + secretVar);
        if (logins.size() != 1) {
            throw invalid(logins.isEmpty()
                    ? "No key service login is set: give one of " + tokenVar + ", " + tokenFileVar + " or " + roleVar
                    + " with " + secretVar + "."
                    : "More than one key service login is set (" + String.join(", ", logins) + "): give one.");
        }
        if ((roleId == null) != (secretId == null)) {
            throw invalid("An AppRole login needs both " + roleVar + " and " + secretVar + ".");
        }
        URI uri;
        try {
            uri = URI.create(address);
        } catch (IllegalArgumentException e) {
            throw invalid(addrVar + " is not a valid address.");
        }
        try {
            VaultAuth auth = token != null ? VaultAuth.token(token)
                    : tokenFile != null ? VaultAuth.tokenFile(Path.of(tokenFile))
                    : VaultAuth.appRole(env.getOrDefault(prefix + "APPROLE_MOUNT", "approle"), roleId, secretId);
            VaultClient client = new VaultClient(uri, env.get(prefix + "NAMESPACE"), auth, VaultClient.DEFAULT_TIMEOUT);
            try {
                return new Connection(client, new VaultKeyService(client,
                        env.getOrDefault(prefix + "TRANSIT_MOUNT", VaultKeyService.DEFAULT_MOUNT),
                        env.getOrDefault(prefix + "KEY_PREFIX", VaultKeyService.DEFAULT_PREFIX)));
            } catch (RuntimeException e) {
                client.close();
                throw e;
            }
        } catch (CryptoException e) {
            throw invalid(e.reason());
        } catch (IllegalArgumentException e) {
            throw invalid(addrVar + " is not a valid address.");
        }
    }

    private static CryptoException invalid(String message) {
        return new CryptoException(CryptoError.CRYPTO_SETTINGS_INVALID, message);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
