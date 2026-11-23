package com.documentplatform.licensing;

/** Why a last-seen reset was not applied. Stable codes; nothing changes when one is refused. */
public enum ResetRefusal implements com.documentplatform.errors.PlatformCode {
    RESET_MISSING("No reset file was given."),
    RESET_MALFORMED("The reset is not a readable reset file."),
    RESET_UNSUPPORTED_FORMAT("The reset uses a format or signature algorithm this version does not read."),
    RESET_UNKNOWN_KEY_ID("The reset is signed with a key this version does not know."),
    RESET_TAMPERED("The reset content was changed after it was signed."),
    RESET_FORGED("The reset signature was not made by the named key."),
    RESET_WRONG_CUSTOMER("The reset was issued for a different customer (tenant) than this installation."),
    RESET_EXPIRED("The reset is past its validity.");

    private final String meaning;

    ResetRefusal(String meaning) {
        this.meaning = meaning;
    }

    public String meaning() {
        return meaning;
    }
}
