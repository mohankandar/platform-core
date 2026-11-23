package com.documentplatform.identity.store;

import com.documentplatform.identity.mapping.Grant;

import java.net.URI;
import java.util.Collection;
import java.util.Set;

/**
 * Platform-wide grants ({@code idn_platform_grant}, identity V4): for this customer and product, members of this
 * group of this login system (issuer) get this platform role (one of the product's {@code platformRoles}), in every
 * entity. Loaded by the setup command from the role mapping file's {@code *} rows. Values are stored exactly as given.
 *
 * <p>Every change writes the grant history ({@code idn_grant_history}, scope PLATFORM, no entity) in the same
 * transaction.
 *
 * <p>SPI: implemented by stores ({@code PostgresGroupGrantStore}, the identity testkit); the setup command
 * writes it, and apps call {@code admission.PlatformGroupAuthorization} to decide.
 */
public interface PlatformGrantStore {

    /** The customer's platform grants for the product and issuer. */
    Set<Grant> platformGrants(String tenantId, String productCode, URI issuer);

    /**
     * Adds and removes platform grants of one customer and product in one transaction, with the history (one CHANGED
     * row when one group loses exactly one role and gains exactly one). Grants already in the wanted state are
     * ignored.
     *
     * @return how many grants were added or removed
     * @throws IllegalStateException when the customer is not stored
     */
    int changePlatform(String tenantId, String productCode, URI issuer, Collection<Grant> add,
                       Collection<Grant> remove, GrantAuthor author);
}
