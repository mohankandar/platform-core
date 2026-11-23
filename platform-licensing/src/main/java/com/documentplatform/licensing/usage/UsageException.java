package com.documentplatform.licensing.usage;

import java.util.Objects;

/** Usage could not be recorded. {@link #code()} is stable; the message gives the exact cause (IDs only). */
public class UsageException extends RuntimeException implements com.documentplatform.errors.HasPlatformCode {

    private final UsageError code;

    public UsageException(UsageError code, String cause) {
        super(code.name() + ": " + code.meaning() + " " + cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public UsageError code() {
        return code;
    }

    /** The code, for monitoring and the app's error handler ({@code HasPlatformCode}). */
    @Override
    public com.documentplatform.errors.PlatformCode platformCode() {
        return code;
    }
}
