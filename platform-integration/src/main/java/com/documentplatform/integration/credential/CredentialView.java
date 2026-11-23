package com.documentplatform.integration.credential;

import com.documentplatform.outbound.CertificateInfo;
import java.time.Instant;
import java.util.UUID;

/**
 * What the IT Admin screen shows of a client certificate: its state, where it is kept (for a keystore its path and
 * alias, never the password), the certificate's subject, issuer, serial, SHA-256 fingerprint and validity, and who
 * added it when. Never the private key or the file.
 */
public record CredentialView(UUID id, UUID integrationId, CredentialState state, CredentialSource source,
                             CertificateInfo info, String keystorePath, String keystoreAlias, Instant switchAt,
                             String addedBy, Instant addedAt, Instant retiredAt) {
}
