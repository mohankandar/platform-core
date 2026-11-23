package com.documentplatform.licensing.usage;

/** Stable codes for usage recording that cannot go ahead. None of them is a limit: limits never stop recording. */
public enum UsageError implements com.documentplatform.errors.PlatformCode {
    USAGE_UNKNOWN_SLOT("The entity slot is not in the current licence."),
    USAGE_UNKNOWN_PRODUCT("The product is not in the current licence."),
    USAGE_NEGATIVE_AMOUNT("A usage amount cannot be below zero.");

    private final String meaning;

    UsageError(String meaning) {
        this.meaning = meaning;
    }

    public String meaning() {
        return meaning;
    }
}
