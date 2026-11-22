package com.documentplatform.integration.store;

import com.documentplatform.integration.config.IntegrationConfig;

import java.time.Instant;
import java.util.Objects;

/**
 * One version of a configuration as stored: the configuration, its version number and who saved it when.
 *
 * @param version   1 for the first, raised by one for every change
 * @param changedBy who saved this version (a user or the app's own name; never a secret)
 */
public record StoredIntegration(IntegrationConfig config, int version, String changedBy, Instant changedAt) {

    public StoredIntegration {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(changedBy, "changedBy");
        Objects.requireNonNull(changedAt, "changedAt");
        if (version < 1) throw new IllegalArgumentException("version starts at 1");
    }

    public java.util.UUID id() {
        return config.id();
    }
}
