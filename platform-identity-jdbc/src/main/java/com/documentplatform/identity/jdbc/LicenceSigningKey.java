package com.documentplatform.identity.jdbc;

import com.documentplatform.identity.signin.DevelopmentLicence;
import com.documentplatform.licensing.LicenceException;
import com.documentplatform.licensing.LicenceService;
import com.documentplatform.licensing.TrustedKeys;

import java.util.Objects;

/**
 * {@link DevelopmentLicence} from platform-licensing: whether the licence in force is signed by a key the release's
 * {@code trusted-keys.json} marks {@code "development": true}. A production release lists no development key, so
 * this is always false there. When the service refuses the stored licence, the answer is false.
 */
public final class LicenceSigningKey {

    private LicenceSigningKey() {
    }

    public static DevelopmentLicence of(LicenceService service, TrustedKeys keys) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(keys, "keys");
        return () -> {
            try {
                return keys.isDevelopmentKey(service.status().licence().keyId());
            } catch (LicenceException refused) {
                return false;
            }
        };
    }
}
