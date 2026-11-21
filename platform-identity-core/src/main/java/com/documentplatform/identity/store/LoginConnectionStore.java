package com.documentplatform.identity.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The customers' login connections and their history. Every write also writes a history row. */
public interface LoginConnectionStore {

    /** The customer's ACTIVE connection for this product, if any. */
    Optional<LoginConnection> active(String tenantId, String productCode);

    Optional<LoginConnection> find(UUID connectionId);

    /**
     * Stores a new connection at version 1 (the given version is ignored).
     *
     * @throws IllegalStateException when the customer is not stored, the connection ID is taken, or the connection is
     *                               ACTIVE and the customer already has an ACTIVE connection for the product
     */
    LoginConnection insert(LoginConnection connection, ChangeSource source, String changedBy);

    /**
     * Replaces the connection's values (same connection ID, customer and product) and moves it to
     * {@code expectedVersion + 1}.
     *
     * @return the stored connection, or empty when the stored version is no longer {@code expectedVersion} (someone
     *         else changed it first; nothing was written)
     * @throws IllegalStateException when the connection would become a second ACTIVE one for the customer and product
     */
    Optional<LoginConnection> update(LoginConnection connection, long expectedVersion, ChangeSource source,
                                     String changedBy);

    /** The connection's history, oldest first. */
    List<ConnectionChange> history(UUID connectionId);
}
