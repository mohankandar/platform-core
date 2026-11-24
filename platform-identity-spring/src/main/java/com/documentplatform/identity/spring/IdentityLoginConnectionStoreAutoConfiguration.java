package com.documentplatform.identity.spring;

import com.documentplatform.identity.jdbc.IdentitySchema;
import com.documentplatform.identity.jdbc.LicenceSigningKey;
import com.documentplatform.identity.jdbc.PostgresLoginConnectionStore;
import com.documentplatform.identity.signin.DevelopmentLicence;
import com.documentplatform.identity.store.LoginConnectionStore;
import com.documentplatform.licensing.LicenceService;
import com.documentplatform.licensing.spring.internal.LicensingConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * The login-connection store on the identity JDBC tables (when the app has platform-identity-jdbc and a DataSource;
 * after {@link IdentitySchema#verify}, read only), and whether the licence in force is signed by a development key
 * (from platform-licensing-spring's licence service when the app has it; else never, so http issuers are refused).
 * Both back off for app-defined beans.
 */
@AutoConfiguration(afterName = {"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "com.documentplatform.licensing.spring.LicensingAutoConfiguration"})
@ConditionalOnClass(name = "com.documentplatform.identity.jdbc.PostgresLoginConnectionStore")
public class IdentityLoginConnectionStoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(LoginConnectionStore.class)
    @ConditionalOnBean(DataSource.class)
    LoginConnectionStore identityLoginConnectionStore(DataSource dataSource) {
        IdentitySchema.verify(dataSource);
        return new PostgresLoginConnectionStore(dataSource);
    }

    /** From the licence in force, when platform-licensing-spring is wired. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.documentplatform.licensing.spring.internal.LicensingConfig")
    static class FromLicensing {

        @Bean
        @ConditionalOnMissingBean(DevelopmentLicence.class)
        @ConditionalOnBean({LicenceService.class, LicensingConfig.class})
        DevelopmentLicence identityDevelopmentLicence(LicenceService service, LicensingConfig config) {
            return LicenceSigningKey.of(service, config.trustedKeys());
        }
    }

    /** Without licensing: no development licence (http issuers refused). */
    @Bean
    @ConditionalOnMissingBean(DevelopmentLicence.class)
    DevelopmentLicence identityNoDevelopmentLicence() {
        return DevelopmentLicence.NONE;
    }
}
