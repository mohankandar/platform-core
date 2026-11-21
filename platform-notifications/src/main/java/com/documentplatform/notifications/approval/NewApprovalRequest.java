package com.documentplatform.notifications.approval;

import com.documentplatform.notifications.AppLink;
import com.documentplatform.notifications.EntityId;
import com.documentplatform.notifications.RecordIdentifier;
import com.documentplatform.notifications.TenantId;

import java.util.Objects;

/**
 * What the app gives to ask for a decision. Structured fields only, as for every notification: the request type, its
 * entity, who asks, the record's identifier and the request's page in the app.
 *
 * @param requester  the user id of the person asking; they get no links and cannot decide
 * @param identifier the record's primary identifier shown in the message (for example a request number); optional
 * @param link       the request's page in the app, the message's main link; optional
 */
public record NewApprovalRequest(String type, TenantId tenant, EntityId entity, String requester,
                                 RecordIdentifier identifier, AppLink link) {

    public NewApprovalRequest {
        Ids.id(type, "Request type id");
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(entity, "entity");
        Ids.id(requester, "User id");
    }
}
