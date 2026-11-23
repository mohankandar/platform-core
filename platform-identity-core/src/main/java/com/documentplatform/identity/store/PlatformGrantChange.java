package com.documentplatform.identity.store;

import java.net.URI;
import java.time.Instant;

/**
 * One row of the grant history ({@code idn_grant_history}) with scope PLATFORM: one change of a platform-wide grant
 * of a customer and product (no entity). Insert only.
 *
 * @param roleBefore the role before; null when the grant was added
 * @param roleAfter  the role after; null when the grant was removed
 * @param setupRunId the setup run's ID when the source is SETUP; else null
 */
public record PlatformGrantChange(String tenantId, String productCode, URI issuer, String group, String roleBefore,
                                  String roleAfter, GrantChange.Kind change, ChangeSource source, String changedBy,
                                  Instant changedAt, String setupRunId) {
}
