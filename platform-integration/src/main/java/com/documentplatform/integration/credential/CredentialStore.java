package com.documentplatform.integration.credential;

import java.util.List;
import java.util.UUID;

/**
 * Where the integrations' client certificates and their history are kept. Built: {@code jdbc.PostgresCredentialStore}
 * ({@code int_credential}, {@code int_credential_change}); in memory for tests in the testkit. Apps use
 * {@link IntegrationCredentials}, which checks, seals and audits.
 *
 * <p>SPI: implemented by stores; apps call {@link IntegrationCredentials}.
 */
public interface CredentialStore {

    /** Every certificate of the integration (CURRENT, NEXT and RETIRED), oldest first. */
    List<StoredCredential> list(UUID integrationId);

    /** The number of the integration's last change, 0 when there was none. */
    int version(UUID integrationId);

    /**
     * Saves a change in one transaction: the rows (new ones inserted, existing ones updated by ID, those retired
     * first) and the history entry {@code change}, numbered {@code expectedVersion + 1}. Refused with
     * {@code INT_CREDENTIAL_CONFLICT} (an {@code IntegrationStore.Conflict}) when another change was saved first.
     */
    void save(int expectedVersion, CredentialChange change, List<StoredCredential> rows);

    /** The integration's changes, oldest first. */
    List<CredentialChange> history(UUID integrationId);

    /** Every CURRENT and NEXT certificate of every integration (for the expiry check and timed switches). */
    List<StoredCredential> active();
}
