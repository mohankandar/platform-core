package com.documentplatform.licensing;

import java.util.Objects;

/**
 * A licence was refused. {@link #code()} is stable; the message is the code, its meaning and the exact cause. The
 * message never contains key material, signatures or licence content beyond IDs.
 */
public class LicenceException extends RuntimeException implements com.documentplatform.errors.HasPlatformCode {

    private final LicenceRefusal code;

    public LicenceException(LicenceRefusal code, String cause) {
        super(code.name() + ": " + code.meaning() + (cause == null || cause.isBlank() ? "" : " " + cause));
        this.code = Objects.requireNonNull(code, "code");
    }

    public LicenceException(LicenceRefusal code) {
        this(code, null);
    }

    @Override
    public com.documentplatform.errors.PlatformCode platformCode() {
        return code();
    }

    public LicenceRefusal code() {
        return code;
    }
}
