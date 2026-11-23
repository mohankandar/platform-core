package com.documentplatform.setup;

import java.util.List;
import java.util.Objects;

/**
 * Setup stopped with {@link #code()}. The message is the code and the cause; {@link #details()} lists line-by-line
 * problems (for example mapping file errors). Never holds secrets.
 */
public final class SetupException extends RuntimeException implements com.documentplatform.errors.HasPlatformCode {

    private final SetupRefusal code;
    private final List<String> details;

    public SetupException(SetupRefusal code, String cause) {
        this(code, cause, List.of());
    }

    public SetupException(SetupRefusal code, String cause, List<String> details) {
        super(code.name() + ": " + cause);
        this.code = Objects.requireNonNull(code, "code");
        this.details = List.copyOf(details);
    }

    public SetupRefusal code() {
        return code;
    }

    /** The code, for monitoring and the app's error handler ({@code HasPlatformCode}). */
    @Override
    public com.documentplatform.errors.PlatformCode platformCode() {
        return code;
    }

    public List<String> details() {
        return details;
    }
}
