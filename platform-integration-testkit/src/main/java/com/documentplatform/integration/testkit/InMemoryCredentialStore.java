package com.documentplatform.integration.testkit;

import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.integration.credential.CredentialChange;
import com.documentplatform.integration.credential.CredentialState;
import com.documentplatform.integration.credential.CredentialStore;
import com.documentplatform.integration.credential.StoredCredential;
import com.documentplatform.integration.store.IntegrationStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client certificates and their history in memory, with the Postgres store's rules: numbered changes (a stale expected
 * number is {@code INT_CREDENTIAL_CONFLICT}), at most one CURRENT and one NEXT per integration, a retired one never
 * changes. {@link #rows()} shows what a database would hold (to check that no key is stored in clear). For tests.
 */
public final class InMemoryCredentialStore implements CredentialStore {

    private final Map<UUID, StoredCredential> rows = new LinkedHashMap<>();
    private final List<CredentialChange> changes = new ArrayList<>();

    @Override
    public synchronized List<StoredCredential> list(UUID integrationId) {
        return rows.values().stream().filter(r -> r.integrationId().equals(integrationId)).toList();
    }

    @Override
    public synchronized int version(UUID integrationId) {
        return (int) changes.stream().filter(c -> c.integrationId().equals(integrationId)).count();
    }

    @Override
    public synchronized void save(int expectedVersion, CredentialChange change, List<StoredCredential> changed) {
        if (version(change.integrationId()) != expectedVersion || change.version() != expectedVersion + 1) {
            throw new IntegrationStore.Conflict(IntegrationCode.INT_CREDENTIAL_CONFLICT);
        }
        Map<UUID, StoredCredential> after = new LinkedHashMap<>(rows);
        for (StoredCredential r : changed) {
            StoredCredential before = after.get(r.id());
            if (before != null && before.state() == CredentialState.RETIRED) {
                throw new IllegalStateException("a retired certificate never changes");
            }
            after.put(r.id(), r);
        }
        for (CredentialState s : List.of(CredentialState.CURRENT, CredentialState.NEXT)) {
            long n = after.values().stream()
                    .filter(r -> r.integrationId().equals(change.integrationId()) && r.state() == s).count();
            if (n > 1) throw new IntegrationStore.Conflict(IntegrationCode.INT_CREDENTIAL_CONFLICT);
        }
        rows.clear();
        rows.putAll(after);
        changes.add(change);
    }

    @Override
    public synchronized List<CredentialChange> history(UUID integrationId) {
        return changes.stream().filter(c -> c.integrationId().equals(integrationId)).toList();
    }

    @Override
    public synchronized List<StoredCredential> active() {
        return rows.values().stream().filter(r -> r.state() != CredentialState.RETIRED).toList();
    }

    /** Every row, as a database would hold it. */
    public synchronized List<StoredCredential> rows() {
        return List.copyOf(rows.values());
    }
}
