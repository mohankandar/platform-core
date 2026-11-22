package com.documentplatform.identity.store;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of the grant history ({@code idn_grant_history}): one grant change of one entity. Insert only.
 *
 * @param roleBefore the role before; null when the grant was added
 * @param roleAfter  the role after; null when the grant was removed
 * @param setupRunId the setup run's ID when the source is SETUP; else null
 */
public record GrantChange(String tenantId, String productCode, String slotId, UUID entityUuid, URI issuer,
                          String group, String roleBefore, String roleAfter, Kind change, ChangeSource source,
                          String changedBy, Instant changedAt, String setupRunId) {

    public enum Kind {
        ADDED,
        REMOVED,
        /** The group's role changed from {@code roleBefore} to {@code roleAfter} in one step. */
        CHANGED
    }
}
