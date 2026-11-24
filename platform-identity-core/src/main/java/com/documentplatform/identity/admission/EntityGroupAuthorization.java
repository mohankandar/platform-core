package com.documentplatform.identity.admission;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.documentplatform.identity.admission.IdentityAdmissionException.Reason.GROUPS_NOT_A_COLLECTION;
import static com.documentplatform.identity.admission.IdentityAdmissionException.Reason.NO_ADMISSION_POLICY;
import static com.documentplatform.identity.admission.IdentityAdmissionException.Reason.UNKNOWN_ROLE;
import static com.documentplatform.identity.admission.IdentityAdmissionException.Reason.NO_ROLE_FOR_ENTITY;
import static com.documentplatform.identity.admission.IdentityAdmissionException.Reason.PLATFORM_ROLE_IN_ENTITY_GRANT;

/**
 * Turns verified login-system group memberships into roles for one entity, from that entity's grants only:
 * a group granted in another entity never adds a role here. Grants come from server configuration, never the request.
 */
public final class EntityGroupAuthorization {

    private final EntityRoleCatalog roles;

    public EntityGroupAuthorization(EntityRoleCatalog roles) {
        this.roles = Objects.requireNonNull(roles, "roles");
    }

    /** No matching group is ordinary non-membership; an invalid policy is never suppressed. */
    public boolean hasMatchingGroup(Object signedGroups, Map<String, Set<String>> grants) {
        if (!(signedGroups instanceof Collection<?> groups)) {
            throw new IdentityAdmissionException(GROUPS_NOT_A_COLLECTION, "The verified groups claim is not a list");
        }
        if (grants == null || grants.isEmpty()) {
            throw new IdentityAdmissionException(NO_ADMISSION_POLICY, "The entity has no group grants");
        }
        for (Object candidate : groups) {
            if (candidate instanceof String group && grants.containsKey(group)) return true; // exactly (SEC-44)
        }
        return false;
    }

    public Set<String> authorizedRoles(EntityId requestedEntity, Object signedGroups,
                                       Map<EntityId, Map<String, Set<String>>> mappings) {
        Objects.requireNonNull(requestedEntity, "requestedEntity");
        if (mappings == null || !mappings.containsKey(requestedEntity)) {
            throw new IdentityAdmissionException(NO_ADMISSION_POLICY, "The entity has no group grants");
        }
        Map<String, Set<String>> grants = mappings.get(requestedEntity);
        if (grants == null || grants.isEmpty()) {
            throw new IdentityAdmissionException(NO_ADMISSION_POLICY, "The entity has no group grants");
        }
        Set<String> external = new LinkedHashSet<>();
        if (signedGroups instanceof Collection<?> groups) {
            for (Object group : groups) {
                if (group instanceof String value && !value.isBlank()) external.add(value); // exactly (SEC-44)
            }
        } else {
            throw new IdentityAdmissionException(GROUPS_NOT_A_COLLECTION, "The verified groups claim is not a list");
        }
        Set<String> authorized = new LinkedHashSet<>();
        for (String group : external) {
            Set<String> granted = grants.get(group);
            if (granted != null) {
                for (String role : granted) {
                    String canonical = roles.canonicalEntityRole(role)
                            .orElseThrow(() -> new IdentityAdmissionException(UNKNOWN_ROLE, "A grant names a role the product does not know"));
                    if (roles.isPlatformRole(canonical)) {
                        throw new IdentityAdmissionException(PLATFORM_ROLE_IN_ENTITY_GRANT,
                                "The product platform role cannot be granted through an entity group");
                    }
                    authorized.add(canonical);
                }
            }
        }
        if (authorized.isEmpty()) {
            throw new IdentityAdmissionException(NO_ROLE_FOR_ENTITY, "The verified identity has no role in this entity");
        }
        return Set.copyOf(authorized);
    }
}
