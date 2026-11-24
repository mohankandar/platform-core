package com.documentplatform.integration.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The install's integration settings ({@code platform.integration.*}), checked at startup by
 * {@link IntegrationSettings#from}. Secrets never go here: the proxy password is a reference into the secret store.
 *
 * <pre>
 * (The installation, internal addresses, sign-in NONE, private CA and proxy are platform.outbound.* since P028:
 * platform-outbound-spring. The old names here are refused at startup, naming the new one.)
 * platform.integration.limits.connect-timeout=10s
 * platform.integration.limits.read-timeout=120s
 * platform.integration.limits.max-file-size=250MB
 * platform.integration.limits.max-documents=100
 * platform.integration.limits.max-response-size=1MB
 * platform.integration.limits.max-package-size=2GB
 * platform.integration.profile-folder=/etc/app/integration-profiles
 * platform.integration.health.enabled=true
 * platform.integration.health.cache-for=60s
 * platform.integration.credentials.expiry-warning-days=30,7,1
 * platform.integration.callbacks.first-delay=1m
 * platform.integration.callbacks.max-delay=6h
 * platform.integration.callbacks.give-up-after=24h
 * platform.integration.callbacks.keep-finished=30d
 * platform.integration.worker.enabled=true                    # callbacks out, timed certificate switches
 * platform.integration.worker.every=15s
 * platform.integration.scanner.type=NONE                      # NONE (off), CLAMD or ICAP
 * platform.integration.scanner.host=scanner.corp.example
 * platform.integration.scanner.port=3310                      # default 3310 (clamd), 1344 (ICAP)
 * platform.integration.scanner.icap-service=avscan
 * platform.integration.scanner.timeout=60s
 * platform.integration.intake.work-folder=/var/app/intake     # downloads while the app takes them
 * </pre>
 */
@ConfigurationProperties(prefix = IntegrationProperties.PREFIX)
public class IntegrationProperties {

    public static final String PREFIX = "platform.integration";

    /** Switches the module off when false. */
    private boolean enabled = true;
    /** Moved to platform.outbound.* (P028): set here, startup refuses, naming the new property. */
    private String installation;
    private String allowInternalAddresses;
    private String allowSignInNone;
    private String privateCaFile;
    private Map<String, String> proxy = new LinkedHashMap<>();
    private final Limits limits = new Limits();
    /** Where the installation's IT puts the published profile files. */
    private String profileFolder;
    private final Health health = new Health();
    private final Credentials credentials = new Credentials();
    private final Callbacks callbacks = new Callbacks();
    private final Worker worker = new Worker();

    /** Client certificates (mutual TLS). */
    public static class Credentials {
        /** The days before a certificate ends at which CredentialExpiry warns (1 to 365 each). */
        private List<Integer> expiryWarningDays = new ArrayList<>(List.of(30, 7, 1));

        public List<Integer> getExpiryWarningDays() {
            return expiryWarningDays;
        }

        public void setExpiryWarningDays(List<Integer> d) {
            this.expiryWarningDays = d;
        }
    }

    /** Signed callbacks out: the retry schedule and how long finished ones are kept. */
    public static class Callbacks {
        private Duration firstDelay = Duration.ofMinutes(1);
        private Duration maxDelay = Duration.ofHours(6);
        private Duration giveUpAfter = Duration.ofHours(24);
        private Duration keepFinished = Duration.ofDays(30);
        /**
         * Callbacks in (P044, SEC-8): whether the earlier {@code v1} signature, which does not cover the callback's ID,
         * is still accepted. Off by default; on only for a sender that cannot sign {@code v2} yet.
         */
        private boolean acceptV1Signatures;

        public boolean isAcceptV1Signatures() {
            return acceptV1Signatures;
        }

        public void setAcceptV1Signatures(boolean acceptV1Signatures) {
            this.acceptV1Signatures = acceptV1Signatures;
        }

        public Duration getFirstDelay() {
            return firstDelay;
        }

        public void setFirstDelay(Duration d) {
            this.firstDelay = d;
        }

        public Duration getMaxDelay() {
            return maxDelay;
        }

        public void setMaxDelay(Duration d) {
            this.maxDelay = d;
        }

        public Duration getGiveUpAfter() {
            return giveUpAfter;
        }

        public void setGiveUpAfter(Duration d) {
            this.giveUpAfter = d;
        }

        public Duration getKeepFinished() {
            return keepFinished;
        }

        public void setKeepFinished(Duration d) {
            this.keepFinished = d;
        }
    }

    /** The background worker: callbacks due, certificates whose switch time came, daily purge. */
    public static class Worker {
        private boolean enabled = true;
        private Duration every = Duration.ofSeconds(15);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getEvery() {
            return every;
        }

        public void setEvery(Duration every) {
            this.every = every;
        }
    }

    /** The virus scanner the customer provides (requirement 11); none: scanning is off. */
    public static class Scanner {
        public enum Type { NONE, CLAMD, ICAP }

        private Type type = Type.NONE;
        /** The scanner's host name or address (an internal address is allowed on premises). */
        private String host;
        /** Its port; default 3310 for clamd, 1344 for ICAP. */
        private Integer port;
        /** ICAP only: the service path ({@code avscan}, {@code srv_clamav}). */
        private String icapService = "avscan";
        /** To connect and for each answer (1 second to 10 minutes). */
        private Duration timeout = Duration.ofSeconds(60);

        public Type getType() {
            return type;
        }

        public void setType(Type type) {
            this.type = type;
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

        public String getIcapService() {
            return icapService;
        }

        public void setIcapService(String icapService) {
            this.icapService = icapService;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    /** Intake (API polling and the watched folder). */
    public static class Intake {
        /** Where downloads are kept while the app's handler runs (removed after each item); the system's by default. */
        private String workFolder;

        public String getWorkFolder() {
            return workFolder;
        }

        public void setWorkFolder(String workFolder) {
            this.workFolder = workFolder;
        }
    }

    private final Scanner scanner = new Scanner();
    private final Intake intake = new Intake();

    public Scanner getScanner() {
        return scanner;
    }

    public Intake getIntake() {
        return intake;
    }

    public Credentials getCredentials() {
        return credentials;
    }

    public Callbacks getCallbacks() {
        return callbacks;
    }

    public Worker getWorker() {
        return worker;
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

    public String getInstallation() {
        return installation;
    }

    public void setInstallation(String v) {
        this.installation = v;
    }

    public String getAllowInternalAddresses() {
        return allowInternalAddresses;
    }

    public void setAllowInternalAddresses(String v) {
        this.allowInternalAddresses = v;
    }

    public String getAllowSignInNone() {
        return allowSignInNone;
    }

    public void setAllowSignInNone(String v) {
        this.allowSignInNone = v;
    }

    public String getPrivateCaFile() {
        return privateCaFile;
    }

    public void setPrivateCaFile(String v) {
        this.privateCaFile = v;
    }

    public Map<String, String> getProxy() {
        return proxy;
    }

    public void setProxy(Map<String, String> v) {
        this.proxy = v == null ? new LinkedHashMap<>() : v;
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
