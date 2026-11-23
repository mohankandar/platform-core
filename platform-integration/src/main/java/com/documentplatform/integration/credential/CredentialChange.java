package com.documentplatform.integration.credential;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One change of an integration's client certificates, for the history and the app's audit: numbered per integration
 * like configuration versions, with who and when. Holds the certificate's fingerprint (public), never its key.
 *
 * @param version     1 for the first change of this integration's certificates, raised by one for each change
 * @param credential  the certificate the change is about
 * @param fingerprint its SHA-256 fingerprint
 */
public record CredentialChange(UUID integrationId, UUID entity, String product, int version, Kind kind,
                               UUID credential, String fingerprint, String by, Instant at) {

    public enum Kind {
        /** A certificate was uploaded (as CURRENT when there was none, else as NEXT). */
        UPLOADED,
        /** A keystore entry was set (as CURRENT when there was none, else as NEXT). */
        KEYSTORE_SET,
        /** The certificate in a keystore was replaced in the file by the customer (seen when used). */
        KEYSTORE_RENEWED,
        /** The time NEXT becomes CURRENT by itself was set or cleared. */
        SWITCH_SCHEDULED,
        /** NEXT became CURRENT at the admin's switch; the old CURRENT is RETIRED. */
        SWITCHED,
        /** NEXT became CURRENT at its set time; the old CURRENT is RETIRED. */
        SWITCHED_AT_TIME,
        /** NEXT was discarded (RETIRED) without being used. */
        NEXT_DISCARDED
    }

    public CredentialChange {
        Objects.requireNonNull(integrationId, "integrationId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(by, "by");
        Objects.requireNonNull(at, "at");
        if (version < 1) throw new IllegalArgumentException("version starts at 1");
    }
}
