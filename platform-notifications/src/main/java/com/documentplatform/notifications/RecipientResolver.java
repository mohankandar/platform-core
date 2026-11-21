package com.documentplatform.notifications;

import java.util.Optional;
import java.util.Set;

/**
 * Supplied by the app: who holds a role. Recipients are roles, not named people, so this is asked at send time and
 * picks up changes in who holds the role.
 */
public interface RecipientResolver {

    /**
     * @param entity the event's entity ("Reviewers of entity X"), or null for a tenant-level role
     * @return the people holding the role; empty if none
     */
    Set<Recipient> resolve(TenantId tenant, EntityId entity, RoleId role);

    /**
     * One person by user id, for a message to that person only (for example the requester of an approval, told the
     * outcome). Asked at send time. The default knows no one, so such messages fail with {@code NO_RECIPIENTS} until
     * the app implements it.
     *
     * @return the person, or empty if the app does not know them (any more)
     */
    default Optional<Recipient> person(TenantId tenant, String userId) {
        return Optional.empty();
    }
}
