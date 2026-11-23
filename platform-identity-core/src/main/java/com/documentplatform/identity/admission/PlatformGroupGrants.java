package com.documentplatform.identity.admission;

import java.util.Map;
import java.util.Set;

/**
 * The server-side platform-wide grants of the customer whose ACTIVE login connection matches this key exactly
 * (provider ID, issuer, audience), as {@code external group -> platform role names}. Built in:
 * {@code PostgresIdentityLookup} and the identity testkit's in-memory lookup ({@code idn_platform_grant}, loaded by
 * the setup command from the role mapping file's {@code *} rows).
 *
 * <p>Implementations guarantee: only grants recorded for this issuer and product under an ACTIVE connection; group
 * names exactly as the login system sends them; an empty map for a blank provider ID or audience, or when no
 * connection matches; {@code null} when the store could not be read, or when more than one customer's connection
 * matches with platform grants (the library then refuses).
 *
 * <p>SPI: implemented by stores ({@code PostgresIdentityLookup}, the identity testkit); apps call
 * {@link PlatformGroupAuthorization}.
 */
@FunctionalInterface
public interface PlatformGroupGrants {
    Map<String, Set<String>> activePlatformGrants(ConnectionKey key);
}
