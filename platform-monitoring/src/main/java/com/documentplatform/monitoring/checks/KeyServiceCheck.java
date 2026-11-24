package com.documentplatform.monitoring.checks;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.KeyService;
import com.documentplatform.monitoring.DependencyCheck;
import com.documentplatform.monitoring.MonitoringCode;
import com.documentplatform.monitoring.Verdict;

import java.util.Map;
import java.util.Objects;

/**
 * The key service, from {@link KeyService#health()}, and with a customer (P047) that customer's two keys: there, and usable
 * (a data key is wrapped and a fingerprint made; both are thrown away). Its {@code CRYPTO_*} code tells apart can't reach,
 * login refused, a customer key missing, permission denied on a key and key disabled. Required at start-up when the
 * product's {@code customerKeys} is {@code required}; when it is not, missing keys are no problem (set-up may run without).
 */
public final class KeyServiceCheck implements DependencyCheck {

    private final KeyService keys;
    private final CustomerId customer;
    private final boolean required;

    /** The key service only, no customer's keys. */
    public KeyServiceCheck(KeyService keys, boolean required) {
        this(keys, null, required);
    }

    /** @param customer whose keys are checked, or null for the key service only */
    public KeyServiceCheck(KeyService keys, CustomerId customer, boolean required) {
        this.keys = Objects.requireNonNull(keys, "keys");
        this.customer = customer;
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
        if (h.up()) return customer == null ? Verdict.working() : customerKeys(h);
        if (h.code() != null) return Verdict.down(h.code(), Map.of("keyService", String.valueOf(h.keyService())));
        return Verdict.down(MonitoringCode.MON_KEY_SERVICE_DOWN, Map.of("keyService", String.valueOf(h.keyService())));
    }

    private Verdict customerKeys(KeyService.Health h) {
        try {
            keys.newDataKey(customer).close();
            keys.fingerprint(customer, keys.fingerprintKey(customer).latest(), new byte[]{1});
            return Verdict.working();
        } catch (CryptoException e) {
            if (e.code() == CryptoError.CRYPTO_KEY_MISSING && !required) return Verdict.working();
            return Verdict.down(e.code(), Map.of("keyService", String.valueOf(h.keyService())));
        }
    }
}
