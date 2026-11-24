package com.documentplatform.integration.spring;

import com.documentplatform.integration.config.FieldLists;
import com.documentplatform.integration.config.Limits;
import com.documentplatform.integration.engine.CallEngine;
import com.documentplatform.integration.engine.PollEngine;
import com.documentplatform.integration.engine.TestAction;
import com.documentplatform.outbound.AddressGuard;
import com.documentplatform.integration.scan.ClamdScanner;
import com.documentplatform.integration.scan.FileScanner;
import com.documentplatform.integration.scan.IcapScanner;
import com.documentplatform.integration.scan.ScanEvents;
import com.documentplatform.integration.scan.Scanning;
import com.documentplatform.integration.watch.WatchFiles;
import com.documentplatform.integration.watch.WatchedFolder;
import com.documentplatform.integration.folder.ExportFiles;
import com.documentplatform.integration.folder.FolderExport;
import com.documentplatform.integration.folder.LocalFiles;
import com.documentplatform.outbound.Outbound;
import com.documentplatform.integration.jdbc.IntegrationSchema;
import com.documentplatform.integration.jdbc.PostgresIntegrationStore;
import com.documentplatform.integration.profile.ProfileFolder;
import com.documentplatform.outbound.Installation;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.envelope.ValueCipher;
import com.documentplatform.integration.callback.CallbackOutbox;
import com.documentplatform.integration.callback.Callbacks;
import com.documentplatform.integration.credential.CertificateSource;
import com.documentplatform.integration.credential.CredentialExpiry;
import com.documentplatform.integration.credential.CredentialStore;
import com.documentplatform.integration.credential.IntegrationCredentials;
import com.documentplatform.integration.jdbc.PostgresCallbackOutbox;
import com.documentplatform.integration.jdbc.PostgresCredentialStore;
import com.documentplatform.notifications.RetryPolicy;
import com.documentplatform.integration.store.IntegrationAudit;
import com.documentplatform.integration.store.IntegrationConfigs;
import com.documentplatform.integration.store.IntegrationStore;
import com.documentplatform.notifications.secret.SecretResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Wires platform-integration from the install config ({@code platform.integration.*}) and the app's beans.
 *
 * <p>The app supplies: its {@link SecretResolver} (secrets by reference: the proxy password, sign-in secrets) and its
 * {@link IntegrationAudit} (every configuration change and Test action); startup refuses with a specific message when
 * either is missing. Optional: {@link FieldLists} (the entities' case field lists, to check {@code {field:<key>}}
 * placeholders when a configuration is saved), a {@link Clock}, an {@link ExportFiles} (tests).
 *
 * <p>From platform-outbound-spring ({@code platform.outbound.*}): the {@link Installation} and the {@link Outbound}
 * every call goes through; startup refuses without them.
 *
 * <p>Beans: {@link IntegrationSettings}, the default {@link Limits} (bean
 * {@code integrationDefaultLimits}), {@link CallEngine}, {@link IntegrationStore} (Postgres on the
 * app's DataSource, after {@link IntegrationSchema#verify}: read only, never creates tables), {@link IntegrationConfigs},
 * {@link FolderExport}, {@link TestAction}, {@link ProfileFolder} (when {@code profile-folder} is set), since P026
 * {@link CredentialStore} and {@link IntegrationCredentials} (client certificates; the call engine presents them),
 * {@link CredentialExpiry}, {@link CallbackOutbox} and {@link Callbacks} (signed callbacks), the
 * {@link IntegrationWorker} (callbacks due, timed certificate switches; {@code worker.enabled}), since P027 the
 * scanner ({@link FileScanner} from {@code scanner.*}, {@link Scanning}; the app may supply {@link ScanEvents}),
 * {@link PollEngine} and {@link WatchedFolder} (the app may supply {@link WatchFiles}) and, with
 * Actuator, the {@code integrations} health contributor. Every bean backs off when the app defines its own.
 */
@AutoConfiguration(afterName = {"org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "com.documentplatform.outbound.spring.OutboundAutoConfiguration"})
@ConditionalOnProperty(prefix = IntegrationProperties.PREFIX, name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(IntegrationProperties.class)
public class IntegrationAutoConfiguration {

    private static final System.Logger LOG = System.getLogger(IntegrationAutoConfiguration.class.getName());

    /** The app's beans this module cannot run without, checked once with every missing one named. */
    public record AppBeans(SecretResolver secrets, IntegrationAudit audit) {
    }

    /**
     * The rules for callbacks the app receives (P044, SEC-8): {@code v2} signatures only, unless
     * {@code platform.integration.callbacks.accept-v1-signatures=true}. The app's receiver calls
     * {@link com.documentplatform.integration.callback.CallbackVerifier.Policy#verify}.
     */
    @Bean
    @ConditionalOnMissingBean
    com.documentplatform.integration.callback.CallbackVerifier.Policy callbackVerifierPolicy(
            IntegrationProperties properties) {
        boolean v1 = properties.getCallbacks().isAcceptV1Signatures();
        if (v1) {
            LOG.log(Level.WARNING, "Integrations: callbacks signed with v1 (without the callback ID) are accepted; "
                    + "turn platform.integration.callbacks.accept-v1-signatures off once every sender signs v2");
        }
        return new com.documentplatform.integration.callback.CallbackVerifier.Policy(v1, null);
    }

    @Bean
    @ConditionalOnMissingBean
    IntegrationSettings integrationSettings(IntegrationProperties properties,
                                            ObjectProvider<Installation> installation) {
        // The installation and the Outbound bean come from platform.outbound.* (platform-outbound-spring) since P028.
        IntegrationSettings s = IntegrationSettings.from(properties, installation.getIfUnique());
        LOG.log(Level.INFO, "Integrations: {0}", s.installation());
        return s;
    }

    @Bean
    @ConditionalOnMissingBean
    AppBeans integrationAppBeans(ObjectProvider<SecretResolver> secrets, ObjectProvider<IntegrationAudit> audit) {
        List<String> missing = new ArrayList<>();
        SecretResolver s = secrets.getIfUnique();
        IntegrationAudit a = audit.getIfUnique();
        if (s == null) {
            missing.add("the app's SecretResolver bean is missing (platform-integration reads secrets by reference "
                    + "through it: the proxy password and every sign-in secret)");
        }
        if (a == null) {
            missing.add("the app's IntegrationAudit bean is missing (every configuration change and Test action goes "
                    + "to the app's audit)");
        }
        if (!missing.isEmpty()) throw new IntegrationConfigException(missing);
        return new AppBeans(s, a);
    }

    /**
     * Without platform.outbound.* (and no app-defined Installation): startup stops here, whichever bean asks first,
     * with every integration problem listed, the missing platform.outbound.installation first.
     */
    @Bean
    @ConditionalOnMissingBean(Installation.class)
    Installation integrationMissingInstallation(IntegrationProperties properties) {
        IntegrationSettings.from(properties, null); // always throws: the installation is missing
        throw new IllegalStateException("unreachable");
    }

    /** An app that defines its own Installation but no Outbound gets one on it. */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(Outbound.class)
    Outbound integrationOutbound(Installation installation, AppBeans app) {
        return new Outbound(installation, app.secrets());
    }

    @Bean
    @ConditionalOnMissingBean(name = "integrationDefaultLimits")
    Limits integrationDefaultLimits(IntegrationSettings settings) {
        return settings.defaultLimits();
    }

    @Bean
    @ConditionalOnMissingBean
    CallEngine integrationCallEngine(Outbound outbound, AppBeans app, ObjectProvider<Clock> clock,
                                     ObjectProvider<CertificateSource> certificates) {
        return new CallEngine(outbound, app.secrets(), certificates.getIfAvailable(() -> CertificateSource.NONE),
                clock.getIfAvailable(Clock::systemUTC));
    }

    // ---- Client certificates (mutual TLS) ----

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DataSource.class)
    CredentialStore integrationCredentialStore(IntegrationStore checkedFirst, DataSource ds) {
        return new PostgresCredentialStore(ds); // the tables were checked with the integration store's
    }

    /**
     * Uploaded certificates are sealed with the app's {@link ValueCipher} (platform-crypto-spring's) under the
     * installation's customer ({@link CustomerId} bean). Without them uploads are refused
     * ({@code INT_CREDENTIAL_KEY_SERVICE_FAILED}) and keystores still work. An installation serving several customers
     * defines its own {@link IntegrationCredentials} bean with its entity-to-customer function.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(CredentialStore.class)
    IntegrationCredentials integrationCredentials(CredentialStore store, ObjectProvider<ValueCipher> cipher,
                                                  ObjectProvider<CustomerId> customer, AppBeans app,
                                                  ObjectProvider<Clock> clock) {
        CustomerId c = customer.getIfUnique();
        return new IntegrationCredentials(store, c == null ? null : cipher.getIfUnique(), entity -> {
            if (c == null) throw new IllegalStateException("no customer");
            return c;
        }, app.secrets(), app.audit(), clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(CredentialStore.class)
    CredentialExpiry integrationCredentialExpiry(CredentialStore store, IntegrationProperties properties) {
        try {
            return new CredentialExpiry(store, properties.getCredentials().getExpiryWarningDays());
        } catch (IllegalArgumentException e) {
            throw new IntegrationConfigException(List.of(IntegrationProperties.PREFIX
                    + ".credentials.expiry-warning-days: each is 1 to 365"));
        }
    }

    // ---- Callbacks ----

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DataSource.class)
    CallbackOutbox integrationCallbackOutbox(IntegrationStore checkedFirst, DataSource ds) {
        return new PostgresCallbackOutbox(ds);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(CallbackOutbox.class)
    Callbacks integrationCallbacks(CallbackOutbox outbox, IntegrationStore store, CallEngine engine,
                                   IntegrationProperties properties, ObjectProvider<Clock> clock) {
        IntegrationProperties.Callbacks p = properties.getCallbacks();
        RetryPolicy retry;
        try {
            retry = new RetryPolicy(p.getFirstDelay(), p.getMaxDelay(), p.getGiveUpAfter());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IntegrationConfigException(List.of(IntegrationProperties.PREFIX + ".callbacks: first-delay at "
                    + "least 1s, max-delay not below it, give-up-after not negative"));
        }
        return new Callbacks(outbox, store, engine, retry, Callbacks.DEFAULT_LEASE,
                clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = IntegrationProperties.PREFIX, name = "worker.enabled", havingValue = "true",
            matchIfMissing = true)
    IntegrationWorker integrationWorker(ObjectProvider<Callbacks> callbacks,
                                        ObjectProvider<IntegrationCredentials> credentials,
                                        IntegrationProperties properties, ObjectProvider<Clock> clock) {
        Duration every = properties.getWorker().getEvery();
        if (every == null || every.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IntegrationConfigException(List.of(IntegrationProperties.PREFIX + ".worker.every: at least 1s"));
        }
        return new IntegrationWorker(callbacks.getIfUnique(), credentials.getIfUnique(), every, properties.getCallbacks().getKeepFinished(),
                clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    IntegrationStore integrationStore(ObjectProvider<DataSource> dataSource) {
        DataSource ds = dataSource.getIfUnique();
        if (ds == null) {
            throw new IntegrationConfigException(List.of("platform-integration needs the app's DataSource (the "
                    + "database with the int_ tables) or an IntegrationStore bean"));
        }
        IntegrationSchema.verify(ds);
        return new PostgresIntegrationStore(ds);
    }

    @Bean
    @ConditionalOnMissingBean
    IntegrationConfigs integrationConfigs(IntegrationStore store, AppBeans app, Installation installation,
                                          ObjectProvider<Clock> clock, ObjectProvider<FieldLists> fieldLists) {
        return new IntegrationConfigs(store, app.audit(), installation, clock.getIfAvailable(Clock::systemUTC),
                fieldLists.getIfAvailable(() -> FieldLists.NONE));
    }

    @Bean
    @ConditionalOnMissingBean
    FolderExport integrationFolderExport(Installation installation, ObjectProvider<ExportFiles> files,
                                         ObjectProvider<Clock> clock) {
        return new FolderExport(installation, files.getIfAvailable(() -> LocalFiles.INSTANCE),
                clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    TestAction integrationTestAction(CallEngine engine, FolderExport folders, AppBeans app) {
        return new TestAction(engine, folders, app.audit());
    }

    // ---- Intake: the scanner, API polling, the watched folder ----

    /** The customer's scanner, from {@code scanner.*}; none (scanning off) when {@code scanner.type} is NONE. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression("'${platform.integration.scanner.type:NONE}'.toUpperCase() != 'NONE'")
    FileScanner integrationFileScanner(IntegrationSettings settings, Installation installation) {
        AddressGuard guard = new AddressGuard(installation.allowInternalAddresses());
        FileScanner s = settings.scannerType() == IntegrationProperties.Scanner.Type.ICAP
                ? new IcapScanner(settings.scanner(), settings.icapService(), guard)
                : new ClamdScanner(settings.scanner(), guard);
        LOG.log(Level.INFO, "Integrations: incoming files are scanned ({0})", s.kind());
        return s;
    }

    @Bean
    @ConditionalOnMissingBean
    Scanning integrationScanning(ObjectProvider<FileScanner> scanner, ObjectProvider<ScanEvents> events) {
        FileScanner s = scanner.getIfUnique();
        if (s == null) LOG.log(Level.INFO, "Integrations: no virus scanner is configured; scanning is off");
        return new Scanning(s, events.getIfAvailable(() -> ScanEvents.NONE));
    }

    @Bean
    @ConditionalOnMissingBean
    PollEngine integrationPollEngine(CallEngine engine, Scanning scanning, IntegrationSettings settings) {
        return settings.intakeWorkFolder() == null ? new PollEngine(engine, scanning)
                : new PollEngine(engine, scanning, settings.intakeWorkFolder());
    }

    @Bean
    @ConditionalOnMissingBean
    WatchedFolder integrationWatchedFolder(Installation installation, ObjectProvider<WatchFiles> files,
                                           Scanning scanning, ObjectProvider<Clock> clock) {
        return new WatchedFolder(files.getIfAvailable(() -> LocalFiles.INSTANCE), installation, scanning,
                clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = IntegrationProperties.PREFIX, name = "profile-folder")
    ProfileFolder integrationProfileFolder(IntegrationSettings settings) {
        ProfileFolder f = new ProfileFolder(settings.profileFolder());
        LOG.log(Level.INFO, "Integration profile folder: {0} file(s)", f.files().size());
        return f;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthContributor")
    // Replaced by entries in platform-monitoring-spring's "dependencies" contributor when that module is there (P032).
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass(
            "com.documentplatform.monitoring.spring.MonitoringAutoConfiguration")
    @ConditionalOnProperty(prefix = IntegrationProperties.PREFIX, name = "health.enabled", havingValue = "true",
            matchIfMissing = true)
    static class Health {

        @Bean(name = "integrationsHealthContributor")
        @ConditionalOnMissingBean(name = "integrationsHealthContributor")
        IntegrationHealthContributor integrationsHealthContributor(IntegrationConfigs configs, CallEngine engine,
                                                                   FolderExport folders, WatchedFolder watched,
                                                                   Scanning scanning, IntegrationSettings settings,
                                                                   ObjectProvider<Clock> clock) {
            return new IntegrationHealthContributor(configs, engine, folders, watched, scanning,
                    settings.healthCacheFor(), clock.getIfAvailable(Clock::systemUTC));
        }
    }
}
