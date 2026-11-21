package com.documentplatform.licensing;

import java.util.Objects;

/** A last-seen reset was refused. Message: code, meaning, exact cause (IDs and times only). */
public class ResetException extends RuntimeException {

    private final ResetRefusal code;

    public ResetException(ResetRefusal code, String cause) {
        super(code.name() + ": " + code.meaning() + (cause == null || cause.isBlank() ? "" : " " + cause));
        this.code = Objects.requireNonNull(code, "code");
    }

    public ResetRefusal code() {
        return code;
    }
}
