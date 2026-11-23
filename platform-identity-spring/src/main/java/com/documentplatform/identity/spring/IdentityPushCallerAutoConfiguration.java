package com.documentplatform.identity.spring;

import com.documentplatform.identity.jdbc.IdentitySchema;
import com.documentplatform.identity.jdbc.PostgresPushCallerDirectory;
import com.documentplatform.identity.push.PushCaller;
import com.documentplatform.identity.push.PushCallerDirectory;
import com.documentplatform.identity.push.PushCallerSettings;
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
import java.time.Clock;

/**
 * The push-API caller check ({@link PushCaller}) when {@code platform.identity.push.product-code} is set: on the
 * identity tables of the app's DataSource ({@link PostgresPushCallerDirectory}, after {@link IdentitySchema#verify})
 * when the app has platform-identity-jdbc, or on the app's own {@link PushCallerDirectory}. There is no filter that
 * decides on its own: the app's controller calls {@link PushCaller#check} (with {@link PushCallerRequests}) and maps a
 * refusal to its own error answer. Backs off for app-defined beans.
 */
@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration")
@ConditionalOnProperty(prefix = PushCallerProperties.PREFIX, name = "product-code")
@EnableConfigurationProperties(PushCallerProperties.class)
public class IdentityPushCallerAutoConfiguration {

    /** On the identity tables, when platform-identity-jdbc and a DataSource are there. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.documentplatform.identity.jdbc.PostgresPushCallerDirectory")
    static class OnIdentityTables {

        @Bean
        @ConditionalOnMissingBean(PushCallerDirectory.class)
        @ConditionalOnBean(DataSource.class)
        PushCallerDirectory identityPushCallerDirectory(DataSource dataSource) {
            IdentitySchema.verify(dataSource);
            return new PostgresPushCallerDirectory(dataSource);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    PushCaller identityPushCaller(PushCallerProperties p, ObjectProvider<PushCallerDirectory> directory,
                                  ObjectProvider<Clock> clock) {
        PushCallerSettings settings;
        try {
            settings = new PushCallerSettings(p.getProductCode().strip(), p.getClientIdClaims(), p.getEntityClaim(),
                    p.getSourceClaim(), p.isRequireTokenAndCertificate(), p.getCacheFor());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalStateException("IDENTITY_PUSH_SETTINGS_INVALID: " + PushCallerProperties.PREFIX + ": "
                    + (e.getMessage() == null ? "a setting is missing" : e.getMessage()));
        }
        PushCallerDirectory d = directory.getIfUnique();
        if (d == null) {
            throw new IllegalStateException("IDENTITY_PUSH_SETTINGS_INVALID: " + PushCallerProperties.PREFIX
                    + ".product-code is set, but there is no PushCallerDirectory: add platform-identity-jdbc and the "
                    + "app's DataSource (the identity tables), or define a PushCallerDirectory bean.");
        }
        return new PushCaller(d, settings, clock.getIfAvailable(Clock::systemUTC));
    }
}
