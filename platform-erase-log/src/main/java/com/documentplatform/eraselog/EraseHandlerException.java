package com.documentplatform.eraselog;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A handler refused or failed. {@link #code()} is the handler's own stable code (for example
 * {@code VALUES_LEGAL_HOLD}); the message holds ids and neutral reasons only, never a value. No cause is attached.
 */
public final class EraseHandlerException extends RuntimeException
        implements com.documentplatform.errors.HasPlatformCode {

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");

    private final String code;
    private final com.documentplatform.errors.PlatformCode platformCode;

    /** With the handler's own code as text (an app's handler without a code enum). */
    public EraseHandlerException(String code, String message) {
        super(check(code) + ": " + message, null, false, true);
        this.code = code;
        this.platformCode = null;
    }

    /** With the handler's own {@code PlatformCode} (for example {@code FieldValuesError.VALUES_LEGAL_HOLD}). */
    public EraseHandlerException(com.documentplatform.errors.PlatformCode code, String message) {
        super(check(Objects.requireNonNull(code, "code").code()) + ": " + message, null, false, true);
        this.code = code.code();
        this.platformCode = code;
    }

    private static String check(String code) {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("A handler code is 2 to 64 of A-Z 0-9 _ starting with a letter");
        }
        return code;
    }

    public String code() {
        return code;
    }

    /**
     * The handler's own code when it gave a {@code PlatformCode}; else {@link EraseLogError#ERASE_HANDLER_FAILED} (the
     * text code is in {@link #code()} and the message).
     */
    @Override
    public com.documentplatform.errors.PlatformCode platformCode() {
        return platformCode != null ? platformCode : EraseLogError.ERASE_HANDLER_FAILED;
    }

    /** The message without the code in front. */
    public String reason() {
        return getMessage().substring(code.length() + 2);
    }

    static EraseHandlerException unexpected(String kind, RuntimeException e) {
        return new EraseHandlerException(EraseLogError.ERASE_HANDLER_ERROR, "the " + Objects.requireNonNull(kind) + " handler "
                + "failed (" + e.getClass().getSimpleName() + ")");
    }
}
