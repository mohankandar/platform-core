package com.documentplatform.licensing;

import java.security.PrivateKey;

/** Writes a signed licence file ({@link LicenceFormat}). The private key is used and dropped; it is never written. */
public final class LicenceSigner {

    private LicenceSigner() {
    }

    /**
     * @return the licence file text (UTF-8 when stored), pretty-printed with sorted keys
     * @throws IllegalArgumentException if the key is not an Ed25519 private key
     */
    public static String sign(Licence licence, PrivateKey key) {
        return SignedDocument.sign(LicenceFormat.FORMAT, "licence", LicenceFormat.payload(licence), key);
    }
}
