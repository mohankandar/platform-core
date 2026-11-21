package com.documentplatform.licensing;

/**
 * Why a stored licence is refused. Each code is stable (apps and support docs key on it) and has one exact cause.
 * Under any of these the app does not start.
 */
public enum LicenceRefusal {
    LICENCE_MISSING("No licence is stored. Install the licence file you received with the setup or licence command."),
    LICENCE_MALFORMED("The stored licence is not a readable licence file."),
    LICENCE_UNSUPPORTED_FORMAT("The stored licence uses a format or signature algorithm this version does not read."),
    LICENCE_UNKNOWN_KEY_ID("The licence is signed with a key this version does not know."),
    LICENCE_TAMPERED("The licence content was changed after it was signed."),
    LICENCE_FORGED("The licence signature was not made by the named key."),
    LICENCE_WRONG_CUSTOMER("The licence belongs to a different customer (tenant) than this installation."),
    LICENCE_CLOCK_ROLLBACK("The system clock is behind the latest time this installation has seen.");

    private final String meaning;

    LicenceRefusal(String meaning) {
        this.meaning = meaning;
    }

    /** The fixed meaning of the code; {@link LicenceException#getMessage()} adds the exact cause. */
    public String meaning() {
        return meaning;
    }
}
