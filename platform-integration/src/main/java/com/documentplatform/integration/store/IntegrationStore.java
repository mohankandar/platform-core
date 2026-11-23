package com.documentplatform.integration.store;

import com.documentplatform.integration.config.IntegrationConfig;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Where configurations and their history are kept. Every change is a new version; versions are never changed or
 * deleted. Built: {@code jdbc.PostgresIntegrationStore}; in-memory for tests in the testkit. Apps use
 * {@link IntegrationConfigs}, which checks and audits, rather than the store directly.
 *
 * <p>SPI: implemented by stores; apps call {@link IntegrationConfigs}.
 */
public interface IntegrationStore {

    /** Thrown when another change was saved first, or the integration already exists or is unknown. */
    final class Conflict extends RuntimeException implements com.documentplatform.errors.HasPlatformCode {
        private final com.documentplatform.integration.IntegrationCode code;

        public Conflict(com.documentplatform.integration.IntegrationCode code) {
            super(code.name(), null, false, false);
            this.code = code;
        }

        public com.documentplatform.integration.IntegrationCode code() {
            return code;
        }

        /** The code, for monitoring and the app's error handler ({@code HasPlatformCode}). */
        @Override
        public com.documentplatform.errors.PlatformCode platformCode() {
            return code;
        }
    }

    /** Stores version 1. {@code INT_CONFIG_EXISTS} if the ID is taken. */
    StoredIntegration create(IntegrationConfig config, ConfigChange change);

    /**
     * Stores the next version, only if the current one is {@code expectedVersion}: else {@code
     * INT_CONFIG_VERSION_CONFLICT} (someone else saved first) or {@code INT_CONFIG_NOT_FOUND}.
     */
    StoredIntegration update(IntegrationConfig config, int expectedVersion, ConfigChange change);

    Optional<StoredIntegration> current(UUID id);

    Optional<StoredIntegration> version(UUID id, int version);

    /** Every version's history entry, oldest first. */
    List<ConfigChange> history(UUID id);

    /** The current version of every integration of an entity in a product. */
    List<StoredIntegration> list(UUID entity, String product);

    /**
     * The current version of every integration that is switched on, of every entity and product (for the health
     * check per integration). Stores built before P025 do not have it.
     */
    default List<StoredIntegration> enabled() {
        throw new UnsupportedOperationException("This integration store cannot list the enabled integrations");
    }
}
