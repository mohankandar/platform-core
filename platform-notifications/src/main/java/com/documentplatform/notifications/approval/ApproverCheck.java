package com.documentplatform.notifications.approval;

import com.documentplatform.notifications.EntityId;
import com.documentplatform.notifications.RecipientResolver;
import com.documentplatform.notifications.RoleId;
import com.documentplatform.notifications.TenantId;

import java.util.Objects;

/**
 * Supplied by the app: does this person hold the approver role now? Asked every time a link is opened and again when
 * the person decides, so a person whose role was taken away after the message went out is refused.
 */
@FunctionalInterface
public interface ApproverCheck {

    /**
     * @throws RuntimeException if it cannot be checked; the person is then refused (fails closed)
     */
    boolean holdsRole(TenantId tenant, EntityId entity, RoleId role, String userId);

    /** The check made from the app's recipient resolver: is the person among the role's holders? */
    static ApproverCheck from(RecipientResolver recipients) {
        Objects.requireNonNull(recipients, "recipients");
        return (tenant, entity, role, userId) -> recipients.resolve(tenant, entity, role).stream()
                .anyMatch(r -> r.userId().equals(userId));
    }
}
