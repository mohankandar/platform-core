package com.documentplatform.identity.admission;

import java.util.Optional;

/**
 * Implemented by the product: its role names. The library knows no role names of its own.
 *
 * <p>The product must guarantee:
 * <ul>
 *   <li>{@link #canonicalEntityRole} returns the canonical name for a configured role name, or empty for a name the
 *       product does not know (the library then refuses the grant);</li>
 *   <li>{@link #isPlatformRole} is true for any role that reaches platform-wide screens; such a role can never be
 *       granted through an entity's groups.</li>
 * </ul>
 */
public interface EntityRoleCatalog {
    Optional<String> canonicalEntityRole(String configuredName);

    boolean isPlatformRole(String canonicalRole);

    /**
     * The role an alias stands for, when the product's role list gives aliases (for example a role name as the
     * customer's identity system calls it). Exact spelling; empty when the name is not an alias. A role mapping file
     * may use an alias: it is resolved when the file is read and stored as the role.
     */
    default Optional<String> aliasTarget(String name) {
        return Optional.empty();
    }
}
