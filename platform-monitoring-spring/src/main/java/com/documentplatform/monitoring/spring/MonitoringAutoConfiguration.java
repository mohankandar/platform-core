package com.documentplatform.monitoring.spring;

import com.documentplatform.monitoring.CheckRunner;
import com.documentplatform.monitoring.DependencyCheck;
import com.documentplatform.monitoring.DependencyStatus;
import com.documentplatform.monitoring.StartupChecks;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Wires the dependency self-checks: the built-in ones from the app's modules and beans ({@link BuiltInChecks}) and the
 * app's own {@link DependencyCheck} beans into one {@link DependencyStatus}; refuses to start while a required check
 * is DOWN ({@code platform.monitoring.startup-checks}); then runs every check on its schedule. With Actuator: the
 * readiness indicator {@code readinessState} (the app's readiness and the required checks) and
 * the informational {@code dependencies} contributor; with
 * Micrometer: the metrics; in a servlet app: the request filter. Every bean backs off for the app's own.
 */
@AutoConfiguration(afterName = {"org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "com.documentplatform.licensing.spring.LicensingAutoConfiguration",
        "com.documentplatform.crypto.spring.CryptoAutoConfiguration",
        "com.documentplatform.notifications.spring.NotificationsAutoConfiguration",
        "com.documentplatform.identity.spring.IdentityIssuerFetchAutoConfiguration",
        "com.documentplatform.identity.spring.IdentityLoginConnectionStoreAutoConfiguration",
        "com.documentplatform.integration.spring.IntegrationAutoConfiguration"},
        beforeName = "org.springframework.boot.actuate.autoconfigure.availability.AvailabilityHealthContributorAutoConfiguration")
@ConditionalOnProperty(prefix = MonitoringProperties.PREFIX, name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(MonitoringProperties.class)
public class MonitoringAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    DependencyStatus platformDependencyStatus(ListableBeanFactory beans, MonitoringProperties p, Environment env,
                                              ObjectProvider<DependencyCheck> appChecks,
                                              ObjectProvider<MonitoringListener> listeners,
                                              ObjectProvider<Clock> clock) {
        MonitoringConfigException.check(p);
        Clock c = clock.getIfAvailable(Clock::systemUTC);
        List<DependencyCheck> checks = new ArrayList<>(BuiltInChecks.all(beans, p, env, c));
        appChecks.orderedStream().forEach(checks::add);
        List<Supplier<List<DependencyCheck>>> sources = new ArrayList<>();
        if (ClassUtils.isPresent("com.documentplatform.integration.store.IntegrationStore", getClass().getClassLoader())) {
            Supplier<List<DependencyCheck>> integrations = IntegrationSource.of(beans);
            if (integrations != null) sources.add(integrations);
        }
        DependencyStatus status = new DependencyStatus(checks, sources, new CheckRunner(c), c, p.getEvery());
        listeners.orderedStream().forEach(l -> status.onChange(l::changed));
        return status;
    }

    /** Start-up refusal, then the scheduler. */
    @Bean
    SmartInitializingSingleton platformMonitoringStart(DependencyStatus status, MonitoringProperties p) {
        return () -> {
            if (p.isStartupChecks()) StartupChecks.require(status);
            status.start(p.getTick());
        };
    }

    @Bean
    @ConditionalOnMissingBean
    DiagnosticBundles platformDiagnosticBundles(DependencyStatus status, Environment env,
                                                ObjectProvider<DataSource> ds, ObjectProvider<AppVersions> app,
                                                MonitoringProperties properties) {
        AppVersions none = java.util.Map::of;
        return new DiagnosticBundles(status, env, ds.getIfUnique(), app.getIfAvailable(() -> none),
                properties.getBundleProperties());
    }

    /** Readiness and the dependency list, with Actuator. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    static class Health {

        /**
         * Readiness: the app's own readiness state and the checks required at start-up (never integrations). Takes
         * the place of Spring Boot's {@code readinessState} indicator, so the readiness probe needs no setting.
         */
        @Bean
        @ConditionalOnMissingBean(name = "readinessStateHealthIndicator")
        org.springframework.boot.actuate.health.HealthIndicator readinessStateHealthIndicator(
                DependencyStatus status, ObjectProvider<org.springframework.boot.availability.ApplicationAvailability>
                        availability) {
            return DependencyHealth.readiness(status, availability.getIfAvailable());
        }

        @Bean
        @ConditionalOnMissingBean(name = "dependenciesHealthIndicator")
        org.springframework.boot.actuate.health.HealthIndicator dependenciesHealthIndicator(DependencyStatus status) {
            return DependencyHealth.all(status);
        }
    }

    /** Metrics, with Micrometer. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    static class Metrics {

        @Bean
        @ConditionalOnBean(type = "io.micrometer.core.instrument.MeterRegistry")
        MonitoringMetrics platformMonitoringMetrics(io.micrometer.core.instrument.MeterRegistry registry,
                                                    DependencyStatus status, ListableBeanFactory beans) {
            MonitoringMetrics m = new MonitoringMetrics(registry);
            status.onResult(m::record);
            if (ClassUtils.isPresent("com.documentplatform.integration.engine.CallEngine", getClass().getClassLoader())) {
                MonitoringMetrics.Attempts.bind(m, beans);
            }
            return m;
        }
    }

    /** The request filter: X-Request-Id and the reference in the MDC. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "jakarta.servlet.Filter")
    static class Web {

        @Bean
        @ConditionalOnMissingBean
        RequestReferenceFilter platformRequestReferenceFilter() {
            return new RequestReferenceFilter();
        }
    }

}
