package com.documentplatform.integration.config;

import com.documentplatform.integration.IntegrationCode;

import java.util.Objects;

/**
 * One problem (or warning) found when a configuration is saved. Names where, never a secret or a value.
 *
 * @param code        the problem's code
 * @param where       which part, for example {@code steps[1].headers.X-Name} or {@code signIn}
 * @param placeholder the placeholder concerned, or null
 */
public record ConfigProblem(IntegrationCode code, String where, String placeholder) {

    public ConfigProblem {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(where, "where");
    }

    @Override
    public String toString() {
        return code + " at " + where + (placeholder == null ? "" : " {" + placeholder + "}");
    }
}
