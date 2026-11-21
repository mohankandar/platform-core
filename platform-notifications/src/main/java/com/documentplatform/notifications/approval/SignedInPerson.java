package com.documentplatform.notifications.approval;

import com.documentplatform.notifications.TenantId;

import java.util.Objects;

/**
 * The person signed in to the app who opened a link or decides, as the app's login knows them.
 *
 * @param displayName shown to later clickers ("Already approved by ..."); trimmed, 1 to 200 characters
 */
public record SignedInPerson(TenantId tenant, String userId, String displayName) {

    public SignedInPerson {
        Objects.requireNonNull(tenant, "tenant");
        Ids.id(userId, "User id");
        displayName = Ids.name(displayName);
    }
}
