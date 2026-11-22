package com.documentplatform.eraselog;

import java.util.Objects;
import java.util.Optional;

/**
 * An erase log call refused or failed; {@link #code()} says why, and for {@link EraseLogError#ERASE_HANDLER_FAILED}
 * {@link #handlerCode()} is the handler's own code (for example {@code VALUES_LEGAL_HOLD}). The message holds ids and
 * neutral reasons only, never a value. No cause is attached, so nothing a store or handler carried can leak through a
 * stack trace.
 */
public final class EraseLogException extends RuntimeException {

    private final EraseLogError code;
    private final String handlerCode;

    public EraseLogException(EraseLogError code, String message) {
        this(code, null, message);
    }

    public EraseLogException(EraseLogError code, String handlerCode, String message) {
        super(Objects.requireNonNull(code, "code") + ": " + message, null, false, true);
        this.code = code;
        this.handlerCode = handlerCode;
    }

    public EraseLogError code() {
        return code;
    }

    /** The handler's own code, when a handler refused or failed. */
    public Optional<String> handlerCode() {
        return Optional.ofNullable(handlerCode);
    }

    /** The message without the code in front. */
    public String reason() {
        return getMessage().substring(code.name().length() + 2);
    }
}
