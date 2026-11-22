package com.documentplatform.crypto.spring;

import com.documentplatform.crypto.KeyService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Actuator health of the key service ({@code /actuator/health/keyService}): UP or DOWN, the key service kind, and on
 * DOWN the {@code CRYPTO_*} code and a short neutral reason. Never an address with credentials, a token, a secret ID
 * or a key.
 */
public final class KeyServiceHealthIndicator implements HealthIndicator {

    private final KeyService keys;

    public KeyServiceHealthIndicator(KeyService keys) {
        this.keys = keys;
    }

    @Override
    public Health health() {
        KeyService.Health h = keys.health();
        Health.Builder b = h.up() ? Health.up() : Health.down();
        b.withDetail("keyService", h.keyService() == null ? keys.id() : h.keyService());
        if (h.code() != null) {
            b.withDetail("code", h.code().name());
        }
        if (h.message() != null) {
            b.withDetail("message", h.message());
        }
        return b.build();
    }
}
