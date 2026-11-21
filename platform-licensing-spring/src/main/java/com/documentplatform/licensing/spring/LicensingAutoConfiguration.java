package com.documentplatform.licensing.spring;

import com.documentplatform.licensing.ClockGuard;
import com.documentplatform.licensing.LastSeenStore;
import com.documentplatform.licensing.LicenceService;
import com.documentplatform.licensing.LicenceStore;
import com.documentplatform.licensing.LicenceVerifier;
import com.documentplatform.licensing.NoticeLog;
import com.documentplatform.licensing.jdbc.LicensingSchema;
import com.documentplatform.licensing.jdbc.PostgresLastSeenStore;
import com.documentplatform.licensing.jdbc.PostgresLicenceStore;
import com.documentplatform.licensing.jdbc.PostgresNoticeLog;
import com.documentplatform.licensing.jdbc.PostgresUsageLedger;
import com.documentplatform.licensing.usage.UsageLedger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.List;

/**
 * Builds the {@link LicenceService} from the install config ({@code platform.licensing.*}) and the app's
 * {@link DataSource} holding the {@code lic_} tables.
 *
 * <p>At startup: the config is checked ({@link LicensingConfig}), the tables are checked ({@link LicensingSchema#verify},
 * read only), and the stored licence is read ({@link LicenceStartupCheck}). Any of these can stop the app with a
 * specific message. Expiry, grace and a server clock behind never stop it.
 *
 * <p>Every bean backs off when the app defines its own of the same type (for example in-memory stores in tests).
 * {@code platform.licensing.enabled=false} switches the module off.
 */
@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration")
@ConditionalOnProperty(prefix = LicensingProperties.PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(LicensingProperties.class)
public class LicensingAutoConfiguration {

    @Bean
    LicensingConfig licensingConfig(LicensingProperties properties) {
        return LicensingConfig.from(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    LicensingClock licensingClock() {
        return new LicensingClock(Clock.systemUTC());
    }

    /** The checked tables: created only when a Postgres store is, so apps with their own stores need no database. */
    @Bean
    @Lazy
    LicensingTables licensingTables(ObjectProvider<DataSource> dataSource) {
        DataSource ds = dataSource.getIfUnique();
        if (ds == null) {
            throw new LicensingConfigException(List.of("Licensing needs the app's DataSource (the database holding "
                    + "the lic_ tables): none, or several without one marked @Primary, was found"));
        }
        LicensingSchema.verify(ds);
        return new LicensingTables(ds);
    }

    @Bean
    @ConditionalOnMissingBean
    LicenceStore licenceStore(LicensingTables tables) {
        return new PostgresLicenceStore(tables.dataSource());
    }

    @Bean
    @ConditionalOnMissingBean
    LastSeenStore licensingLastSeenStore(LicensingTables tables) {
        return new PostgresLastSeenStore(tables.dataSource());
    }

    @Bean
    @ConditionalOnMissingBean
    UsageLedger licensingUsageLedger(LicensingTables tables) {
        return new PostgresUsageLedger(tables.dataSource());
    }

    /** Used only by licensing notifications; lazy, so apps without notifications never touch its table. */
    @Bean
    @Lazy
    @ConditionalOnMissingBean
    NoticeLog licensingNoticeLog(LicensingTables tables) {
        return new PostgresNoticeLog(tables.dataSource());
    }

    @Bean
    @ConditionalOnMissingBean
    LicenceService licenceService(LicensingConfig config, LicenceStore store, LastSeenStore lastSeen,
                                  UsageLedger ledger, LicensingClock clock) {
        return new LicenceService(store, new LicenceVerifier(config.trustedKeys()),
                new ClockGuard(clock.clock(), config.clockTolerance(), lastSeen), ledger, config.productCode(),
                config.tenantId());
    }

    @Bean
    LicenceStartupCheck licenceStartupCheck(LicenceService service) {
        return new LicenceStartupCheck(service);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    static class HealthConfiguration {
        @Bean
        @ConditionalOnMissingBean
        LicensingHealthIndicator licensingHealthIndicator(LicenceService service) {
            return new LicensingHealthIndicator(service);
        }
    }

    /** The DataSource whose lic_ tables passed the startup check. */
    record LicensingTables(DataSource dataSource) {
    }
}
