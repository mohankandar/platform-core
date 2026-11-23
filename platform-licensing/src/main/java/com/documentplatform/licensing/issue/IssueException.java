package com.documentplatform.licensing.issue;

import java.util.Objects;

/** The issuing command refused. Message: code, meaning, exact cause. Never key material or signatures. */
public class IssueException extends RuntimeException implements com.documentplatform.errors.HasPlatformCode {

    private final IssueError code;

    public IssueException(IssueError code, String cause) {
        super(code.name() + ": " + code.meaning() + (cause == null ? "" : " " + cause));
        this.code = Objects.requireNonNull(code, "code");
    }

    public IssueError code() {
        return code;
    }

    /** The code, for monitoring and the app's error handler ({@code HasPlatformCode}). */
    @Override
    public com.documentplatform.errors.PlatformCode platformCode() {
        return code;
    }
}
