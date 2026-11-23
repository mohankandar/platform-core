package com.documentplatform.integration.credential;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One client certificate of an integration as stored. For an uploaded one, {@code sealed} is the certificate chain and
 * private key sealed under the customer's keys ({@code pcv1.…}, platform-crypto's {@code ValueCipher}), dropped when
 * the credential is retired; for a keystore, the reference. People see a {@link CredentialView}, never this.
 *
 * @param switchAt for NEXT: when it becomes CURRENT by itself, or null for the admin's switch
 */
public record StoredCredential(UUID id, UUID integrationId, UUID entity, String product, CredentialState state,
                               CredentialSource source, CertificateInfo info, String sealed, KeystoreRef keystore,
                               Instant switchAt, String addedBy, Instant addedAt, Instant retiredAt) {

    public StoredCredential {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(integrationId, "integrationId");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(info, "info");
        Objects.requireNonNull(addedBy, "addedBy");
        Objects.requireNonNull(addedAt, "addedAt");
        if ((source == CredentialSource.KEYSTORE) != (keystore != null)) {
            throw new IllegalArgumentException("A keystore credential, and only it, has a keystore reference");
        }
        if (source == CredentialSource.UPLOADED && state != CredentialState.RETIRED && sealed == null) {
            throw new IllegalArgumentException("An uploaded credential in use has its sealed key");
        }
        if (switchAt != null && state != CredentialState.NEXT) {
            throw new IllegalArgumentException("Only the next credential has a switch time");
        }
        if ((state == CredentialState.RETIRED) != (retiredAt != null)) {
            throw new IllegalArgumentException("A retired credential, and only it, has a retirement time");
        }
    }

    StoredCredential retired(Instant at) {
        return new StoredCredential(id, integrationId, entity, product, CredentialState.RETIRED, source, info, null,
                keystore, null, addedBy, addedAt, at);
    }

    StoredCredential current() {
        return new StoredCredential(id, integrationId, entity, product, CredentialState.CURRENT, source, info, sealed,
                keystore, null, addedBy, addedAt, null);
    }

    StoredCredential withSwitchAt(Instant at) {
        return new StoredCredential(id, integrationId, entity, product, state, source, info, sealed, keystore, at,
                addedBy, addedAt, retiredAt);
    }

    StoredCredential withInfo(CertificateInfo i) {
        return new StoredCredential(id, integrationId, entity, product, state, source, i, sealed, keystore, switchAt,
                addedBy, addedAt, retiredAt);
    }

    public CredentialView view() {
        return new CredentialView(id, integrationId, state, source, info, keystore == null ? null : keystore.path(),
                keystore == null ? null : keystore.alias(), switchAt, addedBy, addedAt, retiredAt);
    }

    @Override
    public String toString() {
        return "StoredCredential[" + id + " " + state + " " + source + " " + info.sha256() + "]"; // never the sealed key
    }
}
