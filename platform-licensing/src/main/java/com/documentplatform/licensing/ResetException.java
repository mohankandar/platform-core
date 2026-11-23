package com.documentplatform.licensing;

import java.util.Objects;

/** A last-seen reset was refused. Message: code, meaning, exact cause (IDs and times only). */
public class ResetException extends RuntimeException implements com.documentplatform.errors.HasPlatformCode {

    private final ResetRefusal code;

    public ResetException(ResetRefusal code, String cause) {
        super(code.name() + ": " + code.meaning() + (cause == null || cause.isBlank() ? "" : " " + cause));
        this.code = Objects.requireNonNull(code, "code");
    }

    public ResetRefusal code() {
        return code;
    }

    /** The code, for monitoring and the app's error handler ({@code HasPlatformCode}). */
    @Override
    public com.documentplatform.errors.PlatformCode platformCode() {
        return code;
    }
}
