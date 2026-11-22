package com.documentplatform.crypto.spring;

import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.KeyService;
import com.documentplatform.crypto.envelope.RecordCipher;
import com.documentplatform.crypto.envelope.StreamCipher;
import com.documentplatform.crypto.envelope.ValueCipher;
import com.documentplatform.crypto.fingerprint.Fingerprinter;
import com.documentplatform.crypto.vault.VaultClient;
import com.documentplatform.crypto.vault.VaultKeyService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Builds the key service and the ciphers from the install config ({@code platform.crypto.*}).
 *
 * <ul>
 *   <li>{@link KeyService}: Vault Transit ({@link VaultKeyService} over a {@link VaultClient} with the configured login),
 *       unless the app defines its own (for example the in-memory key service in tests). The config is checked at
 *       startup ({@link CryptoConfig}); Vault itself is not called at startup, so a Vault outage never stops the app:
 *       it shows in the health indicator, and each crypto call fails with its {@code CRYPTO_*} code.</li>
 *   <li>{@link ValueCipher}, {@link RecordCipher}, {@link StreamCipher}, {@link Fingerprinter} on that key service.</li>
 *   <li>{@link CustomerId} of {@code platform.crypto.tenant-id} (or {@code platform.licensing.tenant-id}) when set.</li>
 *   <li>{@link KeyServiceHealthIndicator} when the app uses Actuator.</li>
 * </ul>
 * {@code platform.crypto.enabled=false} switches the module off.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = CryptoProperties.PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CryptoProperties.class)
public class CryptoAutoConfiguration {

    /** The Vault side: only when the app has no key service of its own. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(KeyService.class)
    static class VaultConfiguration {

        @Bean
        CryptoConfig cryptoConfig(CryptoProperties properties, Environment env) {
            return CryptoConfig.from(properties, env.getProperty("platform.licensing.tenant-id"));
        }

        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean
        VaultClient cryptoVaultClient(CryptoConfig config) {
            return new VaultClient(config.address(), config.namespace(), config.auth(), config.timeout());
        }

        @Bean
        KeyService keyService(VaultClient vault, CryptoConfig config) {
            return new VaultKeyService(vault, config.transitMount(), config.keyPrefix());
        }
    }

    /** True when platform.crypto.tenant-id or platform.licensing.tenant-id is set. */
    static final class TenantConfigured implements org.springframework.context.annotation.Condition {
        @Override
        public boolean matches(org.springframework.context.annotation.ConditionContext context,
                               org.springframework.core.type.AnnotatedTypeMetadata metadata) {
            Environment env = context.getEnvironment();
            for (String name : new String[]{CryptoProperties.PREFIX + ".tenant-id", "platform.licensing.tenant-id"}) {
                String v = env.getProperty(name);
                if (v != null && !v.isBlank()) {
                    return true;
                }
            }
            return false;
        }
    }

    @Bean
    @ConditionalOnMissingBean
    @org.springframework.context.annotation.Conditional(TenantConfigured.class)
    CustomerId cryptoCustomer(CryptoProperties properties, Environment env,
                              org.springframework.beans.factory.ObjectProvider<CryptoConfig> config) {
        CryptoConfig checked = config.getIfAvailable();
        if (checked != null && checked.customer() != null) {
            return checked.customer(); // every config problem was already reported together
        }
        String tenant = properties.getTenantId() != null && !properties.getTenantId().isBlank()
                ? properties.getTenantId().strip() : env.getProperty("platform.licensing.tenant-id");
        if (tenant == null || tenant.isBlank()) {
            throw new CryptoConfigException(java.util.List.of(CryptoProperties.PREFIX + ".tenant-id is missing (and "
                    + "platform.licensing.tenant-id is not set): give this installation's customer ID (TEN-...); the "
                    + "setup command writes it into platform-tenant.properties"));
        }
        try {
            return CustomerId.fromTenantId(tenant.strip());
        } catch (IllegalArgumentException e) {
            throw new CryptoConfigException(java.util.List.of(CryptoProperties.PREFIX + ".tenant-id (or "
                    + "platform.licensing.tenant-id) must be TEN- followed by 1 to 60 of A-Z 0-9 -"));
        }
    }

    @Bean
    @ConditionalOnMissingBean
    ValueCipher valueCipher(KeyService keys) {
        return new ValueCipher(keys);
    }

    @Bean
    @ConditionalOnMissingBean
    RecordCipher recordCipher(KeyService keys) {
        return new RecordCipher(keys);
    }

    @Bean
    @ConditionalOnMissingBean
    StreamCipher streamCipher(KeyService keys) {
        return new StreamCipher(keys);
    }

    @Bean
    @ConditionalOnMissingBean
    Fingerprinter fingerprinter(KeyService keys) {
        return new Fingerprinter(keys);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    static class HealthConfiguration {
        @Bean
        @ConditionalOnMissingBean
        KeyServiceHealthIndicator keyServiceHealthIndicator(KeyService keys) {
            return new KeyServiceHealthIndicator(keys);
        }
    }
}
