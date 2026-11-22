package com.documentplatform.identity.spring;

import com.documentplatform.identity.signin.IssuerFetch;
import com.documentplatform.integration.http.Outbound;
import com.documentplatform.integration.http.OutboundIssuerFetch;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * The login-connection test sign-in's fetch of the issuer's discovery document and keys over platform-integration's
 * outbound rules ({@link OutboundIssuerFetch}: https only, the address guard, the installation's proxy and private
 * certificate authority, no redirects, size limits), when platform-integration is on the classpath and its
 * {@link Outbound} is a bean (platform-integration-spring). Backs off for an app-defined {@link IssuerFetch}.
 */
@AutoConfiguration(afterName = "com.documentplatform.integration.spring.IntegrationAutoConfiguration")
@ConditionalOnClass(name = "com.documentplatform.integration.http.OutboundIssuerFetch")
public class IdentityIssuerFetchAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(IssuerFetch.class)
    @ConditionalOnBean(Outbound.class)
    IssuerFetch identityIssuerFetch(Outbound outbound) {
        return new OutboundIssuerFetch(outbound);
    }
}
