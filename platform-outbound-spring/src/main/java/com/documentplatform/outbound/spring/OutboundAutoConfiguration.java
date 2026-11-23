package com.documentplatform.outbound.spring;

import com.documentplatform.notifications.secret.SecretResolver;
import com.documentplatform.outbound.Installation;
import com.documentplatform.outbound.Outbound;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Conditional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.lang.System.Logger.Level;
import java.util.List;

/**
 * The installation's outbound rules from {@code platform.outbound.*}, when any of them is set ({@code installation}
 * is then required): the {@link Installation} and the {@link Outbound} every outbound call goes through (the
 * integrations, the identity test sign-in's issuer fetch). The app's {@link SecretResolver} reads the proxy password; startup refuses
 * a proxy with sign-in without one. Both beans back off for the app's own.
 */
@AutoConfiguration
@Conditional(OnOutboundSettings.class)
@EnableConfigurationProperties(OutboundProperties.class)
public class OutboundAutoConfiguration {

    private static final System.Logger LOG = System.getLogger(OutboundAutoConfiguration.class.getName());

    @Bean
    @ConditionalOnMissingBean
    Installation outboundInstallation(OutboundProperties properties) {
        Installation i = OutboundSettings.from(properties);
        LOG.log(Level.INFO, "Outbound calls: {0}", i);
        return i;
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    Outbound outbound(Installation installation, ObjectProvider<SecretResolver> secrets) {
        SecretResolver s = secrets.getIfUnique();
        if (installation.proxy() != null && installation.proxy().signIn() && s == null) {
            throw new OutboundConfigException(List.of("the app's SecretResolver bean is missing (the proxy password "
                    + "of " + OutboundProperties.PREFIX + ".proxy.password-secret is read through it)"));
        }
        return new Outbound(installation, s);
    }
}
