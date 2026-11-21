package com.documentplatform.licensing;

/**
 * The state of an accepted licence on a given day (UTC). The app always starts under every state; refusals are
 * {@link LicenceRefusal}s, not states. Each app decides what "intake" means and stops it when
 * {@link #intakeAllowed()} is false. Viewing and export always continue.
 */
public enum LicenceState {
    /** Before the licence's start date. Intake stays stopped until it starts. */
    NOT_STARTED(false),
    /** Valid, more than 60 days before expiry (or perpetual). */
    VALID(true),
    /** Valid, 60 days or fewer before expiry: warnings at 60, 30, 7 and 1 days. */
    EXPIRING(true),
    /** Past the expiry date, within the 30-day grace period: intake continues with daily critical warnings. */
    EXPIRED_IN_GRACE(true),
    /** Past the grace period: intake stops with a clear message. */
    EXPIRED_GRACE_OVER(false);

    private final boolean intakeAllowed;

    LicenceState(boolean intakeAllowed) {
        this.intakeAllowed = intakeAllowed;
    }

    public boolean intakeAllowed() {
        return intakeAllowed;
    }
}
