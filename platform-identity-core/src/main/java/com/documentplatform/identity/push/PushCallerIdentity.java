package com.documentplatform.identity.push;

import java.util.Objects;
import java.util.UUID;

/**
 * Who a push-API caller is: the customer, the entity it may deliver to, the sending system (source) and the client ID
 * of the binding that admitted it.
 *
 * @param clientId the binding's client ID: the token's client ID, or {@code cert-sha256:<hex>} for a certificate
 *                 caller (with both, the token's)
 * @param via      how the caller was identified
 */
public record PushCallerIdentity(String tenantId, String slotId, UUID entityUuid, String sourceKey, String clientId,
                                 Via via) {

    /** How the caller was identified. */
    public enum Via { TOKEN, CERTIFICATE, TOKEN_AND_CERTIFICATE }

    public PushCallerIdentity {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(slotId, "slotId");
        Objects.requireNonNull(entityUuid, "entityUuid");
        Objects.requireNonNull(sourceKey, "sourceKey");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(via, "via");
    }
}
