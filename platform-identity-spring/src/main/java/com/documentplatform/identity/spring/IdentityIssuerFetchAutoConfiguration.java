package com.documentplatform.identity.spring;

import com.documentplatform.identity.signin.IssuerFetch;
import com.documentplatform.outbound.Outbound;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * The login-connection test sign-in's fetch of the issuer's discovery document and keys over the shared outbound
 * rules ({@link OutboundIssuerFetch}: https only, the address guard, the installation's proxy and private certificate
 * authority, no redirects, size limits), when the {@link Outbound} bean is there (platform-outbound-spring, from
 * {@code platform.outbound.*}; platform-integration is not needed). Backs off for an app-defined {@link IssuerFetch}.
 */
@AutoConfiguration(afterName = "com.documentplatform.outbound.spring.OutboundAutoConfiguration")
public class IdentityIssuerFetchAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(IssuerFetch.class)
    @ConditionalOnBean(Outbound.class)
    IssuerFetch identityIssuerFetch(Outbound outbound) {
        return new OutboundIssuerFetch(outbound);
    }
}
