package com.documentplatform.integration.spring;

import com.documentplatform.integration.config.FieldLists;
import com.documentplatform.integration.config.Limits;
import com.documentplatform.integration.engine.CallEngine;
import com.documentplatform.integration.engine.TestAction;
import com.documentplatform.integration.folder.ExportFiles;
import com.documentplatform.integration.folder.FolderExport;
import com.documentplatform.integration.folder.LocalFiles;
import com.documentplatform.integration.http.Outbound;
import com.documentplatform.integration.jdbc.IntegrationSchema;
import com.documentplatform.integration.jdbc.PostgresIntegrationStore;
import com.documentplatform.integration.profile.ProfileFolder;
import com.documentplatform.integration.safety.Installation;
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
 * <p>Beans: {@link IntegrationSettings}, {@link Installation}, the default {@link Limits} (bean
 * {@code integrationDefaultLimits}), {@link Outbound}, {@link CallEngine}, {@link IntegrationStore} (Postgres on the
 * app's DataSource, after {@link IntegrationSchema#verify}: read only, never creates tables), {@link IntegrationConfigs},
 * {@link FolderExport}, {@link TestAction}, {@link ProfileFolder} (when {@code profile-folder} is set), since P026
 * {@link CredentialStore} and {@link IntegrationCredentials} (client certificates; the call engine presents them),
 * {@link CredentialExpiry}, {@link CallbackOutbox} and {@link Callbacks} (signed callbacks), the
 * {@link IntegrationWorker} (callbacks due, timed certificate switches; {@code worker.enabled}) and, with
 * Actuator, the {@code integrations} health contributor. Every bean backs off when the app defines its own.
 */
@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration")
@ConditionalOnProperty(prefix = IntegrationProperties.PREFIX, name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(IntegrationProperties.class)
public class IntegrationAutoConfiguration {

    private static final System.Logger LOG = System.getLogger(IntegrationAutoConfiguration.class.getName());

    /** The app's beans this module cannot run without, checked once with every missing one named. */
    public record AppBeans(SecretResolver secrets, IntegrationAudit audit) {
    }

    @Bean
    @ConditionalOnMissingBean
    IntegrationSettings integrationSettings(IntegrationProperties properties) {
        IntegrationSettings s = IntegrationSettings.from(properties);
        Installation i = s.installation();
        // Since P026 the HTTP client sends the proxy sign-in on CONNECT itself: no JVM option is needed any more.
        LOG.log(Level.INFO, "Integrations: {0}", i);
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

    @Bean
    @ConditionalOnMissingBean
    Installation integrationInstallation(IntegrationSettings settings) {
        return settings.installation();
    }

    @Bean
    @ConditionalOnMissingBean(name = "integrationDefaultLimits")
    Limits integrationDefaultLimits(IntegrationSettings settings) {
        return settings.defaultLimits();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    Outbound integrationOutbound(Installation installation, AppBeans app) {
        return new Outbound(installation, app.secrets());
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
    @ConditionalOnProperty(prefix = IntegrationProperties.PREFIX, name = "health.enabled", havingValue = "true",
            matchIfMissing = true)
    static class Health {

        @Bean(name = "integrationsHealthContributor")
        @ConditionalOnMissingBean(name = "integrationsHealthContributor")
        IntegrationHealthContributor integrationsHealthContributor(IntegrationConfigs configs, CallEngine engine,
                                                                   FolderExport folders, IntegrationSettings settings,
                                                                   ObjectProvider<Clock> clock) {
            return new IntegrationHealthContributor(configs, engine, folders, settings.healthCacheFor(),
                    clock.getIfAvailable(Clock::systemUTC));
        }
    }
}
