package com.documentplatform.integration.credential;

import com.documentplatform.outbound.ClientCertificate;
import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.integration.config.IntegrationConfig;

import java.time.Instant;

/** Gives the call engine an integration's client certificate for mutual TLS. Built: {@link IntegrationCredentials}. */
public interface CertificateSource {

    /** The certificate, or why there is none to use (a code; never a value). */
    record Loaded(ClientCertificate certificate, IntegrationCode failure) {
        public static Loaded of(ClientCertificate c) {
            return new Loaded(c, null);
        }

        public static Loaded failed(IntegrationCode code) {
            return new Loaded(null, code);
        }

        public boolean ok() {
            return certificate != null;
        }
    }

    /** @param which CURRENT for calls, NEXT for a Test action of the next certificate */
    Loaded load(IntegrationConfig integration, CredentialState which, Instant now);

    /** No certificates: every mutual TLS integration answers {@code INT_CREDENTIAL_MISSING}. */
    CertificateSource NONE = (integration, which, now) -> Loaded.failed(IntegrationCode.INT_CREDENTIAL_MISSING);
}
