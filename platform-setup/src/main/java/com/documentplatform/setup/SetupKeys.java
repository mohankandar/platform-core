package com.documentplatform.setup;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.KeyService;
import com.documentplatform.crypto.vault.VaultAuth;
import com.documentplatform.crypto.vault.VaultClient;
import com.documentplatform.crypto.vault.VaultKeyService;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The customer's keys in the key service: setup creates them (wrapping key and fingerprint key, see
 * {@code docs/design/crypto.md}) with a login given to this run only, under the {@code platform-crypto-setup} policy.
 * The running app's policy cannot create or rotate keys. Existing keys are left alone, so a rerun is safe.
 *
 * <p>Key service settings come from the environment only (never from a file, never printed):
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
public final class SetupKeys {

    public static final String VAULT_ADDR = "PLATFORM_SETUP_VAULT_ADDR";
    public static final String VAULT_NAMESPACE = "PLATFORM_SETUP_VAULT_NAMESPACE";
    public static final String VAULT_TOKEN = "PLATFORM_SETUP_VAULT_TOKEN";
    public static final String VAULT_TOKEN_FILE = "PLATFORM_SETUP_VAULT_TOKEN_FILE";
    public static final String VAULT_ROLE_ID = "PLATFORM_SETUP_VAULT_ROLE_ID";
    public static final String VAULT_SECRET_ID = "PLATFORM_SETUP_VAULT_SECRET_ID";
    public static final String VAULT_APPROLE_MOUNT = "PLATFORM_SETUP_VAULT_APPROLE_MOUNT";
    public static final String VAULT_TRANSIT_MOUNT = "PLATFORM_SETUP_VAULT_TRANSIT_MOUNT";
    public static final String VAULT_KEY_PREFIX = "PLATFORM_SETUP_VAULT_KEY_PREFIX";

    /** A key service for one setup run, and what to do when the run is over (Vault: revoke the login). */
    public record Connection(KeyService keys, Runnable close) {
        public Connection {
            Objects.requireNonNull(keys, "keys");
            Objects.requireNonNull(close, "close");
        }
    }

    /** How setup reaches the key service; the default reads the environment ({@link #vault}). */
    @FunctionalInterface
    public interface Connector {
        Connection connect(Map<String, String> env);
    }

    /** What the keys step found. */
    enum State {
        /** The customer's keys are missing: apply creates them. */
        CREATE,
        /** Both keys exist: left alone. */
        PRESENT,
        /** --no-keys: not looked at. */
        SKIPPED
    }

    private SetupKeys() {
    }

    /** The default connector: Vault Transit, settings from the environment. */
    public static Connection vault(Map<String, String> env) {
        String address = blank(env.get(VAULT_ADDR));
        if (address == null) {
            throw new SetupException(SetupRefusal.SETUP_KEY_SERVICE_SETTINGS_MISSING, VAULT_ADDR + " is not set in the "
                    + "environment.");
        }
        String token = blank(env.get(VAULT_TOKEN));
        String tokenFile = blank(env.get(VAULT_TOKEN_FILE));
        String roleId = blank(env.get(VAULT_ROLE_ID));
        String secretId = blank(env.get(VAULT_SECRET_ID));
        List<String> logins = new ArrayList<>();
        if (token != null) logins.add(VAULT_TOKEN);
        if (tokenFile != null) logins.add(VAULT_TOKEN_FILE);
        if (roleId != null || secretId != null) logins.add(VAULT_ROLE_ID + " + " + VAULT_SECRET_ID);
        if (logins.size() != 1) {
            throw new SetupException(SetupRefusal.SETUP_KEY_SERVICE_SETTINGS_MISSING, logins.isEmpty()
                    ? "No key service login is set: give one of " + VAULT_TOKEN + ", " + VAULT_TOKEN_FILE + " or "
                    + VAULT_ROLE_ID + " with " + VAULT_SECRET_ID + "."
                    : "More than one key service login is set (" + String.join(", ", logins) + "): give one.");
        }
        if ((roleId == null) != (secretId == null)) {
            throw new SetupException(SetupRefusal.SETUP_KEY_SERVICE_SETTINGS_MISSING, "An AppRole login needs both "
                    + VAULT_ROLE_ID + " and " + VAULT_SECRET_ID + ".");
        }
        try {
            VaultAuth auth = token != null ? VaultAuth.token(token)
                    : tokenFile != null ? VaultAuth.tokenFile(Path.of(tokenFile))
                    : VaultAuth.appRole(env.getOrDefault(VAULT_APPROLE_MOUNT, "approle"), roleId, secretId);
            VaultClient client = new VaultClient(URI.create(address), env.get(VAULT_NAMESPACE), auth,
                    VaultClient.DEFAULT_TIMEOUT);
            KeyService keys = new VaultKeyService(client,
                    env.getOrDefault(VAULT_TRANSIT_MOUNT, VaultKeyService.DEFAULT_MOUNT),
                    env.getOrDefault(VAULT_KEY_PREFIX, VaultKeyService.DEFAULT_PREFIX));
            return new Connection(keys, client::close);
        } catch (CryptoException e) {
            throw new SetupException(SetupRefusal.SETUP_KEY_SERVICE_SETTINGS_MISSING, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new SetupException(SetupRefusal.SETUP_KEY_SERVICE_SETTINGS_MISSING, VAULT_ADDR + " is not a valid "
                    + "address.");
        }
    }

    /** Whether the customer's keys exist (read only). */
    static State check(KeyService keys, CustomerId customer) {
        try {
            keys.wrappingKey(customer);
            keys.fingerprintKey(customer);
            return State.PRESENT;
        } catch (CryptoException e) {
            if (e.code() == CryptoError.CRYPTO_KEY_MISSING) {
                return State.CREATE;
            }
            throw failed(e);
        }
    }

    /** Creates whichever of the customer's keys is missing. */
    static void create(KeyService keys, CustomerId customer) {
        try {
            keys.createKeys(customer);
            if (check(keys, customer) != State.PRESENT) {
                throw new SetupException(SetupRefusal.SETUP_KEY_SERVICE_FAILED, "The customer's keys are still missing "
                        + "after creating them.");
            }
        } catch (CryptoException e) {
            throw failed(e);
        }
    }

    static SetupException failed(CryptoException e) {
        return new SetupException(SetupRefusal.SETUP_KEY_SERVICE_FAILED, "The key service refused or failed ("
                + e.getMessage() + ").");
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
