package com.documentplatform.crypto.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The install config of crypto, prefix {@code platform.crypto}. Checked at startup by {@link CryptoConfig#from}, which
 * stops the app with a specific message for each wrong value. The app's Vault login has the
 * {@code platform-crypto-app} policy only: it cannot create, rotate or delete keys (the setup command creates them).
 *
 * <p>Secrets (token, AppRole secret ID) are best given as files ({@code token-file}, {@code secret-id-file}) or
 * environment variables; they never appear in a message, a log line or the health endpoint.
 */
@ConfigurationProperties(prefix = CryptoProperties.PREFIX)
public class CryptoProperties {

    public static final String PREFIX = "platform.crypto";

    /** Switches the module off when false (the app then defines its own KeyService, or none). */
    private boolean enabled = true;

    /**
     * This installation's customer (tenant) ID ({@code TEN-...}), whose keys protect the data. When not set,
     * {@code platform.licensing.tenant-id} is used.
     */
    private String tenantId;

    private final Vault vault = new Vault();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public Vault getVault() {
        return vault;
    }

    /** How to log in to Vault. */
    public enum Login {
        /** AppRole (role ID + secret ID): recommended for servers. */
        APPROLE,
        /** A token file kept up to date by Vault Agent or the orchestrator, read at each login. */
        TOKEN_FILE,
        /** A plain token: local runs only. */
        TOKEN
    }

    /** {@code platform.crypto.vault.*}. */
    public static class Vault {

        /** Vault's address, for example https://vault.internal:8200. */
        private String address;

        /** The Vault Enterprise / HCP namespace; empty for none. */
        private String namespace;

        private Login login = Login.APPROLE;

        /** login=TOKEN: the token. */
        private String token;

        /** login=TOKEN_FILE: the file holding the token. */
        private String tokenFile;

        /** login=APPROLE: where the AppRole method is mounted. */
        private String approleMount = "approle";

        /** login=APPROLE: the role ID. */
        private String roleId;

        /** login=APPROLE: the secret ID (or {@link #secretIdFile}). */
        private String secretId;

        /** login=APPROLE: a file holding the secret ID, read once at startup. */
        private String secretIdFile;

        /** The Transit mount. */
        private String transitMount = "transit";

        /** The start of every customer key name; the same as the setup command's. */
        private String keyPrefix = "customer-";

        /** For connecting and for each call (1 second to 2 minutes). */
        private Duration timeout = Duration.ofSeconds(10);

        public String getAddress() {
            return address;
        }

        public void setAddress(String address) {
            this.address = address;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public Login getLogin() {
            return login;
        }

        public void setLogin(Login login) {
            this.login = login;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getTokenFile() {
            return tokenFile;
        }

        public void setTokenFile(String tokenFile) {
            this.tokenFile = tokenFile;
        }

        public String getApproleMount() {
            return approleMount;
        }

        public void setApproleMount(String approleMount) {
            this.approleMount = approleMount;
        }

        public String getRoleId() {
            return roleId;
        }

        public void setRoleId(String roleId) {
            this.roleId = roleId;
        }

        public String getSecretId() {
            return secretId;
        }

        public void setSecretId(String secretId) {
            this.secretId = secretId;
        }

        public String getSecretIdFile() {
            return secretIdFile;
        }

        public void setSecretIdFile(String secretIdFile) {
            this.secretIdFile = secretIdFile;
        }

        public String getTransitMount() {
            return transitMount;
        }

        public void setTransitMount(String transitMount) {
            this.transitMount = transitMount;
        }

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        @Override
        public String toString() {
            return "Vault[" + address + ", login " + login + "]";
        }
    }
}
