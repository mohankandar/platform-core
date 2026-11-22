package com.documentplatform.crypto.vault;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.CryptoException;

import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * How the app gets its Vault token. {@code toString()} never shows a token or a secret ID.
 *
 * <ul>
 *   <li>{@link Token}: a token given to the app (local runs, or a token an orchestrator hands over);</li>
 *   <li>{@link TokenFile}: a file with the token, read at every login, so an agent (Vault Agent, the orchestrator)
 *       can replace it; recommended when a token is used;</li>
 *   <li>{@link AppRole}: a login with Vault's AppRole method (role ID + secret ID), recommended for servers. The
 *       token it gives is renewed by logging in again when Vault refuses it, and revoked on
 *       {@link VaultClient#close()}.</li>
 * </ul>
 */
public sealed interface VaultAuth permits VaultAuth.Token, VaultAuth.TokenFile, VaultAuth.AppRole {

    Pattern MOUNT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}(?:/[A-Za-z0-9][A-Za-z0-9._-]{0,63}){0,3}");

    static Token token(String value) {
        return new Token(value);
    }

    static TokenFile tokenFile(Path file) {
        return new TokenFile(file);
    }

    static AppRole appRole(String mount, String roleId, String secretId) {
        return new AppRole(mount, roleId, secretId);
    }

    record Token(String value) implements VaultAuth {
        public Token {
            if (value == null || value.isBlank()) {
                throw new CryptoException(CryptoError.CRYPTO_SETTINGS_INVALID, "the Vault token is empty");
            }
            value = value.strip();
        }

        @Override
        public String toString() {
            return "Token[(not shown)]";
        }
    }

    record TokenFile(Path file) implements VaultAuth {
        public TokenFile {
            Objects.requireNonNull(file, "file");
        }

        @Override
        public String toString() {
            return "TokenFile[" + file + "]";
        }
    }

    /**
     * @param mount    where the AppRole method is mounted, normally {@code approle}
     * @param roleId   the role ID (not secret on its own, but not shown either)
     * @param secretId the secret ID
     */
    record AppRole(String mount, String roleId, String secretId) implements VaultAuth {
        public AppRole {
            if (mount == null || !MOUNT.matcher(mount).matches()) {
                throw new CryptoException(CryptoError.CRYPTO_SETTINGS_INVALID,
                        "the Vault AppRole mount is not a valid path");
            }
            if (roleId == null || roleId.isBlank()) {
                throw new CryptoException(CryptoError.CRYPTO_SETTINGS_INVALID, "the Vault AppRole role ID is empty");
            }
            if (secretId == null || secretId.isBlank()) {
                throw new CryptoException(CryptoError.CRYPTO_SETTINGS_INVALID, "the Vault AppRole secret ID is empty");
            }
        }

        @Override
        public String toString() {
            return "AppRole[" + mount + ", (ids not shown)]";
        }
    }
}
