package com.documentplatform.notifications.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The install config of notifications, prefix {@code platform.notifications}. Bound as given and checked at startup by
 * {@link NotificationsConfig#from(NotificationsProperties)}, which stops the app with a specific message for each
 * wrong value. Secrets never go here: the mail password and the webhook addresses are given as references into the
 * secret store, read by the app's {@code SecretResolver} when sending.
 */
@ConfigurationProperties(prefix = NotificationsProperties.PREFIX)
public class NotificationsProperties {

    public static final String PREFIX = "platform.notifications";

    /** Switches the whole module off when false. */
    private boolean enabled = true;

    /** The customer's mail server, used by every e-mail destination. Leave out the host for no e-mail. */
    private final Mail mail = new Mail();

    /** Teams destinations: destination id → secret reference of the Workflows webhook address. */
    private Map<String, String> teams = new LinkedHashMap<>();

    /** Slack destinations: destination id → secret reference of the incoming webhook address. */
    private Map<String, String> slack = new LinkedHashMap<>();

    private final Webhook webhook = new Webhook();
    private final Retry retry = new Retry();
    private final Sender sender = new Sender();

    /** How long a sender holds a message it is sending; must be longer than the send timeouts. */
    private Duration lease = Duration.ofMinutes(5);

    /** How long sent and failed messages stay in the outbox. */
    private Duration retention = Duration.ofDays(30);

    /** How often condition alerts and due summaries are raised. */
    private Duration tickInterval = Duration.ofMinutes(1);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Mail getMail() {
        return mail;
    }

    public Map<String, String> getTeams() {
        return teams;
    }

    public void setTeams(Map<String, String> teams) {
        this.teams = teams;
    }

    public Map<String, String> getSlack() {
        return slack;
    }

    public void setSlack(Map<String, String> slack) {
        this.slack = slack;
    }

    public Webhook getWebhook() {
        return webhook;
    }

    public Retry getRetry() {
        return retry;
    }

    public Sender getSender() {
        return sender;
    }

    public Duration getLease() {
        return lease;
    }

    public void setLease(Duration lease) {
        this.lease = lease;
    }

    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration retention) {
        this.retention = retention;
    }

    public Duration getTickInterval() {
        return tickInterval;
    }

    public void setTickInterval(Duration tickInterval) {
        this.tickInterval = tickInterval;
    }

    public static class Mail {
        /** Host name or address of the mail server. */
        private String host;
        /** Port; default 587 for STARTTLS, 465 for TLS, 25 for NONE. */
        private Integer port;
        /** STARTTLS (recommended), TLS or NONE (relay inside the customer's network only). */
        private String security = "STARTTLS";
        /** Login user name; leave out for a relay that takes mail without a login. */
        private String username;
        /** Secret reference of the login password (required with a user name). Never the password itself. */
        private String passwordSecret;
        /** Sender address, for example notifications@customer.example. */
        private String fromAddress;
        /** Display name of the sender. */
        private String fromName;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration readTimeout = Duration.ofSeconds(30);
        /**
         * How the sender signs in: PASSWORD (default: user name and password, or none for a relay; the customer's own
         * mail server such as Exchange) or OAUTH2_MICROSOFT (Microsoft 365 with an Entra app, see {@link Oauth}).
         */
        private String auth = "PASSWORD";
        /** Microsoft 365 sign-in, used only with auth OAUTH2_MICROSOFT. */
        private final Oauth oauth = new Oauth();

        public String getAuth() {
            return auth;
        }

        public void setAuth(String auth) {
            this.auth = auth;
        }

        public Oauth getOauth() {
            return oauth;
        }

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

        public String getSecurity() {
            return security;
        }

        public void setSecurity(String security) {
            this.security = security;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPasswordSecret() {
            return passwordSecret;
        }

        public void setPasswordSecret(String passwordSecret) {
            this.passwordSecret = passwordSecret;
        }

        public String getFromAddress() {
            return fromAddress;
        }

        public void setFromAddress(String fromAddress) {
            this.fromAddress = fromAddress;
        }

        public String getFromName() {
            return fromName;
        }

        public void setFromName(String fromName) {
            this.fromName = fromName;
        }

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
    }

    /**
     * {@code platform.notifications.mail.oauth.*}: the Entra app registration that sends as the sender mailbox. IDs
     * and a secret reference only; the client secret or the certificate stays in the secret store.
     */
    public static class Oauth {
        /** The Microsoft Entra directory (tenant) ID, a GUID (or the verified domain, for example customer.onmicrosoft.com). */
        private String directoryId;
        /** The app registration's application (client) ID, a GUID. */
        private String clientId;
        /** CERTIFICATE (recommended) or SECRET. */
        private String credentialType;
        /** Secret reference of the certificate with its private key (PEM), or of the client secret. Never the value. */
        private String credentialSecret;
        /** The mailbox the app sends as, for example notifications@customer.example. */
        private String senderMailbox;
        /** Microsoft's sign-in address; leave out (https://login.microsoftonline.com) unless you use a national cloud. */
        private String authority;

        public String getDirectoryId() {
            return directoryId;
        }

        public void setDirectoryId(String directoryId) {
            this.directoryId = directoryId;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getCredentialType() {
            return credentialType;
        }

        public void setCredentialType(String credentialType) {
            this.credentialType = credentialType;
        }

        public String getCredentialSecret() {
            return credentialSecret;
        }

        public void setCredentialSecret(String credentialSecret) {
            this.credentialSecret = credentialSecret;
        }

        public String getSenderMailbox() {
            return senderMailbox;
        }

        public void setSenderMailbox(String senderMailbox) {
            this.senderMailbox = senderMailbox;
        }

        public String getAuthority() {
            return authority;
        }

        public void setAuthority(String authority) {
            this.authority = authority;
        }

        boolean anySet() {
            return directoryId != null || clientId != null || credentialType != null || credentialSecret != null
                    || senderMailbox != null || authority != null;
        }
    }

    public static class Webhook {
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(30);

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }
    }

    /** Retries of temporary failures: first delay, doubling up to the maximum, given up after give-up-after. */
    public static class Retry {
        private Duration firstDelay = Duration.ofMinutes(1);
        private Duration maxDelay = Duration.ofMinutes(30);
        private Duration giveUpAfter = Duration.ofHours(24);

        public Duration getFirstDelay() {
            return firstDelay;
        }

        public void setFirstDelay(Duration firstDelay) {
            this.firstDelay = firstDelay;
        }

        public Duration getMaxDelay() {
            return maxDelay;
        }

        public void setMaxDelay(Duration maxDelay) {
            this.maxDelay = maxDelay;
        }

        public Duration getGiveUpAfter() {
            return giveUpAfter;
        }

        public void setGiveUpAfter(Duration giveUpAfter) {
            this.giveUpAfter = giveUpAfter;
        }
    }

    /** The background sender: woken by each publish, and polling for retries and other instances' messages. */
    public static class Sender {
        private Duration pollInterval = Duration.ofSeconds(10);
        private int batchSize = 100;

        public Duration getPollInterval() {
            return pollInterval;
        }

        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }
}
