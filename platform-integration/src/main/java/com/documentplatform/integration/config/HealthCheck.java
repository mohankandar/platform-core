package com.documentplatform.integration.config;

import java.util.List;
import java.util.Objects;

/**
 * The destination's health check: one configured call, classified by its own rules (then the defaults: 2xx healthy).
 *
 * @param method GET or HEAD normally
 * @param path   path added to the base address; no placeholders but {@code {correlationId}}
 * @param signIn whether the call signs in (a readiness address usually needs no sign-in)
 * @param rules  its own classification rules
 */
public record HealthCheck(HttpMethod method, String path, boolean signIn, List<Rule> rules) {

    public HealthCheck {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    public static HealthCheck get(String path, boolean signIn) {
        return new HealthCheck(HttpMethod.GET, path, signIn, List.of());
    }

    public HealthCheck withRules(List<Rule> r) {
        return new HealthCheck(method, path, signIn, r);
    }
}
