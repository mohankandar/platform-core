package com.documentplatform.identity.store;

import com.documentplatform.identity.mapping.Grant;

import java.net.URI;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Group-to-role grants ({@code idn_group_grant}): in this entity slot, for this product, members of this group of
 * this login system (issuer) get this role. Grants of one product never apply to another product. Values are stored
 * exactly as given.
 */
public interface GroupGrantStore {

    /** The slot's grants for the product and issuer. */
    Set<Grant> grants(String slotId, String productCode, URI issuer);

    /** Every slot of the customer that has grants for the product and issuer: slot ID → grants. */
    Map<String, Set<Grant>> grantsOfCustomer(String tenantId, String productCode, URI issuer);

    /**
     * Adds grants; a grant already stored is left as it is.
     *
     * @return how many were added
     * @throws IllegalStateException when the slot is not stored
     */
    int add(String slotId, String productCode, URI issuer, Collection<Grant> grants);

    /** Removes grants; a grant not stored is ignored. @return how many were removed */
    int remove(String slotId, String productCode, URI issuer, Collection<Grant> grants);
}
