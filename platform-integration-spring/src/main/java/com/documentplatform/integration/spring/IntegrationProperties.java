package com.documentplatform.integration.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The install's integration settings ({@code platform.integration.*}), checked at startup by
 * {@link IntegrationSettings#from}. Secrets never go here: the proxy password is a reference into the secret store.
 *
 * <pre>
 * platform.integration.installation=ON_PREMISES               # required: ON_PREMISES or HOSTED
 * platform.integration.allow-internal-addresses=false         # on premises only: loopback/private addresses, http
 * platform.integration.allow-sign-in-none=false               # on premises only: sign-in NONE (warned, audited)
 * platform.integration.private-ca-file=/etc/app/private-ca.pem
 * platform.integration.proxy.host=proxy.corp.example
 * platform.integration.proxy.port=3128
 * platform.integration.proxy.bypass=.corp.example,files01
 * platform.integration.proxy.user=svc-app
 * platform.integration.proxy.password-secret=integration/proxy-password
 * platform.integration.limits.connect-timeout=10s
 * platform.integration.limits.read-timeout=120s
 * platform.integration.limits.max-file-size=250MB
 * platform.integration.limits.max-documents=100
 * platform.integration.limits.max-response-size=1MB
 * platform.integration.limits.max-package-size=2GB
 * platform.integration.profile-folder=/etc/app/integration-profiles
 * platform.integration.health.enabled=true
 * platform.integration.health.cache-for=60s
 * </pre>
 */
@ConfigurationProperties(prefix = IntegrationProperties.PREFIX)
public class IntegrationProperties {

    public static final String PREFIX = "platform.integration";

    /** Where the app runs: our hosted service, or the customer's own systems. */
    public enum InstallationMode { ON_PREMISES, HOSTED }

    /** Switches the module off when false. */
    private boolean enabled = true;
    /** Required: ON_PREMISES or HOSTED. */
    private InstallationMode installation;
    /** On premises only: loopback and private addresses (and plain http) may be called. */
    private boolean allowInternalAddresses = false;
    /** On premises only: sign-in NONE may be configured. */
    private boolean allowSignInNone = false;
    /** The customer's private certificate authorities (a PEM file), trusted in addition to the system's. */
    private String privateCaFile;
    private final Proxy proxy = new Proxy();
    private final Limits limits = new Limits();
    /** Where the installation's IT puts the published profile files. */
    private String profileFolder;
    private final Health health = new Health();

    public static class Proxy {
        private String host;
        private Integer port;
        /** Hosts that do not use the proxy: exact names or suffixes starting with a dot. */
        private List<String> bypass = new ArrayList<>();
        /** The proxy's Basic sign-in user, when it needs one. */
        private String user;
        /** Where the proxy password is kept in the secret store (a reference, never the password). */
        private String passwordSecret;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public Integer getPort() {
            return port;
        }

        public void setPort(Integer port) {
            this.port = port;
        }

        public List<String> getBypass() {
            return bypass;
        }

        public void setBypass(List<String> bypass) {
            this.bypass = bypass;
        }

        public String getUser() {
            return user;
        }

        public void setUser(String user) {
            this.user = user;
        }

        public String getPasswordSecret() {
            return passwordSecret;
        }

        public void setPasswordSecret(String passwordSecret) {
            this.passwordSecret = passwordSecret;
        }
    }

    /** The default limits for new integrations (each configuration keeps its own copy). */
    public static class Limits {
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration readTimeout = Duration.ofSeconds(120);
        private DataSize maxFileSize = DataSize.ofMegabytes(250);
        private Integer maxDocuments = 100;
        private DataSize maxResponseSize = DataSize.ofMegabytes(1);
        private DataSize maxPackageSize = DataSize.ofGigabytes(2);

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public DataSize getMaxFileSize() {
            return maxFileSize;
        }

        public void setMaxFileSize(DataSize maxFileSize) {
            this.maxFileSize = maxFileSize;
        }

        public Integer getMaxDocuments() {
            return maxDocuments;
        }

        public void setMaxDocuments(Integer maxDocuments) {
            this.maxDocuments = maxDocuments;
        }

        public DataSize getMaxResponseSize() {
            return maxResponseSize;
        }

        public void setMaxResponseSize(DataSize maxResponseSize) {
            this.maxResponseSize = maxResponseSize;
        }

        public DataSize getMaxPackageSize() {
            return maxPackageSize;
        }

        public void setMaxPackageSize(DataSize maxPackageSize) {
            this.maxPackageSize = maxPackageSize;
        }
    }

    public static class Health {
        /** One health contributor per enabled integration under {@code /actuator/health/integrations}. */
        private boolean enabled = true;
        /** How long one integration's health result is kept before it is checked again (5 seconds to 1 hour). */
        private Duration cacheFor = Duration.ofSeconds(60);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getCacheFor() {
            return cacheFor;
        }

        public void setCacheFor(Duration cacheFor) {
            this.cacheFor = cacheFor;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public InstallationMode getInstallation() {
        return installation;
    }

    public void setInstallation(InstallationMode installation) {
        this.installation = installation;
    }

    public boolean isAllowInternalAddresses() {
        return allowInternalAddresses;
    }

    public void setAllowInternalAddresses(boolean allowInternalAddresses) {
        this.allowInternalAddresses = allowInternalAddresses;
    }

    public boolean isAllowSignInNone() {
        return allowSignInNone;
    }

    public void setAllowSignInNone(boolean allowSignInNone) {
        this.allowSignInNone = allowSignInNone;
    }

    public String getPrivateCaFile() {
        return privateCaFile;
    }

    public void setPrivateCaFile(String privateCaFile) {
        this.privateCaFile = privateCaFile;
    }

    public Proxy getProxy() {
        return proxy;
    }

    public Limits getLimits() {
        return limits;
    }

    public String getProfileFolder() {
        return profileFolder;
    }

    public void setProfileFolder(String profileFolder) {
        this.profileFolder = profileFolder;
    }

    public Health getHealth() {
        return health;
    }
}
