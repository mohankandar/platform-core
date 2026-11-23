package com.documentplatform.licensing.issue;

import com.documentplatform.licensing.LastSeenReset;

import java.security.PrivateKey;

/** Signs a last-seen reset ({@code platform-last-seen-reset/1}); the customer side only verifies it (P041). */
public final class ResetSigner {

    private ResetSigner() {
    }

    public static String sign(LastSeenReset reset, PrivateKey key) {
        return DocumentSigner.sign(LastSeenReset.FORMAT, "reset", reset.payload(), key);
    }

    /** Signs through a signing service that keeps the private key. */
    public static String sign(LastSeenReset reset, DocumentSigner.MessageSigner signer) {
        return DocumentSigner.sign(LastSeenReset.FORMAT, "reset", reset.payload(), signer);
    }
}
