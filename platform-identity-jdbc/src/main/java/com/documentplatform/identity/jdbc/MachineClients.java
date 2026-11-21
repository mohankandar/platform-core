package com.documentplatform.identity.jdbc;

import java.util.Set;
import java.util.UUID;

/**
 * Implemented by the app over its own integration table: the entities that have an enabled machine integration bound
 * to this client principal, whose authentication proves a bearer-token client (the app decides which integration kinds
 * qualify). {@link PostgresMachineEntityBindings} keeps only those that are ACTIVE and have the customer's ACTIVE login
 * connection for the key.
 */
@FunctionalInterface
public interface MachineClients {

    /**
     * @param clientPrincipal the verified token's client principal, never blank
     * @return the entity UUIDs (the app's entity keys); null only when the app's table could not be read
     */
    Set<UUID> entitiesWithEnabledClient(String clientPrincipal);
}
