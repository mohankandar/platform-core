package com.documentplatform.identity.spring;

import com.documentplatform.identity.signin.DevelopmentLicence;
import com.documentplatform.identity.signin.IssuerFetch;
import com.documentplatform.identity.signin.OidcTestSignIn;
import com.documentplatform.identity.store.LoginConnectionStore;
import com.documentplatform.identity.store.LoginConnections;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * {@link LoginConnections} (the IT Admin screen's drafts, test sign-in, activate, rollback, discard) when there is a
 * {@link LoginConnectionStore} (the identity JDBC store, or the app's) and an {@link IssuerFetch} (over
 * shared outbound rules, platform-outbound, or the app's). Backs off for an app-defined bean.
 */
@AutoConfiguration(after = {IdentityIssuerFetchAutoConfiguration.class,
        IdentityLoginConnectionStoreAutoConfiguration.class})
public class IdentityLoginConnectionsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(LoginConnections.class)
    @ConditionalOnBean({LoginConnectionStore.class, IssuerFetch.class})
    LoginConnections identityLoginConnections(LoginConnectionStore store, IssuerFetch fetch,
                                              ObjectProvider<DevelopmentLicence> licence, ObjectProvider<Clock> clock) {
        return new LoginConnections(store, new OidcTestSignIn(fetch, clock.getIfAvailable(Clock::systemUTC)),
                licence.getIfAvailable(() -> DevelopmentLicence.NONE));
    }
}
