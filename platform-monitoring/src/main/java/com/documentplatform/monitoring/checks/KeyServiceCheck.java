package com.documentplatform.monitoring.checks;

import com.documentplatform.crypto.KeyService;
import com.documentplatform.monitoring.DependencyCheck;
import com.documentplatform.monitoring.MonitoringCode;
import com.documentplatform.monitoring.Verdict;

import java.util.Map;
import java.util.Objects;

/**
 * The key service and the customer's keys, from {@link KeyService#health()}: its {@code CRYPTO_*} code tells apart
 * can't reach, login refused, a customer key missing, permission denied on a key and key disabled. Required at
 * start-up when the product's {@code customerKeys} is {@code required}.
 */
public final class KeyServiceCheck implements DependencyCheck {

    private final KeyService keys;
    private final boolean required;

    public KeyServiceCheck(KeyService keys, boolean required) {
        this.keys = Objects.requireNonNull(keys, "keys");
        this.required = required;
    }

    @Override
    public String name() {
        return "key-service";
    }

    @Override
    public boolean requiredAtStartup() {
        return required;
    }

    @Override
    public Verdict check() {
        KeyService.Health h = keys.health();
        if (h.up()) return Verdict.working();
        if (h.code() != null) return Verdict.down(h.code(), Map.of("keyService", String.valueOf(h.keyService())));
        return Verdict.down(MonitoringCode.MON_KEY_SERVICE_DOWN, Map.of("keyService", String.valueOf(h.keyService())));
    }
}
