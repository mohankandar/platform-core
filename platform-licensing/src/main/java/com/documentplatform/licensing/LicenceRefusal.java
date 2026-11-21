package com.documentplatform.licensing;

/**
 * Why a licence is refused. Each code is stable (apps and support docs key on it) and has one exact cause.
 *
 * <p>For the stored licence, the first seven mean the app does not start. A server clock behind is never a refusal
 * (see {@link ClockReading}). {@link #LICENCE_OLDER_VERSION} and {@link #LICENCE_VERSION_CONFLICT} only refuse storing a
 * new licence; the licence already stored stays in force.
 */
public enum LicenceRefusal {
    LICENCE_MISSING("No licence is stored. Install the licence file you received with the setup or licence command."),
    LICENCE_MALFORMED("The licence is not a readable licence file."),
    LICENCE_UNSUPPORTED_FORMAT("The licence uses a format or signature algorithm this version does not read."),
    LICENCE_UNKNOWN_KEY_ID("The licence is signed with a key this version does not know."),
    LICENCE_TAMPERED("The licence content was changed after it was signed."),
    LICENCE_FORGED("The licence signature was not made by the named key."),
    LICENCE_WRONG_CUSTOMER("The licence belongs to a different customer (tenant) than this installation."),
    LICENCE_OLDER_VERSION("The licence is an older version than one already stored."),
    LICENCE_VERSION_CONFLICT("A different licence with the same version number is already stored.");

    private final String meaning;

    LicenceRefusal(String meaning) {
        this.meaning = meaning;
    }

    /** The fixed meaning of the code; {@link LicenceException#getMessage()} adds the exact cause. */
    public String meaning() {
        return meaning;
    }

    /** What the operator does about it. */
    public String action() {
        return switch (this) {
            case LICENCE_MISSING -> "Store the licence file with the licence command (store --file <licence file>).";
            case LICENCE_MALFORMED, LICENCE_TAMPERED -> "Store the original licence file again with the licence "
                    + "command; if you no longer have it, ask your account manager for a copy.";
            case LICENCE_UNSUPPORTED_FORMAT, LICENCE_UNKNOWN_KEY_ID -> "Install the app release that matches your "
                    + "licence, or ask your account manager for a licence signed with a current key.";
            case LICENCE_FORGED -> "This file was not issued by us. Store the licence file you received from your "
                    + "account manager.";
            case LICENCE_WRONG_CUSTOMER -> "Store the licence issued for this installation; licences cannot move "
                    + "between customers or between production and test environments.";
            case LICENCE_OLDER_VERSION, LICENCE_VERSION_CONFLICT -> "Nothing changed. Store the newest licence file "
                    + "you received.";
        };
    }
}
