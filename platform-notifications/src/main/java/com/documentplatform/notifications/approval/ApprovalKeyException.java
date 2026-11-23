package com.documentplatform.notifications.approval;

import com.documentplatform.notifications.ChannelKind;
import com.documentplatform.notifications.FailureCode;

/**
 * The link key could not be read: not in the secret store, too short, or the store could not be reached. The text is
 * fixed by the code and never holds the key or the store's reply.
 */
public final class ApprovalKeyException extends RuntimeException implements com.documentplatform.errors.HasPlatformCode {

    private final FailureCode code;

    ApprovalKeyException(FailureCode code) {
        super(code.describe(ChannelKind.EMAIL) + " (" + code.name() + ")", null, false, false);
        this.code = code;
    }

    /** {@code ACTION_KEY_NOT_FOUND}, {@code ACTION_KEY_INVALID} or {@code SECRET_UNAVAILABLE}. */
    public FailureCode code() {
        return code;
    }

    /** The code, for monitoring and the app's error handler ({@code HasPlatformCode}). */
    @Override
    public com.documentplatform.errors.PlatformCode platformCode() {
        return code;
    }
}
