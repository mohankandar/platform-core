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

    /**
     * Signs through a signing service that keeps the private key (for example Vault Transit): only the message to
     * sign leaves this process, and only the signature comes back.
     */
    public static String sign(Licence licence, SignedDocument.MessageSigner signer) {
        return SignedDocument.sign(LicenceFormat.FORMAT, "licence", LicenceFormat.payload(licence), signer);
    }
}
