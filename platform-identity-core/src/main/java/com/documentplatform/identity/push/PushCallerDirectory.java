package com.documentplatform.identity.push;

import com.documentplatform.identity.store.EntityStatus;
import com.documentplatform.identity.store.MachineBinding;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * What {@link PushCaller} reads, from the identity tables (Postgres: {@code PostgresPushCallerDirectory} in
 * platform-identity-jdbc; in memory: the identity testkit's store). Read only. A store that cannot be read throws;
 * nothing here ever falls back to a configured entity.
 */
public interface PushCallerDirectory {

    /** An ACTIVE login connection: its customer and the values a token is checked against. */
    record ActiveConnection(String tenantId, String providerId, URI issuer, String apiAudience) {
        public ActiveConnection {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(issuer, "issuer");
            Objects.requireNonNull(apiAudience, "apiAudience");
        }
    }

    /** A client binding (ACTIVE or INACTIVE) with its entity's status now. */
    record BoundClient(MachineBinding binding, EntityStatus entityStatus) {
        public BoundClient {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(entityStatus, "entityStatus");
        }
    }

    /** The ACTIVE login connections for the product whose issuer is exactly {@code issuer} (any customer). */
    List<ActiveConnection> activeConnections(String productCode, URI issuer);

    /** Whether the customer has an ACTIVE login connection for the product. */
    boolean hasActiveConnection(String tenantId, String productCode);

    /** Every binding of this client ID for the product (any customer, ACTIVE or INACTIVE). */
    List<BoundClient> bindings(String productCode, String clientId);
}
