package com.documentplatform.outbound.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * The installation's outbound settings ({@code platform.outbound.*}), shared by every module that calls out (the
 * integrations, the identity test sign-in), checked at startup by {@link OutboundSettings#from}. Secrets never go
 * here: the proxy password is a reference into the secret store.
 *
 * <pre>
 * platform.outbound.installation=ON_PREMISES               # ON_PREMISES or HOSTED; no outbound calls when unset
 * platform.outbound.allow-internal-addresses=false         # on premises only: loopback/private addresses, http
 * platform.outbound.allow-sign-in-none=false               # on premises only: integration sign-in NONE (audited)
 * platform.outbound.private-ca-file=/etc/app/private-ca.pem
 * platform.outbound.proxy.host=proxy.corp.example
 * platform.outbound.proxy.port=3128
 * platform.outbound.proxy.bypass=.corp.example,files01
 * platform.outbound.proxy.user=svc-app
 * platform.outbound.proxy.password-secret=outbound/proxy-password
 * </pre>
 *
 * Until P028 these were {@code platform.integration.installation}, {@code .allow-internal-addresses},
 * {@code .allow-sign-in-none}, {@code .private-ca-file} and {@code .proxy.*}; integration refuses the old names.
 */
@ConfigurationProperties(prefix = OutboundProperties.PREFIX)
public class OutboundProperties {

    public static final String PREFIX = "platform.outbound";

    /** Where the app runs: our hosted service, or the customer's own systems. */
    public enum InstallationMode { ON_PREMISES, HOSTED }

    /** ON_PREMISES or HOSTED. Without it there are no outbound beans (integration then refuses to start). */
    private InstallationMode installation;
    /** On premises only: loopback and private addresses (and plain http) may be called. */
    private boolean allowInternalAddresses = false;
    /** On premises only: an integration's sign-in NONE may be configured. */
    private boolean allowSignInNone = false;
    /** The customer's private certificate authorities (a PEM file), trusted in addition to the system's. */
    private String privateCaFile;
    private final Proxy proxy = new Proxy();

    /** The outbound proxy; none when no field is set. */
    public static class Proxy {
        private String host;
        private Integer port;
        /** Hosts reached directly: a name, or a suffix starting with a dot. */
        private List<String> bypass = new ArrayList<>();
        /** The proxy's Basic sign-in user; goes with password-secret. */
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
            this.bypass = bypass == null ? new ArrayList<>() : bypass;
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
}
