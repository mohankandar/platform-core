package com.documentplatform.licensing;

import java.util.Objects;

/**
 * A licence was refused. {@link #code()} is stable; the message is the code, its meaning and the exact cause. The
 * message never contains key material, signatures or licence content beyond IDs.
 */
public class LicenceException extends RuntimeException {

    private final LicenceRefusal code;

    public LicenceException(LicenceRefusal code, String cause) {
        super(code.name() + ": " + code.meaning() + (cause == null || cause.isBlank() ? "" : " " + cause));
        this.code = Objects.requireNonNull(code, "code");
    }

    public LicenceException(LicenceRefusal code) {
        this(code, null);
    }

    public LicenceRefusal code() {
        return code;
    }
}
