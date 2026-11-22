package com.documentplatform.integration.profile;

import com.documentplatform.integration.IntegrationCode;

import java.util.Objects;

/**
 * One problem of a profile: its code, where it is (a JSON pointer into the profile, {@code ""} for the whole file)
 * and the rule broken (a schema rule or the underlying configuration code). Never a value from the profile.
 */
public record ProfileProblem(IntegrationCode code, String pointer, String detail) {

    public ProfileProblem {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(pointer, "pointer");
    }

    @Override
    public String toString() {
        return code + " at " + (pointer.isEmpty() ? "/" : pointer) + (detail == null ? "" : " (" + detail + ")");
    }
}
