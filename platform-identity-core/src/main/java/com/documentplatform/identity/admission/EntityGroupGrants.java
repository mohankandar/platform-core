package com.documentplatform.identity.admission;

import java.util.Map;
import java.util.Set;

/**
 * Implemented by the product: the server-side group-to-role grants for each entity, as
 * {@code entity -> (external group -> configured role names)}.
 *
 * <p>The product must guarantee:
 * <ul>
 *   <li>grants are already scoped to this connection: only entities with an ACTIVE connection for this key and an
 *       ACTIVE entity, and only grants recorded for this issuer;</li>
 *   <li>group names exactly as the login system sends them (no case folding, no path stripping);</li>
 *   <li>an empty map for a blank provider id or audience;</li>
 *   <li>{@code null} only when the store could not be read (the library then refuses).</li>
 * </ul>
 * The grants come from server configuration only, never from the request.
 *
 * <p>SPI: implemented by stores ({@code PostgresIdentityLookup}, the identity testkit, or the app's own);
 * apps call {@link EntityMembershipResolver}.
 */
@FunctionalInterface
public interface EntityGroupGrants {
    Map<EntityId, Map<String, Set<String>>> activeGrants(ConnectionKey key);
}
