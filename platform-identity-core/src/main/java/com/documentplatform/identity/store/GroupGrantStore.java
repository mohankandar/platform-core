package com.documentplatform.identity.store;

import com.documentplatform.identity.mapping.Grant;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Group-to-role grants ({@code idn_group_grant}): in this entity slot, for this product, members of this group of
 * this login system (issuer) get this role. Grants of one product never apply to another product. Values are stored
 * exactly as given.
 *
 * <p>Every change also writes the grant history ({@code idn_grant_history}, see {@link GrantHistory}) in the same
 * transaction: pass a {@link GrantAuthor} to say who. The methods without one record the change as made in the app
 * by {@value GrantAuthor#UNNAMED_APP_USER}.
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

    /** {@link #add} with a history row per grant added, written by {@code author}. */
    default int add(String slotId, String productCode, URI issuer, Collection<Grant> grants, GrantAuthor author) {
        return change(slotId, productCode, issuer, grants, List.of(), author);
    }

    /** {@link #remove} with a history row per grant removed, written by {@code author}. */
    default int remove(String slotId, String productCode, URI issuer, Collection<Grant> grants, GrantAuthor author) {
        return change(slotId, productCode, issuer, List.of(), grants, author);
    }

    /**
     * Adds and removes grants of one slot in one transaction, with the history. When one group loses exactly one role
     * and gains exactly one role in the call, the history holds one CHANGED row (role before → role after) instead of
     * a REMOVED and an ADDED row. Grants already in the wanted state are ignored.
     *
     * @return how many grants were added or removed
     * @throws IllegalStateException when the slot is not stored
     */
    default int change(String slotId, String productCode, URI issuer, Collection<Grant> add, Collection<Grant> remove,
                       GrantAuthor author) {
        throw new UnsupportedOperationException("this store does not write the grant history");
    }

    /** Changes one group's role in one step (one CHANGED history row). @return whether anything changed */
    default boolean changeRole(String slotId, String productCode, URI issuer, String group, String roleBefore,
                               String roleAfter, GrantAuthor author) {
        Set<Grant> now = grants(slotId, productCode, issuer);
        Grant before = new Grant(group, roleBefore);
        Grant after = new Grant(group, roleAfter);
        if (!now.contains(before) || now.contains(after)) return false;
        return change(slotId, productCode, issuer, List.of(after), List.of(before), author) > 0;
    }
}
