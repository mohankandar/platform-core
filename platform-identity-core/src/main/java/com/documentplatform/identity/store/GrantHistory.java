package com.documentplatform.identity.store;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The grant history ({@code idn_grant_history}) for the app's audit page: every grant added, removed or changed, by
 * setup or in the app, with who and when. Insert only; the database refuses updates and deletes. The app's DML-only
 * database user is enough to read it.
 */
public interface GrantHistory {

    /**
     * The entity's grant changes (every product) from {@code from} (inclusive) to {@code to} (exclusive), oldest
     * first.
     */
    List<GrantChange> forEntity(UUID entityUuid, Instant from, Instant to);
}
