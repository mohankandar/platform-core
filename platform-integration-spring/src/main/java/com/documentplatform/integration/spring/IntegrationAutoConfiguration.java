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
import com.documentplatform.integration.safety.JvmProxySignIn;
import com.documentplatform.integration.store.IntegrationAudit;
import com.documentplatform.integration.store.IntegrationConfigs;
import com.documentplatform.integration.store.IntegrationStore;
import com.documentplatform.notifications.secret.SecretResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.lang.System.Logger.Level;
import java.time.Clock;
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
 * {@link FolderExport}, {@link TestAction}, {@link ProfileFolder} (when {@code profile-folder} is set) and, with
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
        if (i.proxy() != null && i.proxy().signIn() && JvmProxySignIn.current().basicDisabledForTunnels()) {
            LOG.log(Level.WARNING, "The outbound proxy has sign-in, but this Java runtime does not send Basic sign-in "
                    + "for https (CONNECT): https calls through the proxy will fail with "
                    + "INT_PROXY_SIGN_IN_DISABLED_BY_JVM. Start the app with " + JvmProxySignIn.TUNNEL_START_OPTION);
        }
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
    CallEngine integrationCallEngine(Outbound outbound, AppBeans app, ObjectProvider<Clock> clock) {
        return new CallEngine(outbound, app.secrets(), clock.getIfAvailable(Clock::systemUTC));
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
