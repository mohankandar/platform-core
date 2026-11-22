package com.documentplatform.integration.config;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One configured integration: a destination (outbound) or a source (inbound) of one entity in one product. Stored as
 * versions: every change is a new version with who and when ({@code store.IntegrationConfigs}). Holds secret
 * <em>references</em> only.
 *
 * @param id               stable ID
 * @param entity           the entity's UUID ({@code entity_uuid})
 * @param product          the product code (2–32 capital letters, digits or '_', starting with a letter)
 * @param kind             what it does; its direction follows from it
 * @param name             a name for people (1–200 characters)
 * @param enabled          switched on
 * @param endpoint         the destination's or source's address (API kinds); null for folder kinds
 * @param signIn           the sign-in bundle
 * @param limits           limits and timeouts
 * @param steps            the configured calls, in order (API_PUSH)
 * @param errorCodePointer where the destination puts its own error code in an answer ({@code /code}), or null
 * @param rules            classification rules (status + destination code → result class), before the defaults
 * @param health           the health check, or null when the destination has none
 * @param testMode         what the Test action runs: all steps with fake data, or only health and sign-in
 * @param sendCorrelationId whether each call carries {@code X-Correlation-Id}
 * @param profile          the ready-made profile this configuration came from (for example a published API's
 *                         name), or null
 */
public record IntegrationConfig(UUID id, UUID entity, String product, Kind kind, String name, boolean enabled,
                                Endpoint endpoint, SignIn signIn, Limits limits, List<CallStep> steps,
                                String errorCodePointer, List<Rule> rules, HealthCheck health, TestMode testMode,
                                boolean sendCorrelationId, String profile) {

    public enum TestMode {
        /** Every step, with fake data, against the destination (its test entity). */
        FULL,
        /** Only the health check and the sign-in (token fetch, secrets found); nothing is sent. */
        CHECK_ONLY
    }

    private static final Pattern PRODUCT = Pattern.compile("[A-Z][A-Z0-9_]{1,31}");
    private static final Pattern PROFILE = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public IntegrationConfig {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(signIn, "signIn");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(testMode, "testMode");
        if (!PRODUCT.matcher(product).matches()) {
            throw new IllegalArgumentException("A product code has 2 to 32 capital letters, digits or '_' and starts "
                    + "with a letter");
        }
        if (name.isBlank() || name.length() > 200 || name.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            throw new IllegalArgumentException("Name must be 1-200 characters without control characters");
        }
        if (profile != null && !PROFILE.matcher(profile).matches()) {
            throw new IllegalArgumentException("Profile name is not valid");
        }
        steps = steps == null ? List.of() : List.copyOf(steps);
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    public Direction direction() {
        return kind.direction();
    }

    /** A new API push configuration, switched on, with default limits, no steps, rules or health check. */
    public static IntegrationConfig apiPush(UUID entity, String product, String name, Endpoint endpoint,
                                            SignIn signIn) {
        return new IntegrationConfig(UUID.randomUUID(), entity, product, Kind.API_PUSH, name, true, endpoint, signIn,
                Limits.DEFAULT, List.of(), null, List.of(), null, TestMode.FULL, true, null);
    }

    public IntegrationConfig withEnabled(boolean on) {
        return new IntegrationConfig(id, entity, product, kind, name, on, endpoint, signIn, limits, steps,
                errorCodePointer, rules, health, testMode, sendCorrelationId, profile);
    }

    public IntegrationConfig withName(String n) {
        return new IntegrationConfig(id, entity, product, kind, n, enabled, endpoint, signIn, limits, steps,
                errorCodePointer, rules, health, testMode, sendCorrelationId, profile);
    }

    public IntegrationConfig withEndpoint(Endpoint e) {
        return new IntegrationConfig(id, entity, product, kind, name, enabled, e, signIn, limits, steps,
                errorCodePointer, rules, health, testMode, sendCorrelationId, profile);
    }

    public IntegrationConfig withSignIn(SignIn s) {
        return new IntegrationConfig(id, entity, product, kind, name, enabled, endpoint, s, limits, steps,
                errorCodePointer, rules, health, testMode, sendCorrelationId, profile);
    }

    public IntegrationConfig withLimits(Limits l) {
        return new IntegrationConfig(id, entity, product, kind, name, enabled, endpoint, signIn, l, steps,
                errorCodePointer, rules, health, testMode, sendCorrelationId, profile);
    }

    public IntegrationConfig withSteps(List<CallStep> s) {
        return new IntegrationConfig(id, entity, product, kind, name, enabled, endpoint, signIn, limits, s,
                errorCodePointer, rules, health, testMode, sendCorrelationId, profile);
    }

    public IntegrationConfig withErrorCodePointer(String p) {
        return new IntegrationConfig(id, entity, product, kind, name, enabled, endpoint, signIn, limits, steps,
                p, rules, health, testMode, sendCorrelationId, profile);
    }

    public IntegrationConfig withRules(List<Rule> r) {
        return new IntegrationConfig(id, entity, product, kind, name, enabled, endpoint, signIn, limits, steps,
                errorCodePointer, r, health, testMode, sendCorrelationId, profile);
    }

    public IntegrationConfig withHealth(HealthCheck h) {
        return new IntegrationConfig(id, entity, product, kind, name, enabled, endpoint, signIn, limits, steps,
                errorCodePointer, rules, h, testMode, sendCorrelationId, profile);
    }

    public IntegrationConfig withTestMode(TestMode t) {
        return new IntegrationConfig(id, entity, product, kind, name, enabled, endpoint, signIn, limits, steps,
                errorCodePointer, rules, health, t, sendCorrelationId, profile);
    }

    public IntegrationConfig withSendCorrelationId(boolean b) {
        return new IntegrationConfig(id, entity, product, kind, name, enabled, endpoint, signIn, limits, steps,
                errorCodePointer, rules, health, testMode, b, profile);
    }

    public IntegrationConfig withProfile(String p) {
        return new IntegrationConfig(id, entity, product, kind, name, enabled, endpoint, signIn, limits, steps,
                errorCodePointer, rules, health, testMode, sendCorrelationId, p);
    }
}
