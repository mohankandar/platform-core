package com.documentplatform.integration.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
 * @param profile          the ready-made profile this configuration came from (a published API's name), or null
 * @param allowedSensitiveFields the sensitive case fields (platform-fields keys) this integration may send; any other
 *                         sensitive field in a template is refused when saved and when sent
 * @param settings         named settings: {@link #REQUIRED_TOKEN_CLAIMS} (claims the OAuth 2.0 token must carry,
 *                         comma-separated), and a profile's own settings (for example {@code senderIdentity})
 * @param folder           where a folder export writes (kind {@code FOLDER_EXPORT}); null for the other kinds
 * @param callback         the signing secret of a callback (kind {@code CALLBACK}); null for the other kinds
 */
public record IntegrationConfig(UUID id, UUID entity, String product, Kind kind, String name, boolean enabled,
                                Endpoint endpoint, SignIn signIn, Limits limits, List<CallStep> steps,
                                String errorCodePointer, List<Rule> rules, HealthCheck health, TestMode testMode,
                                boolean sendCorrelationId, String profile, Set<String> allowedSensitiveFields,
                                Map<String, String> settings, FolderTarget folder, CallbackTarget callback) {

    public enum TestMode {
        /** Every step, with fake data, against the destination (its test entity). */
        FULL,
        /** Only the health check and the sign-in (token fetch, secrets found); nothing is sent. */
        CHECK_ONLY
    }

    /** Setting: the claims the OAuth 2.0 access token must carry, comma-separated (the token must be a JWT). */
    public static final String REQUIRED_TOKEN_CLAIMS = "token.requiredClaims";

    private static final Pattern PRODUCT = Pattern.compile("[A-Z][A-Z0-9_]{1,31}");
    private static final Pattern PROFILE = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern SETTING = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");

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
        allowedSensitiveFields = allowedSensitiveFields == null ? Set.of()
                : java.util.Collections.unmodifiableSet(new TreeSet<>(allowedSensitiveFields));
        Map<String, String> s = new TreeMap<>();
        if (settings != null) {
            settings.forEach((k, v) -> {
                if (k == null || !SETTING.matcher(k).matches() || v == null || v.length() > 500
                        || v.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
                    throw new IllegalArgumentException("A setting's name is 1-64 of A-Z a-z 0-9 _ . - and its value "
                            + "at most 500 characters");
                }
                s.put(k, v);
            });
        }
        settings = java.util.Collections.unmodifiableMap(s);
    }

    /** Without allowed sensitive fields or settings. */
    public IntegrationConfig(UUID id, UUID entity, String product, Kind kind, String name, boolean enabled,
                             Endpoint endpoint, SignIn signIn, Limits limits, List<CallStep> steps,
                             String errorCodePointer, List<Rule> rules, HealthCheck health, TestMode testMode,
                             boolean sendCorrelationId, String profile) {
        this(id, entity, product, kind, name, enabled, endpoint, signIn, limits, steps, errorCodePointer, rules,
                health, testMode, sendCorrelationId, profile, Set.of(), Map.of(), null, null);
    }

    /** With a folder target, without a callback (the kinds before P026). */
    public IntegrationConfig(UUID id, UUID entity, String product, Kind kind, String name, boolean enabled,
                             Endpoint endpoint, SignIn signIn, Limits limits, List<CallStep> steps,
                             String errorCodePointer, List<Rule> rules, HealthCheck health, TestMode testMode,
                             boolean sendCorrelationId, String profile, Set<String> allowedSensitiveFields,
                             Map<String, String> settings, FolderTarget folder) {
        this(id, entity, product, kind, name, enabled, endpoint, signIn, limits, steps, errorCodePointer, rules,
                health, testMode, sendCorrelationId, profile, allowedSensitiveFields, settings, folder, null);
    }

    /**
     * A new callback configuration, switched on, with default limits: signed POSTs to {@code receiver} (its full
     * address), signed with the secret behind {@code signingSecret}. Sign-in NONE is allowed here (the signature
     * proves the sender); another sign-in type (for example mutual TLS) is added on top.
     */
    public static IntegrationConfig callback(UUID entity, String product, String name, Endpoint receiver,
                                             SignIn signIn, com.documentplatform.notifications.secret.SecretRef
                                                     signingSecret) {
        return new IntegrationConfig(UUID.randomUUID(), entity, product, Kind.CALLBACK, name, true, receiver, signIn,
                Limits.DEFAULT, List.of(), null, List.of(), null, TestMode.FULL, true, null, Set.of(), Map.of(), null,
                new CallbackTarget(signingSecret));
    }

    /** The callback's signing secret. */
    public IntegrationConfig withCallback(CallbackTarget t) {
        return new IntegrationConfig(id, entity, product, kind, name, enabled, endpoint, signIn, limits, steps,
                errorCodePointer, rules, health, testMode, sendCorrelationId, profile, allowedSensitiveFields, settings,
                folder, t);
    }

    /** Without a folder target (the API kinds). */
    public IntegrationConfig(UUID id, UUID entity, String product, Kind kind, String name, boolean enabled,
                             Endpoint endpoint, SignIn signIn, Limits limits, List<CallStep> steps,
                             String errorCodePointer, List<Rule> rules, HealthCheck health, TestMode testMode,
                             boolean sendCorrelationId, String profile, Set<String> allowedSensitiveFields,
                             Map<String, String> settings) {
        this(id, entity, product, kind, name, enabled, endpoint, signIn, limits, steps, errorCodePointer, rules,
                health, testMode, sendCorrelationId, profile, allowedSensitiveFields, settings, null, null);
    }

    public Direction direction() {
        return kind.direction();
    }

    /** The claims the token must carry ({@link #REQUIRED_TOKEN_CLAIMS}), empty when none. */
    public List<String> requiredTokenClaims() {
        String v = settings.get(REQUIRED_TOKEN_CLAIMS);
        if (v == null || v.isBlank()) return List.of();
        return java.util.Arrays.stream(v.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    /** A new API push configuration, switched on, with default limits, no steps, rules or health check. */
    public static IntegrationConfig apiPush(UUID entity, String product, String name, Endpoint endpoint,
                                            SignIn signIn) {
        return new IntegrationConfig(UUID.randomUUID(), entity, product, Kind.API_PUSH, name, true, endpoint, signIn,
                Limits.DEFAULT, List.of(), null, List.of(), null, TestMode.FULL, true, null);
    }

    /**
     * A new folder export configuration, switched on, with default limits: the package folder named by
     * {@code target}, the manifest written last. No sign-in bundle: the folder's rights are the service account's.
     */
    public static IntegrationConfig folderExport(UUID entity, String product, String name, FolderTarget target) {
        return new IntegrationConfig(UUID.randomUUID(), entity, product, Kind.FOLDER_EXPORT, name, true, null,
                new SignIn.None(), Limits.DEFAULT, List.of(), null, List.of(), null, TestMode.FULL, true, null,
                Set.of(), Map.of(), Objects.requireNonNull(target, "target"), null);
    }

    private IntegrationConfig with(String n, boolean on, Endpoint e, SignIn s, Limits l, List<CallStep> st, String p,
                                   List<Rule> r, HealthCheck h, TestMode t, boolean corr, String prof,
                                   Set<String> sens, Map<String, String> set) {
        return new IntegrationConfig(id, entity, product, kind, n, on, e, s, l, st, p, r, h, t, corr, prof, sens, set,
                folder, callback);
    }

    /** Where a folder export writes. */
    public IntegrationConfig withFolder(FolderTarget f) {
        return new IntegrationConfig(id, entity, product, kind, name, enabled, endpoint, signIn, limits, steps,
                errorCodePointer, rules, health, testMode, sendCorrelationId, profile, allowedSensitiveFields, settings,
                f, callback);
    }

    public IntegrationConfig withEnabled(boolean on) {
        return with(name, on, endpoint, signIn, limits, steps, errorCodePointer, rules, health, testMode,
                sendCorrelationId, profile, allowedSensitiveFields, settings);
    }

    public IntegrationConfig withName(String n) {
        return with(n, enabled, endpoint, signIn, limits, steps, errorCodePointer, rules, health, testMode,
                sendCorrelationId, profile, allowedSensitiveFields, settings);
    }

    public IntegrationConfig withEndpoint(Endpoint e) {
        return with(name, enabled, e, signIn, limits, steps, errorCodePointer, rules, health, testMode,
                sendCorrelationId, profile, allowedSensitiveFields, settings);
    }

    public IntegrationConfig withSignIn(SignIn s) {
        return with(name, enabled, endpoint, s, limits, steps, errorCodePointer, rules, health, testMode,
                sendCorrelationId, profile, allowedSensitiveFields, settings);
    }

    public IntegrationConfig withLimits(Limits l) {
        return with(name, enabled, endpoint, signIn, l, steps, errorCodePointer, rules, health, testMode,
                sendCorrelationId, profile, allowedSensitiveFields, settings);
    }

    public IntegrationConfig withSteps(List<CallStep> s) {
        return with(name, enabled, endpoint, signIn, limits, s, errorCodePointer, rules, health, testMode,
                sendCorrelationId, profile, allowedSensitiveFields, settings);
    }

    public IntegrationConfig withErrorCodePointer(String p) {
        return with(name, enabled, endpoint, signIn, limits, steps, p, rules, health, testMode, sendCorrelationId,
                profile, allowedSensitiveFields, settings);
    }

    public IntegrationConfig withRules(List<Rule> r) {
        return with(name, enabled, endpoint, signIn, limits, steps, errorCodePointer, r, health, testMode,
                sendCorrelationId, profile, allowedSensitiveFields, settings);
    }

    public IntegrationConfig withHealth(HealthCheck h) {
        return with(name, enabled, endpoint, signIn, limits, steps, errorCodePointer, rules, h, testMode,
                sendCorrelationId, profile, allowedSensitiveFields, settings);
    }

    public IntegrationConfig withTestMode(TestMode t) {
        return with(name, enabled, endpoint, signIn, limits, steps, errorCodePointer, rules, health, t,
                sendCorrelationId, profile, allowedSensitiveFields, settings);
    }

    public IntegrationConfig withSendCorrelationId(boolean b) {
        return with(name, enabled, endpoint, signIn, limits, steps, errorCodePointer, rules, health, testMode, b,
                profile, allowedSensitiveFields, settings);
    }

    public IntegrationConfig withProfile(String p) {
        return with(name, enabled, endpoint, signIn, limits, steps, errorCodePointer, rules, health, testMode,
                sendCorrelationId, p, allowedSensitiveFields, settings);
    }

    /** Allows these sensitive case fields (platform-fields keys) to be sent; recorded in the version and audited. */
    public IntegrationConfig withAllowedSensitiveFields(Set<String> keys) {
        return with(name, enabled, endpoint, signIn, limits, steps, errorCodePointer, rules, health, testMode,
                sendCorrelationId, profile, keys, settings);
    }

    public IntegrationConfig withSettings(Map<String, String> s) {
        return with(name, enabled, endpoint, signIn, limits, steps, errorCodePointer, rules, health, testMode,
                sendCorrelationId, profile, allowedSensitiveFields, s);
    }

    public IntegrationConfig withSetting(String key, String value) {
        Map<String, String> s = new LinkedHashMap<>(settings);
        if (value == null) s.remove(key);
        else s.put(key, value);
        return withSettings(s);
    }
}
