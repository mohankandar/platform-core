package com.documentplatform.identity.store;

import com.documentplatform.identity.IdentityConnectionStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The customers' login connections and their history. Every write also writes a history row (who, when, source).
 * Per customer and product: at most one ACTIVE connection, and at most one DRAFT or VALIDATED one (the draft the IT
 * Admin is preparing; see {@link LoginConnections}).
 */
public interface LoginConnectionStore {

    /** The customer's ACTIVE connection for this product, if any. */
    Optional<LoginConnection> active(String tenantId, String productCode);

    Optional<LoginConnection> find(UUID connectionId);

    /**
     * Stores a new connection at version 1 (the given version is ignored).
     *
     * @throws IllegalStateException when the customer is not stored, the connection ID is taken, the connection is
     *                               ACTIVE and the customer already has an ACTIVE connection for the product, or it is
     *                               DRAFT or VALIDATED and the customer already has one of those for the product
     */
    LoginConnection insert(LoginConnection connection, ChangeSource source, String changedBy);

    /**
     * Replaces the connection's values (same connection ID, customer and product) and moves it to
     * {@code expectedVersion + 1}.
     *
     * @return the stored connection, or empty when the stored version is no longer {@code expectedVersion} (someone
     *         else changed it first; nothing was written)
     * @throws IllegalStateException when the connection would become a second ACTIVE one (or a second DRAFT or
     *                               VALIDATED one) for the customer and product
     */
    Optional<LoginConnection> update(LoginConnection connection, long expectedVersion, ChangeSource source,
                                     String changedBy);

    /** The connection's history, oldest first. */
    List<ConnectionChange> history(UUID connectionId);

    /** The customer's connections for the product, in every status, oldest first. */
    default List<LoginConnection> connections(String tenantId, String productCode) {
        throw new UnsupportedOperationException("this store does not list connections");
    }

    /** The customer's DRAFT or VALIDATED connection for the product (at most one), if any. */
    default Optional<LoginConnection> pending(String tenantId, String productCode) {
        return connections(tenantId, productCode).stream().filter(c -> c.status() == IdentityConnectionStatus.DRAFT
                || c.status() == IdentityConnectionStatus.VALIDATED).findFirst();
    }

    /**
     * In one transaction: the customer's ACTIVE connection for the product (if any) becomes DISABLED, and this
     * connection becomes ACTIVE, each at its version + 1 with a history row. The caller checks that the connection is
     * VALIDATED.
     *
     * @return the connection now ACTIVE, or empty when its stored version is no longer {@code expectedVersion}
     *         (nothing was written)
     * @throws IllegalStateException when the connection is not stored or already ACTIVE
     */
    default Optional<LoginConnection> activate(UUID connectionId, long expectedVersion, ChangeSource source,
                                               String changedBy) {
        throw new UnsupportedOperationException("this store does not activate connections");
    }

    /**
     * In one transaction: goes back to the previous ACTIVE version of the customer's login connection for the product.
     * The previous ACTIVE version is the newest ACTIVE row of the history whose values differ from the ACTIVE
     * connection's values. Its values become ACTIVE again as a new version (of the connection that had them; the
     * connection ACTIVE until now becomes DISABLED when it is another one). DRAFT and VALIDATED connections are left
     * alone.
     *
     * @return the connection now ACTIVE, or empty when there is no previous ACTIVE version (nothing was written)
     */
    default Optional<LoginConnection> rollback(String tenantId, String productCode, ChangeSource source,
                                               String changedBy) {
        throw new UnsupportedOperationException("this store does not roll connections back");
    }

    /**
     * What {@link #rollback} would make ACTIVE, without writing: the connection that had the previous ACTIVE values,
     * carrying those values (at its stored version). Empty when there is no previous ACTIVE version.
     */
    default Optional<LoginConnection> rollbackCandidate(String tenantId, String productCode) {
        throw new UnsupportedOperationException("this store does not roll connections back");
    }
}
