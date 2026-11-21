package com.documentplatform.licensing;

import java.time.Instant;
import java.util.Objects;

/**
 * What an app calls each time it reads the stored licence: verify the file, check the clock, work out the state.
 * Throws {@link LicenceException} for every refusal (the app does not start); otherwise returns the status, under
 * which the app always starts.
 */
public final class LicenceChecker {

    private final LicenceVerifier verifier;
    private final ClockGuard clockGuard;

    public LicenceChecker(LicenceVerifier verifier, ClockGuard clockGuard) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.clockGuard = Objects.requireNonNull(clockGuard, "clockGuard");
    }

    /**
     * @param stored           the stored licence file, or null if none
     * @param expectedTenantId this installation's tenant, or null when not yet known
     */
    public LicenceStatus check(byte[] stored, String expectedTenantId) {
        Licence licence = verifier.verify(stored, expectedTenantId);
        Instant now = clockGuard.check();
        return LicenceStatus.at(licence, now);
    }
}
