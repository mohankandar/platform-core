package com.documentplatform.identity.admission;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.documentplatform.identity.admission.IdentityAdmissionException.Reason.ENTITY_ROLE_IN_PLATFORM_GRANT;
import static com.documentplatform.identity.admission.IdentityAdmissionException.Reason.GROUPS_NOT_A_COLLECTION;
import static com.documentplatform.identity.admission.IdentityAdmissionException.Reason.NO_ADMISSION_POLICY;
import static com.documentplatform.identity.admission.IdentityAdmissionException.Reason.UNKNOWN_ROLE;

/**
 * A signed-in person's platform roles (the product's {@code platformRoles}, for example an operations admin who
 * works across every entity) from their verified groups and the customer's platform-wide grants under the ACTIVE
 * login connection for the product. Entity roles never come from here, and {@link EntityGroupAuthorization} keeps
 * refusing platform roles through entity grants. The grants come from server configuration, never the request.
 */
public final class PlatformGroupAuthorization {

    private final EntityRoleCatalog roles;
    private final PlatformGroupGrants grants;

    public PlatformGroupAuthorization(EntityRoleCatalog roles, PlatformGroupGrants grants) {
        this.roles = Objects.requireNonNull(roles, "roles");
        this.grants = Objects.requireNonNull(grants, "grants");
    }

    /**
     * @param key          the verified token's connection key (provider ID, issuer, audience)
     * @param signedGroups the verified groups claim (a list)
     * @return the platform roles granted to any of the groups; empty when none (no platform grant, or no ACTIVE
     *         connection for the key)
     * @throws IdentityAdmissionException when the groups claim is not a list, the grants cannot be read, or a grant
     *                                    names a role that is unknown or not a platform role
     */
    public Set<String> platformRoles(ConnectionKey key, Object signedGroups) {
        if (!(signedGroups instanceof Collection<?> groups)) {
            throw new IdentityAdmissionException(GROUPS_NOT_A_COLLECTION, "The verified groups claim is not a list");
        }
        Map<String, Set<String>> granted = grants.activePlatformGrants(key);
        if (granted == null) {
            throw new IdentityAdmissionException(NO_ADMISSION_POLICY, "The platform grants could not be read");
        }
        Set<String> out = new LinkedHashSet<>();
        for (Object g : groups) {
            if (!(g instanceof String group) || group.isBlank()) continue;
            Set<String> forGroup = granted.get(group.trim());
            if (forGroup == null) continue;
            for (String role : forGroup) {
                String canonical = roles.canonicalEntityRole(role).orElseThrow(() -> new IdentityAdmissionException(
                        UNKNOWN_ROLE, "A platform grant names a role the product does not know"));
                if (!roles.isPlatformRole(canonical)) {
                    throw new IdentityAdmissionException(ENTITY_ROLE_IN_PLATFORM_GRANT,
                            "A platform-wide grant names a role that is not a platform role");
                }
                out.add(canonical);
            }
        }
        return Set.copyOf(out);
    }
}
